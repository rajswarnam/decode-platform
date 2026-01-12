# Kubernetes Deployment Strategy

Deploying to Kubernetes (AKS/EKS) fits perfectly with a Monorepo strategy. Instead of using `docker-compose.yaml` for orchestration, you use **Helm Charts** or **Manifests** stored right here in the repo.

## 📂 Repository Structure Update

We typically add a `k8s/` or `charts/` directory at the root.

```text
decode-workspace/
├── context-orchestrator/   # (Java Code)
├── web-frontend/           # (React Code)
├── infra/                  # (Local Dev setups)
├── docker-compose.yaml     # (Local Dev ONLY)
└── k8s/                    # (Production Infrastructure)
    ├── context-orchestrator/
    │   ├── deployment.yaml
    │   ├── service.yaml
    │   └── configmap.yaml
    ├── web-frontend/
    └── infra/
        ├── postgres-statefulset.yaml
        └── minio-statefulset.yaml
```

## 🔄 CI/CD Workflow (The "Monorepo Flow")

In a Kubernetes environment, your Git Monorepo drives the cluster state:

1.  **Change Detection:** You modify `web-frontend/src/App.tsx`.
2.  **CI Pipeline:** Git detects change in `web-frontend/`.
3.  **Build:** CI builds ONLY the frontend docker image: `myregistry.azurecr.io/decode-frontend:v2`.
4.  **Deploy:** CI runs `kubectl apply -f k8s/web-frontend/deployment.yaml` (changing the image tag).

## ⚡ Key Differences from Docker Compose

| Feature | Docker Compose (Local) | Kubernetes (Production) | Impact on Code |
| :--- | :--- | :--- | :--- |
| **Networking** | `http://context-orchestrator:8080` | `http://context-orchestrator.default.svc.cluster.local` | **None** (Use ENV vars for hostnames) |
| **Config** | `environment:` section | `ConfigMap` & `Secret` objects | **None** (Spring Boot reads env vars identically) |
| **Storage** | Local Volumes (`./data`) | `PersistentVolumeClaims` (PVC) | **None** (Apps just see a mounted folder) |

## ✅ Recommendation

**Keep the Monorepo.** It creates a "Single Pane of Glass" for both your application logic and the infrastructure required to run it.
