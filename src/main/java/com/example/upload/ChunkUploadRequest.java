package com.example.upload;

/**
 * Holds metadata about an incoming chunk upload request.
 * The actual binary data arrives as a MultipartFile in the controller.
 */
public class ChunkUploadRequest {

    private String fileId;       // Unique ID for the full file (e.g., "abc123")
    private int chunkIndex;      // Which chunk this is (0-based)
    private int totalChunks;     // Total number of chunks for this file
    private String fileName;     // Original filename (e.g., "movie.mp4")

    // ── Constructors ──────────────────────────────────────────────────────────

    public ChunkUploadRequest() {}

    public ChunkUploadRequest(String fileId, int chunkIndex, int totalChunks, String fileName) {
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.fileName = fileName;
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
}