package org.arm.learningpath.tinysdstudio;

import android.content.Context;
import android.net.Uri;

import java.io.File;

/** Image-generation adapter for the supplied multimethod TinySD ExecuTorch package. */
public final class TinySdImageGenerationAdapter implements ImageGenerationAdapter {
    public static final String ADAPTER_ID = "tinysd-executorch";

    private static final String MODEL_NAME = "optimized.pte";
    private static final String SCHEDULE_NAME = "schedule_data.json";
    private static final String TOKENIZER_NAME = "tokenizer.json";

    private final Context context;
    private final Object runtimeLock = new Object();

    private TinySdRunner runner;
    private ClipTokenizer tokenizer;
    private String loadedModelId;
    private boolean generating;
    private boolean unloadRequested;
    private boolean closed;

    public TinySdImageGenerationAdapter(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String id() {
        return ADAPTER_ID;
    }

    @Override
    public boolean isModelReady(ModelDescriptor model) {
        requireCompatibleModel(model);
        File modelDirectory = modelDirectory(model);
        for (String requiredFile : model.requiredFiles()) {
            if (!new File(modelDirectory, requiredFile).isFile()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void importModel(
            ModelDescriptor model,
            Uri archiveUri,
            ImportProgressListener progressListener
    ) throws Exception {
        requireCompatibleModel(model);
        synchronized (runtimeLock) {
            ensureOpen();
            if (generating) {
                throw new IllegalStateException("Cannot replace the model during generation");
            }
            unloadModelLocked();
        }
        ModelImporter.importArchive(
                context.getContentResolver(),
                archiveUri,
                modelDirectory(model),
                progressListener::onProgress
        );
    }

    @Override
    public GenerationResult generate(
            ModelDescriptor model,
            String prompt,
            long seed,
            GenerationProgressListener progressListener
    ) throws Exception {
        requireCompatibleModel(model);
        if (!isModelReady(model)) {
            throw new IllegalStateException(model.displayName() + " is not installed");
        }
        TinySdRunner activeRunner;
        ClipTokenizer activeTokenizer;
        synchronized (runtimeLock) {
            ensureOpen();
            if (generating) {
                throw new IllegalStateException("Generation is already running");
            }
            generating = true;
            try {
                loadRuntimeLocked(model);
                activeRunner = runner;
                activeTokenizer = tokenizer;
            } catch (Exception | Error error) {
                generating = false;
                unloadModelLocked();
                throw error;
            }
        }

        try {
            long[] promptTokens = activeTokenizer.encode(prompt);
            long[] unconditionalTokens = activeTokenizer.encode("");
            TinySdRunner.GenerationResult result = activeRunner.generate(
                    promptTokens,
                    unconditionalTokens,
                    seed,
                    progressListener::onProgress
            );
            return new GenerationResult(result.bitmap, result.elapsedMs);
        } finally {
            synchronized (runtimeLock) {
                generating = false;
                if (unloadRequested) {
                    unloadModelLocked();
                    if (!closed) {
                        unloadRequested = false;
                    }
                }
            }
        }
    }

    @Override
    public void unloadModel() {
        synchronized (runtimeLock) {
            if (generating) {
                unloadRequested = true;
                return;
            }
            unloadModelLocked();
            if (!closed) {
                unloadRequested = false;
            }
        }
    }

    private void loadRuntimeLocked(ModelDescriptor model) throws Exception {
        if (loadedModelId != null && !loadedModelId.equals(model.id())) {
            unloadModelLocked();
        }
        if (runner != null && tokenizer != null) {
            return;
        }

        File modelDirectory = modelDirectory(model);
        TinySdRunner newRunner = null;
        try {
            newRunner = new TinySdRunner(
                    new File(modelDirectory, MODEL_NAME),
                    new File(modelDirectory, SCHEDULE_NAME)
            );
            ClipTokenizer newTokenizer = new ClipTokenizer(
                    new File(modelDirectory, TOKENIZER_NAME)
            );
            runner = newRunner;
            tokenizer = newTokenizer;
            loadedModelId = model.id();
        } catch (Exception | Error error) {
            if (newRunner != null) {
                newRunner.close();
            }
            runner = null;
            tokenizer = null;
            loadedModelId = null;
            throw error;
        }
    }

    private void unloadModelLocked() {
        if (runner != null) {
            runner.close();
            runner = null;
        }
        tokenizer = null;
        loadedModelId = null;
    }

    @Override
    public void close() {
        synchronized (runtimeLock) {
            closed = true;
            unloadRequested = true;
            if (!generating) {
                unloadModelLocked();
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("The adapter has been closed");
        }
    }

    private void requireCompatibleModel(ModelDescriptor model) {
        if (!ADAPTER_ID.equals(model.adapterId())) {
            throw new IllegalArgumentException("Unsupported adapter ID: " + model.adapterId());
        }
    }

    private File modelDirectory(ModelDescriptor model) {
        File externalFiles = context.getExternalFilesDir(null);
        File filesRoot = externalFiles != null ? externalFiles : context.getFilesDir();
        return new File(filesRoot, model.storageDirectoryName());
    }
}
