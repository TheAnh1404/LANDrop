package com.landrop.transfer;

import java.util.Objects;

/**
 * Immutable abstraction describing a specific chunk of a file in a chunked P2P file transfer.
 */
public record FileChunk(String transferId, int chunkIndex, long offset, int length) {

    public FileChunk {
        Objects.requireNonNull(transferId, "transferId cannot be null");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex cannot be negative: " + chunkIndex);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset cannot be negative: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("length cannot be negative: " + length);
        }
    }

    /**
     * Calculates the total number of chunks for a given file size and chunk size.
     * For empty files (fileSize == 0), returns 0.
     *
     * @param fileSize total file size in bytes (>= 0)
     * @param chunkSize chunk size in bytes (> 0)
     * @return total number of chunks
     */
    public static int calculateTotalChunks(long fileSize, int chunkSize) {
        if (fileSize <= 0) {
            return 0;
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        return (int) ((fileSize + chunkSize - 1) / chunkSize);
    }

    /**
     * Calculates the start byte offset of a chunk within the file.
     *
     * @param chunkIndex 0-based chunk index
     * @param chunkSize configured chunk size
     * @return byte offset
     */
    public static long calculateChunkOffset(int chunkIndex, int chunkSize) {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex cannot be negative: " + chunkIndex);
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        return (long) chunkIndex * chunkSize;
    }

    /**
     * Calculates the length of a specific chunk. The last chunk may be smaller than chunkSize.
     *
     * @param chunkIndex 0-based chunk index
     * @param totalChunks total number of chunks
     * @param chunkSize configured chunk size
     * @param fileSize total file size
     * @return chunk length in bytes
     */
    public static int calculateChunkLength(int chunkIndex, int totalChunks, int chunkSize, long fileSize) {
        if (fileSize <= 0 || totalChunks <= 0) {
            return 0;
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IndexOutOfBoundsException("chunkIndex " + chunkIndex + " out of bounds [0, " + totalChunks + ")");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }

        if (chunkIndex == totalChunks - 1) {
            long remaining = fileSize - ((long) chunkIndex * chunkSize);
            return (int) remaining;
        }
        return chunkSize;
    }

    /**
     * Factory creating a FileChunk descriptor for the given index.
     *
     * @param transferId transfer identifier
     * @param chunkIndex 0-based chunk index
     * @param totalChunks total chunks
     * @param chunkSize configured chunk size
     * @param fileSize total file size
     * @return new FileChunk instance
     */
    public static FileChunk of(String transferId, int chunkIndex, int totalChunks, int chunkSize, long fileSize) {
        long offset = calculateChunkOffset(chunkIndex, chunkSize);
        int length = calculateChunkLength(chunkIndex, totalChunks, chunkSize, fileSize);
        return new FileChunk(transferId, chunkIndex, offset, length);
    }
}
