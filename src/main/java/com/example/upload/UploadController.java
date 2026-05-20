package com.example.upload;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.Map;

/**
 * UploadController exposes three REST endpoints:
 *
 *  POST /api/upload/chunk          → accept and store one chunk
 *  POST /api/upload/merge/{fileId} → merge all chunks into final file
 *  GET  /api/download/{fileName}   → stream the merged file to client
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")   // Allow all origins so the HTML frontend can call the API
public class UploadController {

    @Autowired
    private FileService fileService;

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 1 — Upload a single chunk
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Accepts a multipart request containing one chunk and its metadata.
     *
     * Request params (form-data):
     *   fileId      — unique identifier for this file upload session
     *   chunkIndex  — zero-based index of this chunk
     *   totalChunks — how many chunks the file was split into
     *   fileName    — original filename (stored for the merge step)
     *   chunkData   — the binary chunk data
     *
     * Returns 200 OK with success message, or 500 on error.
     */
    @PostMapping("/upload/chunk")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadChunk(
            @RequestParam("fileId")      String fileId,
            @RequestParam("chunkIndex")  int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("fileName")    String fileName,
            @RequestParam("chunkData")   MultipartFile chunkData) {

        try {
            // Delegate actual storage to the service layer
            fileService.saveChunk(fileId, chunkIndex, chunkData);

            // Return progress info so the frontend can update its progress bar
            Map<String, Object> responseData = Map.of(
                    "fileId",       fileId,
                    "chunkIndex",   chunkIndex,
                    "totalChunks",  totalChunks,
                    "chunkSize",    chunkData.getSize()
            );

            return ResponseEntity.ok(
                    ApiResponse.ok("Chunk " + chunkIndex + " uploaded successfully", responseData)
            );

        } catch (Exception e) {
            System.err.println("[UploadController] Error saving chunk: " + e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to upload chunk: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 2 — Merge all chunks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Triggers the merge of all stored chunks for the given fileId.
     *
     * Path variable: fileId  — same fileId used during chunk uploads
     * Request param: fileName — original filename (used as output filename)
     *
     * After merge, the temp directory is automatically cleaned up.
     */
    @PostMapping("/upload/merge/{fileId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> mergeChunks(
            @PathVariable("fileId") String fileId,
            @RequestParam("fileName") String fileName) {

        try {
            String mergedFilePath = fileService.mergeChunks(fileId, fileName);

            Map<String, String> responseData = Map.of(
                    "fileId",    fileId,
                    "fileName",  fileName,
                    "filePath",  mergedFilePath,
                    "downloadUrl", "/api/download/" + fileName
            );

            return ResponseEntity.ok(
                    ApiResponse.ok("File merged successfully: " + fileName, responseData)
            );

        } catch (Exception e) {
            System.err.println("[UploadController] Error merging chunks: " + e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to merge chunks: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT 3 — Download the merged file
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Streams the final merged file to the client.
     *
     * Uses Spring's Resource abstraction to stream the file efficiently —
     * no loading entire file into memory.
     *
     * Path variable: fileName — the original filename (e.g., "movie.mp4")
     */
    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> downloadFile(@PathVariable("fileName") String fileName) {

        try {
            Path filePath = fileService.getFilePath(fileName);
            Resource resource = new PathResource(filePath);

            // Let the browser decide how to handle it (download or preview)
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(filePath.toFile().length())
                    .body(resource);

        } catch (FileNotFoundException e) {
            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            System.err.println("[UploadController] Error downloading file: " + e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HEALTH CHECK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Simple health check — GET /api/health
     * Useful for Docker health checks and Kubernetes liveness probes.
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.ok("Upload System is running", "OK"));
    }
}