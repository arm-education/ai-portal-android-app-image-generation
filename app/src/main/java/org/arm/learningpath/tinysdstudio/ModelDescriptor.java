package org.arm.learningpath.tinysdstudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Describes one model package that can be loaded by an image-generation adapter. */
public final class ModelDescriptor {
    private final String id;
    private final String displayName;
    private final String adapterId;
    private final String archiveFileName;
    private final String storageDirectoryName;
    private final String outputDescription;
    private final String runtimeName;
    private final long minimumMemoryBytes;
    private final List<String> requiredFiles;

    public ModelDescriptor(
            String id,
            String displayName,
            String adapterId,
            String archiveFileName,
            String storageDirectoryName,
            String outputDescription,
            String runtimeName,
            long minimumMemoryBytes,
            List<String> requiredFiles
    ) {
        this.id = id;
        this.displayName = displayName;
        this.adapterId = adapterId;
        this.archiveFileName = archiveFileName;
        this.storageDirectoryName = storageDirectoryName;
        this.outputDescription = outputDescription;
        this.runtimeName = runtimeName;
        this.minimumMemoryBytes = minimumMemoryBytes;
        this.requiredFiles = Collections.unmodifiableList(new ArrayList<>(requiredFiles));
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String adapterId() {
        return adapterId;
    }

    public String archiveFileName() {
        return archiveFileName;
    }

    public String storageDirectoryName() {
        return storageDirectoryName;
    }

    public String outputDescription() {
        return outputDescription;
    }

    public String runtimeName() {
        return runtimeName;
    }

    public long minimumMemoryBytes() {
        return minimumMemoryBytes;
    }

    public List<String> requiredFiles() {
        return requiredFiles;
    }
}
