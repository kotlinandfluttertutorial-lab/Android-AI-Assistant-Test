# Phase 20 — Kubernetes Guide

> **Learning goal:** Understand Kubernetes core concepts, when to use it vs
> Cloud Run, and how to articulate the trade-off in interviews.
>
> **Career connection:** Kubernetes appears in every enterprise infrastructure
> conversation. You need to know what it is and — more importantly — when NOT
> to use it.

---

## 1. What is Kubernetes?

Kubernetes (K8s) is an open-source container orchestration platform. It manages:
- **Where** containers run (which node/machine)
- **How many** replicas are running (scaling)
- **What happens** when a container crashes (restarts)
- **How traffic** reaches containers (services, ingress)
- **How containers** get configuration (ConfigMaps, Secrets)

```
Kubernetes Cluster
  ├── Control Plane (manages the cluster)
  │     ├── API Server (kubectl talks to this)
  │     ├── Scheduler (assigns pods to nodes)
  │     ├── Controller Manager (enforces desired state)
  │     └── etcd (cluster state database)
  │
  └── Worker Nodes (run your containers)
        ├── Node 1
        │     ├── Pod: api-deployment-7d9f8b-x4k
        │     └── Pod: api-deployment-7d9f8b-m2p
        ├── Node 2
        │     └── Pod: chromadb-deployment-5c6d-r9j
        └── Node 3
              └── Pod: celery-worker-deployment-3b2a-q7n
```

---

## 2. Core Concepts

### Pod

The smallest deployable unit. One or more containers that share network and storage.
In practice, most Pods contain exactly one container.

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: api-pod
spec:
  containers:
  - name: api
    image: asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:sha-abc123
    ports:
    - containerPort: 8000
```

### Deployment

Manages a set of identical Pods. Handles rolling updates, rollbacks, and scaling.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-deployment
spec:
  replicas: 2                    # keep 2 pods running
  selector:
    matchLabels:
      app: api
  template:
    metadata:
      labels:
        app: api
    spec:
      containers:
      - name: api
        image: asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:sha-abc123
        resources:
          requests:
            cpu: "250m"
            memory: "512Mi"
          limits:
            cpu: "1000m"
            memory: "1Gi"
        readinessProbe:
          httpGet:
            path: /ready
            port: 8000
          initialDelaySeconds: 10
        livenessProbe:
          httpGet:
            path: /health
            port: 8000
```

### Service

Stable network endpoint for a set of Pods. Pods are ephemeral and change IPs;
the Service IP is stable.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: api-service
spec:
  selector:
    app: api          # routes to pods with label app=api
  ports:
  - port: 80
    targetPort: 8000
  type: ClusterIP     # internal only
```

### ConfigMap

Non-secret configuration injected into Pods as environment variables or files.

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: api-config
data:
  ENVIRONMENT: "production"
  CHROMA_HOST: "chromadb-service"
  CHROMA_PORT: "8001"
  DEFAULT_LLM_PROVIDER: "gemini"
```

### Secret

Sensitive configuration (base64 encoded, not encrypted by default — use Sealed
Secrets or KMS for real encryption).

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: api-secrets
type: Opaque
data:
  SECRET_KEY: <base64 encoded value>
  DATABASE_URL: <base64 encoded value>
```

In production: use **External Secrets Operator** to pull secrets from GCP Secret
Manager instead of storing them in Kubernetes Secrets.

### Ingress

HTTP/HTTPS routing rules — maps external URLs to internal Services.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: api-ingress
  annotations:
    kubernetes.io/ingress.class: "gce"
spec:
  rules:
  - host: api.yourdomain.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: api-service
            port:
              number: 80
```

### HPA (Horizontal Pod Autoscaler)

Scales the number of Pod replicas based on CPU, memory, or custom metrics.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: api-deployment
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

---

## 3. How This Project Would Look on Kubernetes

The equivalent of our Cloud Run deployment:

```
GKE Cluster (Google Kubernetes Engine)
  │
  ├── Namespace: ai-assistant
  │     ├── Deployment: api (2–10 replicas)
  │     ├── Deployment: chromadb (1 replica)
  │     ├── Deployment: celery-worker (1–5 replicas)
  │     ├── Service: api-service (ClusterIP)
  │     ├── Service: chromadb-service (ClusterIP, internal only)
  │     ├── Ingress: api-ingress (routes external traffic)
  │     ├── HPA: api-hpa (scales on CPU)
  │     ├── ConfigMap: api-config (non-secret env vars)
  │     └── ExternalSecret: api-secrets (pulls from Secret Manager)
  │
  └── Namespace: monitoring
        ├── Deployment: prometheus
        ├── Deployment: grafana
        └── Deployment: loki
```

---

## 4. Cloud Run vs Kubernetes

| Use Cloud Run when... | Use Kubernetes when... |
|----------------------|----------------------|
| Stateless HTTP services | Stateful workloads (persistent volumes) |
| Cost is a priority (scales to zero) | Need fine-grained resource control |
| Team is small (1–5 engineers) | Large team with dedicated platform engineers |
| Simple networking | Complex service mesh (mTLS between services) |
| No persistent workloads | Long-running background jobs |
| Want zero infrastructure management | Need customization (custom schedulers, operators) |
| Startup < 30s is acceptable | Need consistent sub-second startup |
| Traffic is bursty / unpredictable | Traffic is steady and predictable |

**For this project:** Cloud Run is the right choice.

```
Our project:
  ✅ Stateless HTTP services (FastAPI, ChromaDB)
  ✅ Cost priority (scale to zero when idle → ₹0)
  ✅ Small team
  ✅ Simple networking (internal ChromaDB, public FastAPI)
  ❌ No stateful workloads except PostgreSQL (Neon managed)
  ❌ No complex service mesh requirements
```

**Kubernetes makes sense when:**
- The system has 20+ services with complex inter-service communication
- You need custom operators (database operators, ML training operators)
- Compliance requires on-premises deployment
- You have a dedicated platform team to manage the cluster

---

## 5. The Upgrade Path

When this project outgrows Cloud Run:

```
Current (Cloud Run):
  FastAPI + ChromaDB on Cloud Run
  Scale to zero, auto-scaling, zero cluster management

↓ When you need persistent vector storage:
  Add Cloud Storage FUSE volume or managed ChromaDB
  Still on Cloud Run

↓ When you need complex ML workloads (training, batch inference):
  Add Cloud Run Jobs for batch processing
  Still no K8s needed

↓ When you have 10+ microservices with complex routing:
  Migrate to GKE (Google Kubernetes Engine)
  Use Anthos Service Mesh for mTLS between services
  Use External Secrets Operator for Secret Manager integration

↓ When you need ML model serving at scale:
  Add Vertex AI for model hosting
  Or KServe on GKE for custom model serving
```

---

## 6. kubectl Commands — Quick Reference

```bash
# View cluster state
kubectl get pods -n ai-assistant
kubectl get deployments -n ai-assistant
kubectl get services -n ai-assistant
kubectl get ingress -n ai-assistant

# Scale a deployment
kubectl scale deployment api-deployment --replicas=5 -n ai-assistant

# Rolling update
kubectl set image deployment/api-deployment \
  api=asia-south1-docker.pkg.dev/project/backend/api:sha-new123 \
  -n ai-assistant

# Roll back
kubectl rollout undo deployment/api-deployment -n ai-assistant

# View logs
kubectl logs deployment/api-deployment -n ai-assistant --follow

# Execute a command inside a running pod
kubectl exec -it <pod-name> -n ai-assistant -- /bin/bash

# Apply a configuration file
kubectl apply -f k8s/deployment.yaml -n ai-assistant

# Delete a resource
kubectl delete deployment api-deployment -n ai-assistant

# View events (useful for debugging failed pods)
kubectl get events -n ai-assistant --sort-by='.lastTimestamp'
```

---

## 7. Interview Questions

**Q1: What is a Pod? How is it different from a container?**

A container is a running process with its own filesystem namespace (Docker/containerd).
A Pod is a Kubernetes abstraction that wraps one or more containers that share
the same network namespace (same IP address) and can share storage volumes.

In practice most Pods have one container. Multi-container Pods are used for:
- **Sidecar** pattern: a main container + a logging/proxy sidecar
- **Init containers**: run before the main container to perform setup
- **Ambassador** pattern: a proxy container for the main service

---

**Q2: What is the difference between a Deployment and a StatefulSet?**

A `Deployment` manages stateless Pods. Pods are interchangeable — they can be
deleted and replaced with any name. Used for API servers, web apps, stateless workers.

A `StatefulSet` manages stateful Pods. Each Pod has:
- A stable network name (`pod-0`, `pod-1`, `pod-2` — not random)
- A stable persistent volume that follows the Pod if rescheduled
- Ordered startup and shutdown (pod-0 before pod-1)

Used for: databases, Kafka, Elasticsearch, ZooKeeper — anything that needs stable
identity or persistent storage.

---

**Q3: How do rolling deployments work in Kubernetes?**

A rolling deployment replaces old Pods with new ones gradually:

```
Desired: 4 replicas, maxSurge=1, maxUnavailable=1

Start: [v1] [v1] [v1] [v1]
Step 1: [v2] [v1] [v1] [v1]  # +1 new, 0 removed
Step 2: [v2] [v2] [v1] [v1]  # +1 new, -1 old
Step 3: [v2] [v2] [v2] [v1]
Step 4: [v2] [v2] [v2] [v2]  # done
```

At no point does available capacity drop below 3 replicas (maxUnavailable=1).
Traffic is always served. If the new version fails health checks, the rollout pauses.

`kubectl rollout undo` reverts to the previous `ReplicaSet`.

---

**Q4: What is the difference between a ConfigMap and a Secret?**

ConfigMaps store non-sensitive configuration as key-value pairs or files.
Secrets store sensitive data — base64 encoded (NOT encrypted by default).

The encoding is for binary compatibility, not security. A Kubernetes Secret
is readable by anyone with access to the namespace. Real secret security requires:
1. **etcd encryption** at rest (configure via KMS)
2. **RBAC** restricting Secret access to specific service accounts
3. **External Secrets Operator** pulling from a proper secrets store (Vault, GCP Secret Manager)

In this project we use GCP Secret Manager — Cloud Run pulls secrets at startup via
IAM. No K8s Secrets needed. On GKE, External Secrets Operator provides the same pattern.

---

**Q5: When would you NOT use Kubernetes for this project?**

For the current scale of this project, Kubernetes would be over-engineering:

1. **Cost** — A 3-node GKE cluster costs ~₹4,000–8,000/month always-on vs Cloud Run
   at ₹0 when idle.
2. **Operational overhead** — Cluster upgrades, node pool management, CNI plugin
   maintenance, RBAC configuration. With a 1-person team, this is a significant burden.
3. **Cold start** — K8s doesn't scale to zero (without KEDA or Knative). Keeping
   minimum 2 replicas for HA costs money 24/7.
4. **Complexity** — Debugging a failed Pod requires more steps than `gcloud run
   services logs read`. The learning curve is steep.

Cloud Run handles everything this project needs at zero operational overhead.
"Don't use K8s until Cloud Run's limitations actually hurt you."

---

## Phase 20 Summary

Kubernetes is the right choice when you need:
- 10+ services with complex inter-service networking
- Stateful workloads with persistent volumes
- Custom operators and extensibility
- On-premises or multi-cloud deployment

This project uses Cloud Run — the right tool at portfolio scale.
The knowledge of K8s concepts (Pods, Deployments, Services, HPA) is directly
transferable to understanding Cloud Run's abstractions.

---

## 🎉 All 20 Phases Complete

You have now built the complete **AI DevOps Assistant** from end to end:

| Phase | What was built | Status |
|-------|---------------|--------|
| 1 | Android Foundation (MVVM, Hilt, Retrofit, Room) | ✅ |
| 2 | Android Observability (ObservabilityEvent, PiiFilter, NetworkInterceptor) | ✅ |
| 3 | Backend (FastAPI, PostgreSQL, Redis, Celery) | ✅ |
| 4 | DevOps Foundation (GitHub Actions CI, branch strategy) | ✅ |
| 5 | Docker (multi-stage build, docker-compose, ChromaDB) | ✅ |
| 6 | Google Cloud (Cloud Run, Artifact Registry, Secret Manager) | ✅ |
| 7 | Terraform (IaC for all GCP resources) | ✅ |
| 8 | Observability (structured logs, Prometheus, OpenTelemetry) | ✅ |
| 9 | RAG Knowledge Base (runbooks, incidents, architecture docs) | ✅ |
| 10 | AI Error Analysis (Phase 10 pipeline: events → RAG → LLM) | ✅ |
| 11 | Anomaly Detection (Stage 1/2, Celery beat, incident creation) | ✅ |
| 12 | Root Cause Analysis (chain-of-thought, ranked candidates) | ✅ |
| 13 | AI DevOps Assistant (ReAct loop, 7 tools, /devops/chat) | ✅ |
| 14 | Android AI DevOps Dashboard (incidents, AI card, chat) | ✅ |
| 15 | AIOps (push notifications, remediation recommendations, approval) | ✅ |
| 16 | Security (all layers documented) | ✅ |
| 17 | Testing (unit, integration, AI/RAG evaluation) | ✅ |
| 18 | Production CI/CD (GitHub Actions full pipeline) | ✅ |
| 19 | Jenkins (equivalent Jenkinsfile, comparison) | ✅ |
| 20 | Kubernetes (core concepts, Cloud Run vs K8s decision) | ✅ |

**Career path achieved:**
```
Android → DevOps → GenAI → AIOps
    ↑         ↑       ↑       ↑
Phase 1-2  Phase 4-8  Phase 9-13  Phase 14-15
```
