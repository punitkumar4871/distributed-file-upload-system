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

    // GitHub Contents API hard limit is ~25MB per request — use 20MB to be safe
    private static final long MAX_CHUNK_BYTES = 20 * 1024 * 1024; // 20MB

    private static final String CONTENTS_URL =
            "https://api.github.com/repos/%s/%s/contents/%s";

    public void pushFileToGitHub(String fileName) {

        System.out.println("\n[GitService] ====== GITHUB PUSH ======");
        System.out.println("[GitService] File      : " + fileName);
        System.out.println("[GitService] Token     : " + (githubToken != null && !githubToken.isEmpty()
                ? "YES (" + githubToken.substring(0, Math.min(8, githubToken.length())) + "...)"
                : "NO — set GITHUB_TOKEN env variable!"));
        System.out.println("[GitService] Repo      : " + repoOwner + "/" + repoName);

        if (githubToken == null || githubToken.isEmpty()) {
            System.err.println("[GitService] ERROR: GITHUB_TOKEN not set!");
            return;
        }

        try {
            Path filePath = Paths.get(finalDir, fileName);
            if (!Files.exists(filePath)) {
                System.err.println("[GitService] ERROR: File not found: " + filePath.toAbsolutePath());
                return;
            }

            long fileSize = Files.size(filePath);
            System.out.println("[GitService] Size      : " + formatSize(fileSize));

            String dateFolder = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

            if (fileSize <= MAX_CHUNK_BYTES) {
                pushSingleFile(filePath, fileName, dateFolder);
            } else {
                pushInParts(filePath, fileName, dateFolder);
            }

        } catch (Exception e) {
            System.err.println("[GitService] EXCEPTION: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[GitService] ==============================\n");
    }

    // ── SMALL FILE: push in one call ─────────────────────────────────────────

    private void pushSingleFile(Path filePath, String fileName, String dateFolder) throws Exception {
        String repoPath = "uploads/" + dateFolder + "/" + sanitizePath(fileName);
        byte[] bytes = Files.readAllBytes(filePath);
        String base64 = Base64.getEncoder().encodeToString(bytes);

        System.out.println("[GitService] Pushing to: " + repoPath);
        int status = putFile(repoPath, base64, "Upload: " + fileName);

        if (status == 201) {
            System.out.println("[GitService] SUCCESS: committed to storage repo!");
        } else if (status == 422) {
            System.out.println("[GitService] File exists — updating...");
            upsertFile(repoPath, base64, fileName);
        } else {
            System.err.println("[GitService] FAILED with HTTP " + status);
        }
    }

    // ── LARGE FILE: split into 20MB parts ────────────────────────────────────

    private void pushInParts(Path filePath, String fileName, String dateFolder) throws Exception {
        byte[] allBytes = Files.readAllBytes(filePath);
        int totalParts = (int) Math.ceil((double) allBytes.length / MAX_CHUNK_BYTES);

        System.out.println("[GitService] Splitting into " + totalParts + " x 20MB parts...");

        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String ext = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.')) : "";

        int success = 0;
        for (int i = 0; i < totalParts; i++) {
            int from = (int) (i * MAX_CHUNK_BYTES);
            int to   = (int) Math.min(from + MAX_CHUNK_BYTES, allBytes.length);
            byte[] chunk = new byte[to - from];
            System.arraycopy(allBytes, from, chunk, 0, chunk.length);

            String partName   = sanitizePath(baseName)
                    + ".part" + String.format("%03d", i + 1)
                    + "of" + totalParts + ext;
            String repoPath   = "uploads/" + dateFolder + "/" + partName;
            String base64     = Base64.getEncoder().encodeToString(chunk);
            String commitMsg  = "Upload part " + (i + 1) + "/" + totalParts + ": " + fileName;

            System.out.printf("[GitService] Part %d/%d (%s) → %s%n",
                    i + 1, totalParts, formatSize(chunk.length), partName);

            int status = putFile(repoPath, base64, commitMsg);

            if (status == 201) {
                System.out.println("[GitService]   committed OK");
                success++;
            } else if (status == 422) {
                // File already exists from a previous failed attempt — update it
                System.out.println("[GitService]   already exists — updating...");
                boolean updated = upsertFile(repoPath, base64, fileName);
                if (updated) success++;
            } else {
                System.err.println("[GitService]   FAILED HTTP " + status);
            }

            Thread.sleep(300); // small delay to avoid rate limiting
        }

        // Push manifest so user knows how to reassemble
        pushManifest(fileName, dateFolder, totalParts, allBytes.length);

        System.out.println("[GitService] Result: " + success + "/" + totalParts + " parts committed.");
        System.out.println("[GitService] Repo:   https://github.com/" + repoOwner + "/"
                + repoName + "/tree/main/uploads/" + dateFolder);
    }

    // ── MANIFEST ─────────────────────────────────────────────────────────────

    private void pushManifest(String fileName, String dateFolder, int totalParts, long totalBytes) {
        try {
            String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            String ext      = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
            String safe     = sanitizePath(baseName);

            String content =
                    "# Upload Manifest\n"
                    + "original_name : " + fileName + "\n"
                    + "total_size    : " + formatSize(totalBytes) + "\n"
                    + "total_parts   : " + totalParts + "\n"
                    + "chunk_size    : 20 MB\n"
                    + "date          : " + LocalDate.now() + "\n\n"
                    + "# Reassemble on Linux / Mac:\n"
                    + "# cat " + safe + ".part*" + ext + " > \"" + fileName + "\"\n\n"
                    + "# Reassemble on Windows PowerShell:\n"
                    + "# $out = [IO.File]::OpenWrite('" + fileName + "')\n"
                    + "# Get-ChildItem '" + safe + ".part*" + ext + "' | Sort Name | %{ $b=[IO.File]::ReadAllBytes($_); $out.Write($b,0,$b.Length) }\n"
                    + "# $out.Close()\n";

            String repoPath = "uploads/" + dateFolder + "/" + sanitizePath(fileName) + ".manifest.txt";
            String base64   = Base64.getEncoder().encodeToString(content.getBytes());

            int status = putFile(repoPath, base64, "Manifest: " + fileName);
            if (status == 201) {
                System.out.println("[GitService] Manifest committed.");
            } else if (status == 422) {
                upsertFile(repoPath, base64, fileName + ".manifest.txt");
            }
        } catch (Exception e) {
            System.err.println("[GitService] Manifest error: " + e.getMessage());
        }
    }

    // ── PUT FILE (create new) ─────────────────────────────────────────────────

    private int putFile(String repoPath, String base64Content, String message) throws Exception {
        String url  = buildUrl(repoPath);
        String body = "{\"message\":\"" + escapeJson(message) + "\","
                    + "\"content\":\"" + base64Content + "\"}";
        return httpPut(url, body);
    }

    // ── UPSERT FILE (create or update) ───────────────────────────────────────

    private boolean upsertFile(String repoPath, String base64Content, String fileName) throws Exception {
        String sha = getFileSha(repoPath);
        if (sha == null) {
            System.err.println("[GitService]   Could not get SHA — skipping update.");
            return false;
        }
        String url  = buildUrl(repoPath);
        String body = "{\"message\":\"Update: " + escapeJson(fileName) + "\","
                    + "\"content\":\"" + base64Content + "\","
                    + "\"sha\":\"" + sha + "\"}";
        int status = httpPut(url, body);
        if (status == 200 || status == 201) {
            System.out.println("[GitService]   updated OK");
            return true;
        }
        System.err.println("[GitService]   update failed HTTP " + status);
        return false;
    }

    // ── GET FILE SHA (needed for updates) ────────────────────────────────────

    private String getFileSha(String repoPath) throws Exception {
        String url = buildUrl(repoPath);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",        "Bearer " + githubToken)
                .header("Accept",               "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        return extractJsonValue(res.body(), "sha");
    }

    // ── HTTP PUT ──────────────────────────────────────────────────────────────

    private int httpPut(String url, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization",        "Bearer " + githubToken)
                .header("Accept",               "application/vnd.github+json")
                .header("Content-Type",         "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode();
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private String buildUrl(String repoPath) {
        String encoded = repoPath.replace(" ", "%20").replace("(", "%28").replace(")", "%29");
        return String.format(CONTENTS_URL, repoOwner, repoName, encoded);
    }

    private String sanitizePath(String name) {
        return name.replace(" ", "_").replace("(", "").replace(")", "");
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int start = idx + search.length();
        int end   = json.indexOf("\"", start);
        return end == -1 ? null : json.substring(start, end);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)             return bytes + " B";
        if (bytes < 1024 * 1024)      return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}