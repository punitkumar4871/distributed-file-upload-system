package com.example.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * GitService — called after a file is fully merged.
 *
 * It fires a GitHub repository_dispatch event so the
 * "file-storage.yml" workflow picks up the file and
 * commits it to the private storage repository.
 *
 * Required environment variables / application.properties:
 *   github.token                — Personal Access Token with repo scope
 *   github.storage.repo.owner   — e.g. "your-username"
 *   github.storage.repo.name    — e.g. "my-private-storage"
 */
@Service
public class GitService {

    @Value("${github.token:#{null}}")
    private String githubToken;

    @Value("${github.storage.repo.owner:#{null}}")
    private String repoOwner;

    @Value("${github.storage.repo.name:#{null}}")
    private String repoName;

    private static final String DISPATCH_URL =
            "https://api.github.com/repos/%s/%s/dispatches";

    /**
     * Sends a repository_dispatch event to GitHub, which triggers
     * the file-storage.yml workflow to clone the storage repo and
     * commit the uploaded file.
     *
     * @param fileName the merged file's name (e.g. "video.mp4")
     */
    public void triggerFileStorageWorkflow(String fileName) {

        if (githubToken == null || repoOwner == null || repoName == null) {
            System.out.println("[GitService] GitHub config not set — skipping dispatch for: " + fileName);
            return;
        }

        String url = String.format(DISPATCH_URL, repoOwner, repoName);

        // Build the JSON payload
        String payload = String.format("""
                {
                  "event_type": "file-uploaded",
                  "client_payload": {
                    "fileName": "%s"
                  }
                }
                """, fileName.replace("\"", "\\\""));

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization",   "Bearer " + githubToken)
                    .header("Accept",          "application/vnd.github+json")
                    .header("Content-Type",    "application/json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.printf("[GitService] ✓ Dispatch sent for file: %s%n", fileName);
            } else {
                System.err.printf("[GitService] ✗ Dispatch failed [HTTP %d]: %s%n",
                        response.statusCode(), response.body());
            }

        } catch (Exception e) {
            // Non-fatal: file is already merged on disk. Log and continue.
            System.err.printf("[GitService] ✗ Error sending dispatch for %s: %s%n",
                    fileName, e.getMessage());
        }
    }
}
