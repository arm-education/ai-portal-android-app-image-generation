package org.arm.learningpath.tinysdstudio;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.pytorch.executorch.EValue;
import org.pytorch.executorch.Module;
import org.pytorch.executorch.Tensor;

import java.io.Closeable;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

final class TinySdRunner implements Closeable {
    private static final int TOKEN_COUNT = 77;
    private static final int EMBEDDING_SIZE = TOKEN_COUNT * 768;
    private static final int LATENT_CHANNELS = 4;
    private static final int LATENT_HEIGHT = 64;
    private static final int LATENT_WIDTH = 64;
    private static final int LATENT_SIZE = LATENT_CHANNELS * LATENT_HEIGHT * LATENT_WIDTH;
    private static final int IMAGE_SIZE = 512;
    private static final float GUIDANCE_SCALE = 7.5f;

    private final Module module;
    private final ScheduleData schedule;

    TinySdRunner(File modelFile, File scheduleFile) throws Exception {
        schedule = ScheduleData.load(scheduleFile);
        module = Module.load(modelFile.getAbsolutePath(), Module.LOAD_MODE_MMAP);
        Set<String> methods = new HashSet<>(Arrays.asList(module.getMethods()));
        for (String required : new String[]{"text_encoder", "unet", "vae_decoder"}) {
            if (!methods.contains(required)) {
                throw new IllegalArgumentException("Model is missing method: " + required);
            }
        }
    }

    GenerationResult generate(
            long[] promptTokens,
            long[] unconditionalTokens,
            long seed,
            ProgressListener progress
    ) {
        long started = System.nanoTime();
        progress.update("Encoding prompt", 0, schedule.numSteps + 2);

        float[] promptEmbedding = encode(promptTokens);
        float[] unconditionalEmbedding = encode(unconditionalTokens);
        float[] latents = createGaussianLatents(seed, schedule.initNoiseSigma);
        float[] previousModelOutput = new float[LATENT_SIZE];

        for (int step = 0; step < schedule.numSteps; step++) {
            progress.update(
                    "Denoising step " + (step + 1) + " of " + schedule.numSteps,
                    step + 1,
                    schedule.numSteps + 2
            );

            float[] unconditionalPrediction = runUnet(
                    latents,
                    schedule.timesteps[step],
                    unconditionalEmbedding
            );
            float[] conditionalPrediction = runUnet(
                    latents,
                    schedule.timesteps[step],
                    promptEmbedding
            );

            float[] modelOutput = new float[LATENT_SIZE];
            float[] nextLatents = new float[LATENT_SIZE];
            float sigmaCurrent = schedule.sigmaCurrent[step];
            float sigmaPrevious = schedule.sigmaPrevious[step];
            float alphaCurrent = schedule.alphaCurrentSqrt[step];
            float alphaPrevious = schedule.alphaPreviousSqrt[step];
            float phi1 = schedule.phi1[step];
            float r0 = schedule.r0[step];
            float secondOrderWeight = schedule.secondOrderWeight[step];

            for (int index = 0; index < LATENT_SIZE; index++) {
                float unconditionalNoise = unconditionalPrediction[index];
                float conditionalNoise = conditionalPrediction[index];
                float guidedNoise = unconditionalNoise
                        + GUIDANCE_SCALE * (conditionalNoise - unconditionalNoise);
                float currentModelOutput = (latents[index] - sigmaCurrent * guidedNoise)
                        / alphaCurrent;
                float firstOrder = currentModelOutput;
                float secondOrder = secondOrderWeight
                        * (currentModelOutput - previousModelOutput[index]) / r0;
                nextLatents[index] = (sigmaPrevious / sigmaCurrent) * latents[index]
                        - (alphaPrevious * phi1) * firstOrder
                        - (0.5f * alphaPrevious * phi1) * secondOrder;
                modelOutput[index] = currentModelOutput;
            }

            latents = nextLatents;
            previousModelOutput = modelOutput;
        }

        progress.update("Decoding image", schedule.numSteps + 1, schedule.numSteps + 2);
        Tensor latentTensor = Tensor.fromBlob(
                latents,
                new long[]{1, LATENT_CHANNELS, LATENT_HEIGHT, LATENT_WIDTH}
        );
        float[] pixels = module.execute(
                "vae_decoder",
                EValue.from(latentTensor)
        )[0].toTensor().getDataAsFloatArray();
        Bitmap bitmap = toBitmap(pixels);
        long elapsedMs = Math.round((System.nanoTime() - started) / 1_000_000.0);
        progress.update("Complete", schedule.numSteps + 2, schedule.numSteps + 2);
        return new GenerationResult(bitmap, elapsedMs);
    }

    private float[] encode(long[] tokens) {
        if (tokens.length != TOKEN_COUNT) {
            throw new IllegalArgumentException("Expected 77 CLIP tokens");
        }
        Tensor tokenTensor = Tensor.fromBlob(tokens, new long[]{1, TOKEN_COUNT});
        float[] embedding = module.execute(
                "text_encoder",
                EValue.from(tokenTensor)
        )[0].toTensor().getDataAsFloatArray();
        if (embedding.length != EMBEDDING_SIZE) {
            throw new IllegalStateException("Unexpected text embedding size: " + embedding.length);
        }
        return embedding;
    }

    private float[] runUnet(float[] latents, long timestep, float[] embedding) {
        Tensor latentTensor = Tensor.fromBlob(
                latents,
                new long[]{1, LATENT_CHANNELS, LATENT_HEIGHT, LATENT_WIDTH}
        );
        Tensor timestepTensor = Tensor.fromBlob(new long[]{timestep}, new long[]{1});
        Tensor embeddingTensor = Tensor.fromBlob(
                embedding,
                new long[]{1, TOKEN_COUNT, 768}
        );
        float[] prediction = module.execute(
                "unet",
                EValue.from(latentTensor),
                EValue.from(timestepTensor),
                EValue.from(embeddingTensor)
        )[0].toTensor().getDataAsFloatArray();
        if (prediction.length != LATENT_SIZE) {
            throw new IllegalStateException("Unexpected UNet output size: " + prediction.length);
        }
        return prediction;
    }

    private static float[] createGaussianLatents(long seed, float scale) {
        Random random = new Random(seed);
        float[] latents = new float[LATENT_SIZE];
        for (int index = 0; index < latents.length; index++) {
            latents[index] = (float) (random.nextGaussian() * scale);
        }
        return latents;
    }

    private static Bitmap toBitmap(float[] tensor) {
        int planeSize = IMAGE_SIZE * IMAGE_SIZE;
        if (tensor.length != 3 * planeSize) {
            throw new IllegalStateException("Unexpected VAE output size: " + tensor.length);
        }
        int[] colors = new int[planeSize];
        for (int index = 0; index < planeSize; index++) {
            int red = channelToByte(tensor[index]);
            int green = channelToByte(tensor[planeSize + index]);
            int blue = channelToByte(tensor[2 * planeSize + index]);
            colors[index] = Color.rgb(red, green, blue);
        }
        return Bitmap.createBitmap(colors, IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888);
    }

    private static int channelToByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    @Override
    public void close() {
        module.close();
    }

    interface ProgressListener {
        void update(String message, int completed, int total);
    }

    static final class GenerationResult {
        final Bitmap bitmap;
        final long elapsedMs;

        GenerationResult(Bitmap bitmap, long elapsedMs) {
            this.bitmap = bitmap;
            this.elapsedMs = elapsedMs;
        }
    }
}
