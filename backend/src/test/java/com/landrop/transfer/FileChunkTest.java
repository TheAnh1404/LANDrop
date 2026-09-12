package com.landrop.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileChunkTest {

    @Test
    @DisplayName("Chunk count calculation: normal and edge cases")
    void testCalculateTotalChunks() {
        int chunkSize = 1024 * 1024; // 1 MB

        // Empty file
        assertEquals(0, FileChunk.calculateTotalChunks(0, chunkSize));
        assertEquals(0, FileChunk.calculateTotalChunks(-10, chunkSize));

        // Exact multiple
        assertEquals(1, FileChunk.calculateTotalChunks(1024 * 1024, chunkSize));
        assertEquals(5, FileChunk.calculateTotalChunks(5 * 1024 * 1024, chunkSize));

        // Not exact multiple (ceil)
        assertEquals(1, FileChunk.calculateTotalChunks(100, chunkSize));
        assertEquals(2, FileChunk.calculateTotalChunks(1024 * 1024 + 1, chunkSize));
        assertEquals(3, FileChunk.calculateTotalChunks(2 * 1024 * 1024 + 500, chunkSize));

        // Non-positive chunkSize throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> FileChunk.calculateTotalChunks(100, 0));
        assertThrows(IllegalArgumentException.class, () -> FileChunk.calculateTotalChunks(100, -1));
    }

    @Test
    @DisplayName("Chunk offset calculation: 0-based indexing")
    void testCalculateChunkOffset() {
        int chunkSize = 1024 * 1024; // 1 MB

        assertEquals(0L, FileChunk.calculateChunkOffset(0, chunkSize));
        assertEquals(1024 * 1024L, FileChunk.calculateChunkOffset(1, chunkSize));
        assertEquals(2 * 1024 * 1024L, FileChunk.calculateChunkOffset(2, chunkSize));
        assertEquals(9 * 1024 * 1024L, FileChunk.calculateChunkOffset(9, chunkSize));

        assertThrows(IllegalArgumentException.class, () -> FileChunk.calculateChunkOffset(-1, chunkSize));
        assertThrows(IllegalArgumentException.class, () -> FileChunk.calculateChunkOffset(0, 0));
    }

    @Test
    @DisplayName("Chunk length calculation: regular chunks vs last smaller chunk")
    void testCalculateChunkLength() {
        int chunkSize = 1024 * 1024; // 1 MB
        long fileSize = (long) (2.5 * 1024 * 1024); // 2.5 MB -> 3 chunks
        int totalChunks = FileChunk.calculateTotalChunks(fileSize, chunkSize);
        assertEquals(3, totalChunks);

        // Chunk 0: full 1 MB
        assertEquals(chunkSize, FileChunk.calculateChunkLength(0, totalChunks, chunkSize, fileSize));
        // Chunk 1: full 1 MB
        assertEquals(chunkSize, FileChunk.calculateChunkLength(1, totalChunks, chunkSize, fileSize));
        // Chunk 2 (last): remaining 0.5 MB (524,288 bytes)
        assertEquals(512 * 1024, FileChunk.calculateChunkLength(2, totalChunks, chunkSize, fileSize));

        // Empty file length is 0
        assertEquals(0, FileChunk.calculateChunkLength(0, 0, chunkSize, 0));

        // Index out of bounds
        assertThrows(IndexOutOfBoundsException.class, () ->
                FileChunk.calculateChunkLength(3, totalChunks, chunkSize, fileSize));
        assertThrows(IndexOutOfBoundsException.class, () ->
                FileChunk.calculateChunkLength(-1, totalChunks, chunkSize, fileSize));
    }

    @Test
    @DisplayName("FileChunk.of factory builds correct offset and length")
    void testFileChunkOf() {
        String transferId = "uuid-123";
        int chunkSize = 64 * 1024;
        long fileSize = 150 * 1024; // 150 KB -> 3 chunks (64 KB, 64 KB, 22 KB)
        int totalChunks = FileChunk.calculateTotalChunks(fileSize, chunkSize);

        FileChunk chunk0 = FileChunk.of(transferId, 0, totalChunks, chunkSize, fileSize);
        assertEquals(0, chunk0.chunkIndex());
        assertEquals(0L, chunk0.offset());
        assertEquals(64 * 1024, chunk0.length());

        FileChunk chunk1 = FileChunk.of(transferId, 1, totalChunks, chunkSize, fileSize);
        assertEquals(1, chunk1.chunkIndex());
        assertEquals(64 * 1024L, chunk1.offset());
        assertEquals(64 * 1024, chunk1.length());

        FileChunk chunk2 = FileChunk.of(transferId, 2, totalChunks, chunkSize, fileSize);
        assertEquals(2, chunk2.chunkIndex());
        assertEquals(128 * 1024L, chunk2.offset());
        assertEquals(22 * 1024, chunk2.length());
    }
}
