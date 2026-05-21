package com.example.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * GitService — pushes uploaded files DIRECTLY to GitHub via Contents API.
 * No public URL needed. Works on any network, any IP.
 *
 * Flow:
 *   File merged on disk
 *        ↓
 *   Read file bytes
 *        ↓
 *   Base64 encode
 *        ↓
 *   PUT https://api.github.com/repos/{owner}/{repo}/contents/{path}
 *        ↓
 *   File committed directly to private storage repo ✓
 */
@Service
public class GitService {

    @Value("${github.token:#{null}}")
    private String githubToken;

    @Value("${github.storage.repo.owner:#{null}}")
    private String repoOwner;

    @Value("${github.storage.repo.name:#{null}}")
    private String repoName;

    @Value("${upload.final-dir:uploads/final}")
    private String finalDir;

    // GitHub has a 50MB limit per API call — we split anything larger into chunks
    private static final long MAX_CHUNK_BYTES = 45 * 1024 * 1024; // 45MB to be safe

    private static final String CONTENTS_API =
            "https://api.github.com/repos/%s/%s/contents/%s";

    /**
     * Called by FileService after merge is complete.
     * Reads the file from disk and pushes it directly to GitHub.
     */
    public void pushFileToGitHub(String fileName) {

        System.out.println("\n[GitService] ====== GITHUB PUSH ======");
        System.out.println("[GitService] File        : " + fileName);
        System.out.println("[GitService] Token set   : " + (githubToken != null && !githubToken.isEmpty()
                ? "YES (" + githubToken.substring(0, Math.min(8, githubToken.length())) + "...)"
                : "NO — set GITHUB_TOKEN env variable!"));
        System.out.println("[GitService] Repo        : " + repoOwner + "/" + repoName);

        if (githubToken == null || githubToken.isEmpty()) {
            System.err.println("[GitService] ERROR: GITHUB_TOKEN not set.");
            System.err.println("[GitService] Run in PowerShell: $env:GITHUB_TOKEN='ghp_yourtoken'");
            return;
        }

        try {
            Path filePath = Paths.get(finalDir, fileName);
            if (!Files.exists(filePath)) {
                System.err.println("[GitService] ERROR: File not found at: " + filePath.toAbsolutePath());
                return;
            }

            long fileSize = Files.size(filePath);
            System.out.println("[GitService] File size   : " + formatSize(fileSize));

            // Date-based folder: uploads/2026/05/21/
            String dateFolder = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

            if (fileSize <= MAX_CHUNK_BYTES) {
                // Small file — push directly in one API call
                pushSingleFile(filePath, fileName, dateFolder);
            } else {
                // Large file — split into 45MB parts and push each
                System.out.println("[GitService] File is large — splitting into 45MB parts...");
                pushLargeFileInParts(filePath, fileName, dateFolder);
            }

        } catch (Exception e) {
            System.err.println("[GitService] EXCEPTION: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[GitService] ==============================\n");
    }

    // ── PUSH SINGLE FILE ─────────────────────────────────────────────────────

    private void pushSingleFile(Path filePath, String fileName, String dateFolder) throws Exception {
        byte[] fileBytes = Files.readAllBytes(filePath);
        String base64Content = Base64.getEncoder().encodeToString(fileBytes);
        String repoPath = "uploads/" + dateFolder + "/" + fileName;

        System.out.println("[GitService] Pushing to  : " + repoPath);

        int status = callGitHubContentsAPI(repoPath, base64Content,
                "Upload: " + fileName + " [" + LocalDate.now() + "]");

        if (status == 201) {
            System.out.println("[GitService] SUCCESS: File committed to storage repo!");
            System.out.println("[GitService] View at: https://github.com/" + repoOwner + "/" + repoName
                    + "/blob/main/" + repoPath);
        } else if (status == 422) {
            System.out.println("[GitService] File already exists — updating...");
            updateExistingFile(repoPath, base64Content, fileName);
        } else {
            System.err.println("[GitService] ERROR: HTTP " + status);
        }
    }

    // ── PUSH LARGE FILE IN PARTS ─────────────────────────────────────────────

    private void pushLargeFileInParts(Path filePath, String fileName, String dateFolder) throws Exception {
        byte[] allBytes = Files.readAllBytes(filePath);
        int totalParts = (int) Math.ceil((double) allBytes.length / MAX_CHUNK_BYTES);

        System.out.println("[GitService] Splitting into " + totalParts + " parts...");

        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        String extension = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.'))
                : "";

        int successCount = 0;
        for (int i = 0; i < totalParts; i++) {
            int start = (int) (i * MAX_CHUNK_BYTES);
            int end = (int) Math.min(start + MAX_CHUNK_BYTES, allBytes.length);
            byte[] chunk = new byte[end - start];
            System.arraycopy(allBytes, start, chunk, 0, chunk.length);

            String partName = baseName + ".part" + String.format("%03d", i + 1) + "of" + totalParts + extension;
            String repoPath = "uploads/" + dateFolder + "/" + partName;
            String base64Content = Base64.getEncoder().encodeToString(chunk);

            System.out.println("[GitService] Pushing part " + (i + 1) + "/" + totalParts
                    + " (" + formatSize(chunk.length) + ") → " + partName);

            int status = callGitHubContentsAPI(repoPath, base64Content,
                    "Upload part " + (i + 1) + "/" + totalParts + ": " + fileName);

            if (status == 201 || status == 200) {
                successCount++;
                System.out.println("[GitService] Part " + (i + 1) + " committed OK");
            } else {
                System.err.println("[GitService] Part " + (i + 1) + " failed with HTTP " + status);
            }

            // Small delay between API calls to avoid rate limiting
            Thread.sleep(500);
        }

        // Push a manifest file so user knows how to reassemble
        pushManifest(fileName, dateFolder, totalParts, allBytes.length);

        System.out.println("[GitService] Done: " + successCount + "/" + totalParts + " parts committed.");
        System.out.println("[GitService] View at: https://github.com/" + repoOwner + "/" + repoName
                + "/tree/main/uploads/" + dateFolder);
    }

    // ── PUSH MANIFEST FILE ───────────────────────────────────────────────────

    private void pushManifest(String fileName, String dateFolder, int totalParts, long totalBytes) {
        try {
            String baseName = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            String extension = fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.'))
                    : "";

            String manifestContent = "# File Manifest\n"
                    + "original_name: " + fileName + "\n"
                    + "total_size: " + formatSize(totalBytes) + "\n"
                    + "total_parts: " + totalParts + "\n"
                    + "date: " + LocalDate.now() + "\n\n"
                    + "# To reassemble (Linux/Mac):\n"
                    + "# cat " + baseName + ".part*" + extension + " > " + fileName + "\n\n"
                    + "# To reassemble (Windows PowerShell):\n"
                    + "# $files = Get-ChildItem '" + baseName + ".part*" + extension + "' | Sort-Object Name\n"
                    + "# $out = [System.IO.File]::OpenWrite('" + fileName + "')\n"
                    + "# foreach ($f in $files) { $bytes = [System.IO.File]::ReadAllBytes($f); $out.Write($bytes, 0, $bytes.Length) }\n"
                    + "# $out.Close()\n";

            String repoPath = "uploads/" + dateFolder + "/" + fileName + ".manifest.txt";
            String base64 = Base64.getEncoder().encodeToString(manifestContent.getBytes());
            callGitHubContentsAPI(repoPath, base64, "Manifest for: " + fileName);
            System.out.println("[GitService] Manifest committed: " + repoPath);
        } catch (Exception e) {
            System.err.println("[GitService] Could not push manifest: " + e.getMessage());
        }
    }

    // ── GITHUB CONTENTS API CALL ─────────────────────────────────────────────

    private int callGitHubContentsAPI(String repoPath, String base64Content, String commitMessage) throws Exception {
        String url = String.format(CONTENTS_API, repoOwner, repoName,
                repoPath.replace(" ", "%20"));

        String body = String.format(
                "{\"message\":\"%s\",\"content\":\"%s\"}",
                commitMessage.replace("\"", "\\\""),
                base64Content
        );

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",        "Bearer " + githubToken)
                .header("Accept",               "application/vnd.github+json")
                .header("Content-Type",         "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201 && response.statusCode() != 200 && response.statusCode() != 422) {
            System.err.println("[GitService] API Error body: " + response.body());
        }

        return response.statusCode();
    }

    // ── UPDATE EXISTING FILE ─────────────────────────────────────────────────

    private void updateExistingFile(String repoPath, String base64Content, String fileName) throws Exception {
        // Get current SHA first (required to update existing file)
        String url = String.format(CONTENTS_API, repoOwner, repoName,
                repoPath.replace(" ", "%20"));

        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest getReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",        "Bearer " + githubToken)
                .header("Accept",               "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET().build();

        HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
        String sha = extractSha(getResp.body());

        if (sha == null) {
            System.err.println("[GitService] Could not get file SHA for update.");
            return;
        }

        String body = String.format(
                "{\"message\":\"Update: %s\",\"content\":\"%s\",\"sha\":\"%s\"}",
                fileName.replace("\"", "\\\""), base64Content, sha
        );

        HttpRequest putReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",        "Bearer " + githubToken)
                .header("Accept",               "application/vnd.github+json")
                .header("Content-Type",         "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> putResp = client.send(putReq, HttpResponse.BodyHandlers.ofString());
        if (putResp.statusCode() == 200 || putResp.statusCode() == 201) {
            System.out.println("[GitService] File updated successfully.");
        }
    }

    // ── HELPERS ──────────────────────────────────────────────────────────────

    private String extractSha(String json) {
        int idx = json.indexOf("\"sha\":");
        if (idx == -1) return null;
        int start = json.indexOf("\"", idx + 6) + 1;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}