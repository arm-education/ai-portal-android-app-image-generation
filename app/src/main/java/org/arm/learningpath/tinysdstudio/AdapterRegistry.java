package org.arm.learningpath.tinysdstudio;

import android.content.Context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registers each available image-generation implementation by adapter ID. */
public final class AdapterRegistry implements AutoCloseable {
    private final Map<String, ImageGenerationAdapter> adapters;

    public AdapterRegistry(Context context) {
        Map<String, ImageGenerationAdapter> registeredAdapters = new LinkedHashMap<>();
        register(registeredAdapters, new TinySdImageGenerationAdapter(context));
        adapters = Collections.unmodifiableMap(registeredAdapters);
    }

    public List<ImageGenerationAdapter> adapters() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(adapters.values()));
    }

    public ImageGenerationAdapter requireAdapter(String adapterId) {
        ImageGenerationAdapter adapter = adapters.get(adapterId);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter is registered with ID " + adapterId);
        }
        return adapter;
    }

    public void unloadModels() {
        for (ImageGenerationAdapter adapter : adapters.values()) {
            adapter.unloadModel();
        }
    }

    @Override
    public void close() {
        for (ImageGenerationAdapter adapter : adapters.values()) {
            adapter.close();
        }
    }

    private static void register(
            Map<String, ImageGenerationAdapter> registeredAdapters,
            ImageGenerationAdapter adapter
    ) {
        if (registeredAdapters.put(adapter.id(), adapter) != null) {
            throw new IllegalStateException("Duplicate adapter ID: " + adapter.id());
        }
    }
}
