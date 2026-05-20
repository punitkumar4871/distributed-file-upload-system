package com.example.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FileService contains ALL business logic for the upload system:
 *
 *  1. saveChunk()   — persist a single chunk to disk inside /temp/<fileId>/
 *  2. mergeChunks() — concatenate ordered chunks into /uploads/<fileName>
 *  3. getFilePath() — resolve the path of a merged file for download
 *  4. deleteTemp()  — clean up temp chunk directory after merge
 */
@Service
public class FileService {

    // Configurable via application.properties; defaults shown below
    @Value("${upload.temp-dir:uploads/temp}")
    private String tempDir;

    @Value("${upload.final-dir:uploads/final}")
    private String finalDir;

    // ─────────────────────────────────────────────────────────────────────────
    // 1. SAVE CHUNK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Saves a single incoming chunk to:
     *   <tempDir>/<fileId>/chunk_<chunkIndex>
     *
     * Creates the directory if it doesn't exist yet.
     */
    public void saveChunk(String fileId, int chunkIndex, MultipartFile chunkData) throws IOException {

        // Build path:  uploads/temp/abc123/
        Path chunkDir = Paths.get(tempDir, fileId);
        Files.createDirectories(chunkDir);   // no-op if already exists

        // Build chunk file name:  chunk_0, chunk_1, chunk_2 …
        Path chunkFile = chunkDir.resolve("chunk_" + chunkIndex);

        // Write raw bytes to disk
        Files.write(chunkFile, chunkData.getBytes());

        System.out.printf("[FileService] Saved chunk %d for fileId=%s%n", chunkIndex, fileId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. MERGE CHUNKS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Merges all chunks for the given fileId into a single output file.
     *
     * Chunk ordering is critical — files are concatenated in ascending index
     * order (chunk_0 → chunk_1 → … → chunk_N).
     *
     * Output path:  <finalDir>/<fileName>
     *
     * @return absolute path of the merged file
     */
    public String mergeChunks(String fileId, String fileName) throws IOException {

        Path chunkDir = Paths.get(tempDir, fileId);

        // Validate temp directory exists
        if (!Files.exists(chunkDir)) {
            throw new IOException("No chunks found for fileId: " + fileId);
        }

        // Collect & sort chunk files by their numeric suffix
        List<Path> chunks = Files.list(chunkDir)
                .filter(p -> p.getFileName().toString().startsWith("chunk_"))
                .sorted(Comparator.comparingInt(FileService::extractChunkIndex))
                .collect(Collectors.toList());

        if (chunks.isEmpty()) {
            throw new IOException("Chunk directory is empty for fileId: " + fileId);
        }

        // Ensure output directory exists
        Path outputDir = Paths.get(finalDir);
        Files.createDirectories(outputDir);

        // Merge: stream each chunk's bytes sequentially into the output file
        Path outputFile = outputDir.resolve(fileName);

        try (OutputStream out = new BufferedOutputStream(
                new FileOutputStream(outputFile.toFile(), false))) {  // false = overwrite

            for (Path chunk : chunks) {
                Files.copy(chunk, out);   // appends chunk bytes to output stream
            }
        }

        System.out.printf("[FileService] Merged %d chunks → %s%n", chunks.size(), outputFile);

        // Clean up temp chunks
        deleteTemp(fileId);

        return outputFile.toAbsolutePath().toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. GET FILE PATH (for download)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the Path of a merged file.
     * Throws FileNotFoundException if file doesn't exist.
     */
    public Path getFilePath(String fileName) throws FileNotFoundException {
        Path file = Paths.get(finalDir, fileName);
        if (!Files.exists(file)) {
            throw new FileNotFoundException("File not found: " + fileName);
        }
        return file;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. DELETE TEMP DIRECTORY
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recursively deletes the temp chunk directory for a given fileId.
     * Called automatically after a successful merge.
     */
    public void deleteTemp(String fileId) throws IOException {
        Path chunkDir = Paths.get(tempDir, fileId);

        if (Files.exists(chunkDir)) {
            // Walk tree bottom-up, delete files then directories
            Files.walk(chunkDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);

            System.out.printf("[FileService] Deleted temp dir for fileId=%s%n", fileId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the integer index from a chunk filename like "chunk_7" → 7.
     * Used for sorting chunks in the correct order before merge.
     */
    private static int extractChunkIndex(Path chunkPath) {
        String name = chunkPath.getFileName().toString(); // e.g. "chunk_7"
        return Integer.parseInt(name.replace("chunk_", ""));
    }
}