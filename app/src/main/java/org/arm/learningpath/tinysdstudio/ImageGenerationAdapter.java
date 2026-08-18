package org.arm.learningpath.tinysdstudio;

import android.graphics.Bitmap;
import android.net.Uri;

/** Connects a compatible model package to the image-generation user interface. */
public interface ImageGenerationAdapter extends AutoCloseable {
    String id();

    boolean isModelReady(ModelDescriptor model);

    void importModel(
            ModelDescriptor model,
            Uri archiveUri,
            ImportProgressListener progressListener
    ) throws Exception;

    GenerationResult generate(
            ModelDescriptor model,
            String prompt,
            long seed,
            GenerationProgressListener progressListener
    ) throws Exception;

    /** Releases heavyweight runtime state while leaving imported files in place. */
    void unloadModel();

    @Override
    void close();

    interface ImportProgressListener {
        void onProgress(String message, int percent);
    }

    interface GenerationProgressListener {
        void onProgress(String message, int completed, int total);
    }

    final class GenerationResult {
        public final Bitmap bitmap;
        public final long elapsedMs;

        public GenerationResult(Bitmap bitmap, long elapsedMs) {
            this.bitmap = bitmap;
            this.elapsedMs = elapsedMs;
        }
    }
}
