package org.arm.learningpath.tinysdstudio;

import android.content.Context;

import java.util.List;

/** Build-time extension point for reviewed generated adapters and one active model. */
final class GeneratedAdapterRegistry {
    private GeneratedAdapterRegistry() {
    }

    static List<ImageGenerationAdapter> adapters(Context context) {
        return List.of();
    }

    static List<ModelDescriptor> models() {
        return List.of();
    }
}
