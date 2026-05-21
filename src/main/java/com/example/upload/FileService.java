package com.example.upload;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FileService — all business logic for the chunked-upload system.
 *
 * After a successful merge it fires GitService.triggerFileStorageWorkflow()
 * so the assembled file is automatically committed to the private
 * GitHub storage repository via a repository_dispatch event.
 */
@Service
public class FileService {

    @Value("${upload.temp-dir:uploads/temp}")
    private String tempDir;

    @Value("${upload.final-dir:uploads/final}")
    private String finalDir;

    @Autowired
    private GitService gitService;

    // ── 1. SAVE CHUNK ────────────────────────────────────────────────────────

    public void saveChunk(String fileId, int chunkIndex, MultipartFile chunkData) throws IOException {
        Path chunkDir = Paths.get(tempDir, fileId);
        Files.createDirectories(chunkDir);
        Path chunkFile = chunkDir.resolve("chunk_" + chunkIndex);
        Files.write(chunkFile, chunkData.getBytes());
        System.out.printf("[FileService] Saved chunk %d for fileId=%s%n", chunkIndex, fileId);
    }

    // ── 2. MERGE CHUNKS ──────────────────────────────────────────────────────

    public String mergeChunks(String fileId, String fileName) throws IOException {
        Path chunkDir = Paths.get(tempDir, fileId);

        if (!Files.exists(chunkDir)) {
            throw new IOException("No chunks found for fileId: " + fileId);
        }

        List<Path> chunks = Files.list(chunkDir)
                .filter(p -> p.getFileName().toString().startsWith("chunk_"))
                .sorted(Comparator.comparingInt(FileService::extractChunkIndex))
                .collect(Collectors.toList());

        if (chunks.isEmpty()) {
            throw new IOException("Chunk directory is empty for fileId: " + fileId);
        }

        Path outputDir = Paths.get(finalDir);
        Files.createDirectories(outputDir);
        Path outputFile = outputDir.resolve(fileName);

        try (OutputStream out = new BufferedOutputStream(
                new FileOutputStream(outputFile.toFile(), false))) {
            for (Path chunk : chunks) {
                Files.copy(chunk, out);
            }
        }

        System.out.printf("[FileService] Merged %d chunks → %s%n", chunks.size(), outputFile);

        deleteTemp(fileId);

        // ── Trigger GitHub file-storage workflow ──────────────────────────
        gitService.pushFileToGitHub(fileName);


        return outputFile.toAbsolutePath().toString();
    }

    // ── 3. GET FILE PATH ─────────────────────────────────────────────────────

    public Path getFilePath(String fileName) throws FileNotFoundException {
        Path file = Paths.get(finalDir, fileName);
        if (!Files.exists(file)) {
            throw new FileNotFoundException("File not found: " + fileName);
        }
        return file;
    }

    // ── 4. DELETE TEMP DIR ───────────────────────────────────────────────────

    public void deleteTemp(String fileId) throws IOException {
        Path chunkDir = Paths.get(tempDir, fileId);
        if (Files.exists(chunkDir)) {
            Files.walk(chunkDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            System.out.printf("[FileService] Deleted temp dir for fileId=%s%n", fileId);
        }
    }

    // ── HELPER ───────────────────────────────────────────────────────────────

    private static int extractChunkIndex(Path chunkPath) {
        return Integer.parseInt(chunkPath.getFileName().toString().replace("chunk_", ""));
    }
}
