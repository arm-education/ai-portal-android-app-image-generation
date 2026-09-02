package org.arm.learningpath.tinysdstudio;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Models whose package and execution contracts have been confirmed with this app. */
public final class CompatibleModelRegistry {
    private static final long MINIMUM_RAM_BYTES = 7L * 1024 * 1024 * 1024;

    private static final List<ModelDescriptor> MODELS = Collections.singletonList(
            new ModelDescriptor(
                    "tinysd-int8-executorch",
                    "TinySD",
                    TinySdImageGenerationAdapter.ADAPTER_ID,
                    "tinysd_vivo_executorch.zip",
                    "tinysd",
                    "512 \u00D7 512",
                    "ExecuTorch + XNNPACK",
                    MINIMUM_RAM_BYTES,
                    Arrays.asList("optimized.pte", "schedule_data.json", "tokenizer.json")
            )
    );

    private CompatibleModelRegistry() {
    }

    public static List<ModelDescriptor> models() {
        List<ModelDescriptor> generatedModels = GeneratedAdapterRegistry.models();
        if (generatedModels.size() > 1) {
            throw new IllegalStateException(
                    "TinySD Studio supports one active generated model at a time"
            );
        }
        return generatedModels.isEmpty() ? MODELS : generatedModels;
    }
}
