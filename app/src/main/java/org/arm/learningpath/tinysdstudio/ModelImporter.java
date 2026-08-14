package org.arm.learningpath.tinysdstudio;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

final class ModelImporter {
    interface ProgressListener {
        void onProgress(String message, int percent);
    }

    private static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
    private static final int LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;
    private static final int MAX_END_RECORD_SIZE = 65_557;
    private static final int MAX_CENTRAL_DIRECTORY_SIZE = 16 * 1024 * 1024;
    private static final Map<String, String> REQUIRED_ENTRIES = new LinkedHashMap<>();

    static {
        REQUIRED_ENTRIES.put("/huggingface/optimized.pte", "optimized.pte");
        REQUIRED_ENTRIES.put("/huggingface/schedule_data.json", "schedule_data.json");
        REQUIRED_ENTRIES.put("/tokenizer/tokenizer.json", "tokenizer.json");
    }

    private ModelImporter() {}

    static void importArchive(
            ContentResolver contentResolver,
            Uri archiveUri,
            File modelDirectory,
            ProgressListener progressListener
    ) throws Exception {
        File parentDirectory = modelDirectory.getParentFile();
        if (parentDirectory == null || (!parentDirectory.isDirectory() && !parentDirectory.mkdirs())) {
            throw new IllegalStateException("Could not create the app model directory");
        }

        File stagingDirectory = new File(parentDirectory, modelDirectory.getName() + ".importing");
        File backupDirectory = new File(parentDirectory, modelDirectory.getName() + ".backup");
        deleteRecursively(stagingDirectory);
        deleteRecursively(backupDirectory);
        if (!stagingDirectory.mkdirs()) {
            throw new IllegalStateException("Could not create temporary model storage");
        }

        try {
            progressListener.onProgress("Checking the TinySD archive…", 0);
            try (ParcelFileDescriptor descriptor = contentResolver.openFileDescriptor(archiveUri, "r")) {
                if (descriptor == null) {
                    throw new IllegalArgumentException("The selected file could not be opened");
                }
                try (ParcelFileDescriptor.AutoCloseInputStream input =
                             new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                    FileChannel archive = input.getChannel();
                    Map<String, ArchiveEntry> entries = findRequiredEntries(archive);
                    extractRequiredEntries(archive, entries, stagingDirectory, progressListener);
                }
            }

            validateImportedFiles(stagingDirectory);
            replaceModelDirectory(modelDirectory, stagingDirectory, backupDirectory);
            progressListener.onProgress("TinySD model imported successfully.", 100);
        } catch (Throwable error) {
            deleteRecursively(stagingDirectory);
            throw error;
        } finally {
            deleteRecursively(backupDirectory);
        }
    }

    private static Map<String, ArchiveEntry> findRequiredEntries(FileChannel archive) throws Exception {
        long archiveSize = archive.size();
        int tailSize = (int) Math.min(archiveSize, MAX_END_RECORD_SIZE);
        ByteBuffer tail = readBuffer(archive, archiveSize - tailSize, tailSize);
        int endRecordOffset = -1;
        for (int offset = tailSize - 22; offset >= 0; offset--) {
            if (tail.getInt(offset) == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                endRecordOffset = offset;
                break;
            }
        }
        if (endRecordOffset < 0) {
            throw new IllegalArgumentException("The selected file is not a supported ZIP archive");
        }

        int entryCount = unsignedShort(tail, endRecordOffset + 10);
        long centralDirectorySize = unsignedInt(tail, endRecordOffset + 12);
        long centralDirectoryOffset = unsignedInt(tail, endRecordOffset + 16);
        if (centralDirectorySize <= 0
                || centralDirectorySize > MAX_CENTRAL_DIRECTORY_SIZE
                || centralDirectoryOffset + centralDirectorySize > archiveSize) {
            throw new IllegalArgumentException("The ZIP central directory is invalid or unsupported");
        }

        ByteBuffer directory = readBuffer(
                archive,
                centralDirectoryOffset,
                (int) centralDirectorySize
        );
        Map<String, ArchiveEntry> matches = new LinkedHashMap<>();
        int offset = 0;
        for (int index = 0; index < entryCount; index++) {
            if (offset + 46 > directory.limit()
                    || directory.getInt(offset) != CENTRAL_DIRECTORY_SIGNATURE) {
                throw new IllegalArgumentException("The ZIP central directory is incomplete");
            }

            int compressionMethod = unsignedShort(directory, offset + 10);
            long crc = unsignedInt(directory, offset + 16);
            long compressedSize = unsignedInt(directory, offset + 20);
            long uncompressedSize = unsignedInt(directory, offset + 24);
            int nameLength = unsignedShort(directory, offset + 28);
            int extraLength = unsignedShort(directory, offset + 30);
            int commentLength = unsignedShort(directory, offset + 32);
            long localHeaderOffset = unsignedInt(directory, offset + 42);
            int nextOffset = offset + 46 + nameLength + extraLength + commentLength;
            if (nextOffset > directory.limit()) {
                throw new IllegalArgumentException("The ZIP entry metadata is incomplete");
            }

            String name = new String(
                    directory.array(),
                    directory.arrayOffset() + offset + 46,
                    nameLength,
                    StandardCharsets.UTF_8
            );
            String matchedSuffix = matchingSuffix(name);
            if (matchedSuffix != null && !matches.containsKey(matchedSuffix)) {
                if (compressionMethod != 0 || compressedSize != uncompressedSize) {
                    throw new IllegalArgumentException("The required TinySD files must be stored uncompressed");
                }
                matches.put(matchedSuffix, new ArchiveEntry(
                        name,
                        localHeaderOffset,
                        uncompressedSize,
                        crc
                ));
            }
            offset = nextOffset;
        }

        if (matches.size() != REQUIRED_ENTRIES.size()) {
            throw new IllegalArgumentException(
                    "The ZIP does not contain optimized.pte, schedule_data.json, and tokenizer.json"
            );
        }
        return matches;
    }

    private static void extractRequiredEntries(
            FileChannel archive,
            Map<String, ArchiveEntry> entries,
            File stagingDirectory,
            ProgressListener progressListener
    ) throws Exception {
        long totalBytes = entries.values().stream().mapToLong(entry -> entry.size).sum();
        long completedBytes = 0;
        byte[] bytes = new byte[1024 * 1024];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        for (Map.Entry<String, String> requirement : REQUIRED_ENTRIES.entrySet()) {
            ArchiveEntry entry = entries.get(requirement.getKey());
            long dataOffset = findEntryDataOffset(archive, entry);
            archive.position(dataOffset);
            File outputFile = new File(stagingDirectory, requirement.getValue());
            CRC32 crc = new CRC32();
            long remaining = entry.size;
            long lastUpdateBytes = -1;

            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                while (remaining > 0) {
                    int requested = (int) Math.min(bytes.length, remaining);
                    buffer.clear();
                    buffer.limit(requested);
                    int read = archive.read(buffer);
                    if (read < 0) {
                        throw new IllegalArgumentException("The ZIP entry is incomplete: " + entry.name);
                    }
                    output.write(bytes, 0, read);
                    crc.update(bytes, 0, read);
                    remaining -= read;
                    long currentCompleted = completedBytes + entry.size - remaining;
                    if (lastUpdateBytes < 0
                            || currentCompleted - lastUpdateBytes >= 8L * 1024 * 1024) {
                        reportProgress(currentCompleted, totalBytes, progressListener);
                        lastUpdateBytes = currentCompleted;
                    }
                }
                output.getFD().sync();
            }

            if (crc.getValue() != entry.crc) {
                throw new IllegalArgumentException("The ZIP entry failed its checksum: " + entry.name);
            }
            completedBytes += entry.size;
            reportProgress(completedBytes, totalBytes, progressListener);
        }
    }

    private static long findEntryDataOffset(FileChannel archive, ArchiveEntry entry) throws Exception {
        ByteBuffer localHeader = readBuffer(archive, entry.localHeaderOffset, 30);
        if (localHeader.getInt(0) != LOCAL_FILE_HEADER_SIGNATURE) {
            throw new IllegalArgumentException("The ZIP local header is invalid: " + entry.name);
        }
        int nameLength = unsignedShort(localHeader, 26);
        int extraLength = unsignedShort(localHeader, 28);
        return entry.localHeaderOffset + 30L + nameLength + extraLength;
    }

    private static ByteBuffer readBuffer(FileChannel channel, long offset, int size) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(offset);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IllegalArgumentException("The ZIP archive ended unexpectedly");
            }
        }
        buffer.flip();
        return buffer;
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static long unsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static String matchingSuffix(String entryName) {
        String normalizedName = "/" + entryName;
        for (String suffix : REQUIRED_ENTRIES.keySet()) {
            if (normalizedName.endsWith(suffix)) {
                return suffix;
            }
        }
        return null;
    }

    private static void reportProgress(
            long completedBytes,
            long totalBytes,
            ProgressListener progressListener
    ) {
        int percent = totalBytes > 0
                ? (int) Math.min(100, Math.round(completedBytes * 100.0 / totalBytes))
                : 0;
        progressListener.onProgress(String.format(
                Locale.US,
                "Importing TinySD model… %d%% (%d MB of %d MB)",
                percent,
                completedBytes / (1024 * 1024),
                totalBytes / (1024 * 1024)
        ), percent);
    }

    private static void validateImportedFiles(File directory) {
        File model = new File(directory, "optimized.pte");
        File schedule = new File(directory, "schedule_data.json");
        File tokenizer = new File(directory, "tokenizer.json");
        if (model.length() < 100L * 1024 * 1024 || schedule.length() == 0 || tokenizer.length() == 0) {
            throw new IllegalArgumentException("One or more imported TinySD files are incomplete");
        }
    }

    private static void replaceModelDirectory(
            File modelDirectory,
            File stagingDirectory,
            File backupDirectory
    ) {
        if (modelDirectory.exists() && !modelDirectory.renameTo(backupDirectory)) {
            throw new IllegalStateException("Could not replace the existing model files");
        }
        if (!stagingDirectory.renameTo(modelDirectory)) {
            if (backupDirectory.exists()) {
                backupDirectory.renameTo(modelDirectory);
            }
            throw new IllegalStateException("Could not finish the model import");
        }
        deleteRecursively(backupDirectory);
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IllegalStateException("Could not remove temporary file: " + file.getName());
        }
    }

    private static final class ArchiveEntry {
        final String name;
        final long localHeaderOffset;
        final long size;
        final long crc;

        ArchiveEntry(String name, long localHeaderOffset, long size, long crc) {
            this.name = name;
            this.localHeaderOffset = localHeaderOffset;
            this.size = size;
            this.crc = crc;
        }
    }
}
