# Phase 19 — Jenkins Guide

> **Learning goal:** Understand Jenkins as a second CI/CD tool, how it differs
> from GitHub Actions, and when each is the right choice.
>
> **Career connection:** Many enterprise companies still run Jenkins. Understanding
> both tools makes you immediately employable at companies transitioning between them.

---

## 1. Why Jenkins When You Already Have GitHub Actions?

This project uses GitHub Actions as the primary CI/CD tool. Jenkins is covered
as a **learning exercise** — not to replace GitHub Actions, but to understand:

1. What Jenkins does that GitHub Actions doesn't
2. What GitHub Actions does better
3. How to talk about CI/CD architecture in enterprise interviews

---

## 2. GitHub Actions vs Jenkins

| Dimension | GitHub Actions | Jenkins |
|-----------|---------------|---------|
| **Hosting** | GitHub-managed (SaaS) | Self-hosted on your server/VM |
| **Config** | YAML in `.github/workflows/` | `Jenkinsfile` in repo root |
| **Agent** | GitHub-provided runners (Ubuntu/Windows/macOS) | Docker containers or VMs you provision |
| **Plugins** | GitHub Marketplace (~16,000 actions) | Jenkins Plugin Index (~1,800 plugins) |
| **Cost** | Free for public repos, minutes-based for private | Infrastructure cost only (no per-minute charge) |
| **Startup time** | 30–60 seconds (fresh runner) | 5–15 seconds (persistent agent, warm Docker image) |
| **Secrets** | GitHub Secrets | Jenkins Credentials Store |
| **Auditability** | GitHub UI + API | Jenkins Blue Ocean + REST API |
| **Self-hosted runners** | Supported | N/A — always self-hosted |
| **Best for** | Cloud-native, greenfield, GitHub-hosted repos | Enterprise, on-premises, existing infra |

---

## 3. Equivalent Jenkins Pipeline for This Project

The GitHub Actions `backend-ci.yml` pipeline maps to this `Jenkinsfile`:

```groovy
// Jenkinsfile — place in project root
// Equivalent to .github/workflows/backend-ci.yml

pipeline {
    agent {
        // Run each stage in a Docker container
        // Same base image as the backend Docker build
        docker {
            image 'python:3.11-slim'
            args '-v /var/run/docker.sock:/var/run/docker.sock'
        }
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    environment {
        DOCKER_REGISTRY = 'asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend'
        IMAGE_NAME      = "${DOCKER_REGISTRY}/api"
        IMAGE_TAG       = "${GIT_COMMIT.take(7)}"
        // Credentials from Jenkins Credentials Store
        GCP_SA_KEY      = credentials('gcp-service-account-key')
    }

    stages {

        stage('Install Dependencies') {
            steps {
                sh 'pip install -r backend/requirements.txt'
            }
        }

        stage('Security Scan') {
            parallel {
                stage('Bandit') {
                    steps {
                        sh 'bandit -r backend/app/ -f json -o bandit-report.json || true'
                    }
                }
                stage('pip-audit') {
                    steps {
                        sh 'pip-audit -r backend/requirements.txt'
                    }
                }
            }
        }

        stage('Unit Tests') {
            steps {
                sh '''
                    cd backend
                    pytest tests/unit/ \
                        --timeout=30 \
                        --junitxml=../test-results/unit.xml \
                        --cov=app \
                        --cov-report=xml:../coverage/coverage.xml
                '''
            }
            post {
                always {
                    junit 'test-results/unit.xml'
                    cobertura coberturaReportFile: 'coverage/coverage.xml'
                }
            }
        }

        stage('Integration Tests') {
            steps {
                // Start services using Docker Compose
                sh 'docker-compose -f docker-compose.yml up -d postgres redis chromadb'
                sh 'sleep 15'  // wait for services to be healthy

                sh '''
                    cd backend
                    pytest tests/integration/ \
                        --timeout=60 \
                        --junitxml=../test-results/integration.xml
                '''
            }
            post {
                always {
                    junit 'test-results/integration.xml'
                    sh 'docker-compose -f docker-compose.yml down'
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                sh """
                    docker build \
                        --target production \
                        --tag ${IMAGE_NAME}:${IMAGE_TAG} \
                        --tag ${IMAGE_NAME}:latest \
                        backend/
                """
            }
        }

        stage('Trivy Scan') {
            steps {
                sh """
                    trivy image \
                        --exit-code 1 \
                        --severity CRITICAL \
                        --format sarif \
                        --output trivy-results.sarif \
                        ${IMAGE_NAME}:${IMAGE_TAG} || true
                """
            }
        }

        stage('Push to Artifact Registry') {
            when {
                branch 'main'
            }
            steps {
                sh """
                    echo '${GCP_SA_KEY}' | \
                        docker login -u _json_key --password-stdin \
                        https://asia-south1-docker.pkg.dev

                    docker push ${IMAGE_NAME}:${IMAGE_TAG}
                    docker push ${IMAGE_NAME}:latest
                """
            }
        }

        stage('Deploy to Cloud Run') {
            when {
                branch 'main'
            }
            steps {
                sh """
                    echo '${GCP_SA_KEY}' > /tmp/gcp-key.json
                    gcloud auth activate-service-account --key-file=/tmp/gcp-key.json
                    gcloud config set project android-ai-assistant-89cec

                    gcloud run deploy ai-assistant-backend \
                        --image=${IMAGE_NAME}:${IMAGE_TAG} \
                        --region=asia-south1 \
                        --platform=managed

                    rm /tmp/gcp-key.json
                """
            }
        }

        stage('Smoke Test') {
            when {
                branch 'main'
            }
            steps {
                sh '''
                    SERVICE_URL=$(gcloud run services describe ai-assistant-backend \
                        --region=asia-south1 \
                        --format="value(status.url)")

                    for i in 1 2 3 4 5; do
                        HTTP=$(curl -s -o /dev/null -w "%{http_code}" $SERVICE_URL/health)
                        if [ "$HTTP" = "200" ]; then
                            echo "✅ Health check passed"
                            exit 0
                        fi
                        echo "Attempt $i failed (HTTP $HTTP) — retrying in 8s..."
                        sleep 8
                    done
                    echo "❌ Health check failed after 5 attempts"
                    exit 1
                '''
            }
        }
    }

    post {
        success {
            slackSend(
                color: 'good',
                message: "✅ Build ${BUILD_NUMBER} deployed successfully — ${GIT_COMMIT.take(7)}"
            )
        }
        failure {
            slackSend(
                color: 'danger',
                message: "❌ Build ${BUILD_NUMBER} failed — ${GIT_COMMIT.take(7)} — ${BUILD_URL}"
            )
        }
        always {
            archiveArtifacts artifacts: 'trivy-results.sarif, bandit-report.json', allowEmptyArchive: true
            cleanWs()
        }
    }
}
```

---

## 4. Key Jenkins Concepts

### Pipeline types

**Declarative Pipeline** (used above): structured syntax with `pipeline {}` block.
Easier to read, validates syntax before running. Preferred for new pipelines.

**Scripted Pipeline**: Groovy code with `node {}` block. More flexible but harder
to read. Used in legacy pipelines.

### Parallel stages

```groovy
stage('Security Scan') {
    parallel {
        stage('Bandit') { ... }
        stage('pip-audit') { ... }
    }
}
```

Both security scans run simultaneously. Total time = max(bandit_time, pip_audit_time)
instead of bandit_time + pip_audit_time.

### When conditions

```groovy
stage('Deploy') {
    when {
        branch 'main'  // only runs on the main branch
    }
    ...
}
```

The `when` block prevents deploy stages from running on feature branches.
Equivalent to `if: github.ref == 'refs/heads/main'` in GitHub Actions.

### Credentials Store

Jenkins Credentials Store holds secrets that workflows reference by ID:

```groovy
environment {
    GCP_SA_KEY = credentials('gcp-service-account-key')
}
```

The value is masked in logs and never printed. Equivalent to GitHub Secrets.

### Post blocks

```groovy
post {
    always { ... }   // runs whether success or failure
    success { ... }  // only on success
    failure { ... }  // only on failure
    unstable { ... } // test failures but build succeeded
}
```

Used for cleanup, notifications, and report archiving.

---

## 5. Setting Up Jenkins Locally (optional)

```bash
# Run Jenkins in Docker (for learning only — not production)
docker run -d \
    -p 8080:8080 \
    -p 50000:50000 \
    -v jenkins_home:/var/jenkins_home \
    -v /var/run/docker.sock:/var/run/docker.sock \
    --name jenkins \
    jenkins/jenkins:lts-jdk17

# Get initial admin password
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Then: http://localhost:8080 → Install suggested plugins → Create admin user.

---

## 6. Interview Questions

**Q1: When would you choose Jenkins over GitHub Actions?**

Jenkins when:
- The team already runs Jenkins with extensive plugins and custom integrations
- Build artifacts need to stay on-premises (compliance, no cloud egress allowed)
- You need long-running builds (GitHub Actions has a 6-hour limit per job)
- Complex orchestration across many repos and teams with shared library code

GitHub Actions when:
- Green-field project hosted on GitHub
- Team is small and doesn't want to maintain CI infrastructure
- Cost is a concern (GitHub Actions free tier is generous for public repos)
- Tight GitHub ecosystem integration (PR checks, deployment environments, etc.)

---

**Q2: What is a Jenkinsfile and why store it in the repository?**

A `Jenkinsfile` defines the build pipeline in code. Storing it in the repository means:
- Pipeline changes go through code review (PR) like any other code
- Every branch has its own pipeline definition — feature branches can test
  pipeline changes without affecting the main pipeline
- The pipeline is versioned alongside the code it builds
- "Infrastructure as code" for CI/CD

Without a Jenkinsfile, pipelines are configured through the Jenkins UI — not
auditable, not reviewed, and easy to accidentally change.

---

**Q3: What is the difference between a Jenkins agent and executor?**

An **agent** is a machine (VM, container, or cloud instance) where builds run.
An **executor** is a thread on an agent that can run one build at a time.

A single agent with 4 executors can run 4 builds in parallel. The Jenkins controller
schedules builds across available executors on all registered agents.

In this project's Jenkinsfile, `agent { docker { image '...' } }` means each
stage runs in a fresh Docker container — the container is the agent.

---

## Phase 19 Summary

Jenkins provides the same capabilities as GitHub Actions through a different model:
- Self-hosted infrastructure vs GitHub-managed runners
- Groovy Jenkinsfile vs YAML workflow files
- Jenkins Credentials vs GitHub Secrets
- Jenkins plugins vs GitHub Marketplace actions

The pipeline concepts are identical: checkout → build → test → scan → push → deploy.
The syntax and hosting differ. Senior engineers know both.

Say `NEXT` to continue to **Phase 20 — Kubernetes**.
