# Distributed Upload System — DevOps Setup

Spring Boot chunked file upload system with full DevOps pipeline:
**Maven → Docker → GitHub Actions → Kubernetes**

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                  TWO SEPARATE PIPELINES                 │
└─────────────────────────────────────────────────────────┘

PIPELINE 1 — Code Deployment (CI/CD)
─────────────────────────────────────
Developer pushes code
       ↓
GitHub Actions (ci-cd.yml)
       ↓
mvn test  →  mvn package
       ↓
Docker build & push to Docker Hub
       ↓
kubectl apply → Kubernetes rolling deploy


PIPELINE 2 — File Storage (file-storage.yml)
─────────────────────────────────────────────
User uploads file via browser
       ↓
Spring Boot receives chunks → saves to /uploads/temp/<fileId>/
       ↓
All chunks received → merge into /uploads/final/<fileName>
       ↓
GitService sends repository_dispatch to GitHub API
       ↓
file-storage.yml workflow triggers
       ↓
Workflow clones private storage repo
       ↓
git add / commit / push  →  file saved in storage repo
```

---

## Project Structure

```
.
├── .github/
│   └── workflows/
│       ├── ci-cd.yml           ← Build, test, Docker, K8s deploy
│       └── file-storage.yml    ← Auto-commit uploaded files to GitHub
├── k8s/
│   ├── namespace.yml
│   ├── configmap.yml
│   ├── deployment.yml          ← 2 replicas, RollingUpdate, HPA
│   ├── service.yml             ← LoadBalancer on port 80
│   ├── hpa.yml                 ← Auto-scale 2–6 pods on CPU/memory
│   └── secret.yml.template     ← Fill and apply manually; never commit
├── src/
│   └── main/java/com/example/upload/
│       ├── UploadSystemApplication.java
│       ├── UploadController.java   ← REST endpoints
│       ├── FileService.java        ← Chunk save + merge + triggers Git
│       ├── GitService.java         ← Fires repository_dispatch to GitHub
│       ├── ChunkUploadRequest.java
│       ├── ApiResponse.java
│       └── WebConfig.java
├── Dockerfile                  ← Multi-stage: Maven builder + JRE runtime
├── pom.xml
└── README.md
```

---

## Quick Start (Local)

```bash
# 1. Build
mvn clean package -DskipTests

# 2. Run with Docker
docker build -t upload-system .
docker run -p 8080:8080 \
  -e GITHUB_TOKEN=ghp_yourtoken \
  -e GITHUB_STORAGE_REPO_OWNER=your-username \
  -e GITHUB_STORAGE_REPO_NAME=your-storage-repo \
  upload-system

# 3. Open browser
open http://localhost:8080
```

---

## GitHub Actions Setup

### Secrets to add in your repo (Settings → Secrets → Actions)

| Secret | Description |
|---|---|
| `DOCKERHUB_USERNAME` | Your Docker Hub username |
| `DOCKERHUB_TOKEN` | Docker Hub access token |
| `KUBECONFIG` | Base64-encoded kubeconfig file |
| `STORAGE_REPO_TOKEN` | PAT with `repo` scope for storage repo |
| `STORAGE_REPO_OWNER` | GitHub username/org owning storage repo |
| `STORAGE_REPO_NAME` | Name of the private storage repository |

### Encode your kubeconfig

```bash
cat ~/.kube/config | base64 | tr -d '\n'
# Paste output as KUBECONFIG secret
```

---

## Kubernetes Deployment

```bash
# 1. Create secrets (fill in secret.yml.template first)
cp k8s/secret.yml.template k8s/secret.yml
# Edit k8s/secret.yml with base64-encoded values
kubectl apply -f k8s/secret.yml

# 2. Deploy everything
kubectl apply -f k8s/namespace.yml
kubectl apply -f k8s/configmap.yml
kubectl apply -f k8s/deployment.yml
kubectl apply -f k8s/service.yml
kubectl apply -f k8s/hpa.yml

# 3. Watch rollout
kubectl rollout status deployment/upload-system -n upload-system

# 4. Get external IP
kubectl get svc -n upload-system
```

### Important: Shared Storage for Multi-Replica Deployments

With 2+ replicas, a chunk uploaded to Pod A must be readable by Pod B for the merge.
The `deployment.yml` uses a `PersistentVolumeClaim` with `ReadWriteMany`.

**Local (Minikube/Kind):** use NFS or Longhorn
**Cloud (EKS/GKE/AKS):** use EFS (AWS) / Filestore (GCP) / Azure Files

---

## File Storage to GitHub — How It Works

1. User finishes uploading → Spring Boot merges all chunks
2. `FileService` calls `GitService.triggerFileStorageWorkflow(fileName)`
3. `GitService` POSTs a `repository_dispatch` event to GitHub API
4. `file-storage.yml` workflow wakes up, clones your private storage repo
5. File is copied and committed with a timestamp message
6. Files are organized by date: `uploads/2026/05/21/filename.mp4`

### Environment variables for GitService

```bash
GITHUB_TOKEN=ghp_your_token_with_repo_scope
GITHUB_STORAGE_REPO_OWNER=your-github-username
GITHUB_STORAGE_REPO_NAME=your-private-storage-repo
```

---

## REST API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/upload/chunk` | Upload a single chunk |
| `POST` | `/api/upload/merge/{fileId}` | Merge all chunks into final file |
| `GET` | `/api/download/{fileName}` | Download merged file |
| `GET` | `/api/health` | Health check (used by K8s probes) |
