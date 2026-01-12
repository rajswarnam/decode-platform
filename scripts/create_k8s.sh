#!/bin/bash

mkdir -p k8s

# Helpers
create_deployment() {
    NAME=$1
    IMAGE=$2
    PORT=$3
    REPLICAS=${4:-1}
    cat > k8s/$NAME.yaml <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $NAME
  namespace: decode-app
  labels:
    app: $NAME
spec:
  replicas: $REPLICAS
  selector:
    matchLabels:
      app: $NAME
  template:
    metadata:
      labels:
        app: $NAME
    spec:
      containers:
      - name: $NAME
        image: $IMAGE
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: $PORT
---
apiVersion: v1
kind: Service
metadata:
  name: $NAME
  namespace: decode-app
spec:
  selector:
    app: $NAME
  ports:
  - protocol: TCP
    port: $PORT
    targetPort: $PORT
EOF
}

# Namespace
cat > k8s/namespace.yaml <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: decode-app
EOF

# Infrastructure
create_deployment "postgres" "postgres:16-alpine" 5432
# Add env vars for postgres
sed -i '' '/imagePullPolicy:/a\
        env:\
        - name: POSTGRES_PASSWORD\
          value: "password"\
        - name: POSTGRES_USER\
          value: "user"\
        - name: POSTGRES_DB\
          value: "decode"' k8s/postgres.yaml

create_deployment "minio" "minio/minio" 9000
# Fix MinIO command and ports
sed -i '' '/imagePullPolicy:/a\
        args:\
        - "server"\
        - "/data"\
        env:\
        - name: MINIO_ROOT_USER\
          value: "minioadmin"\
        - name: MINIO_ROOT_PASSWORD\
          value: "minioadmin"' k8s/minio.yaml
# Add Console port service
cat >> k8s/minio.yaml <<EOF
  - protocol: TCP
    port: 9001
    targetPort: 9001
EOF

create_deployment "qdrant" "qdrant/qdrant" 6333

# Services
create_deployment "web-frontend" "web-frontend:latest" 3000
create_deployment "api-gateway" "api-gateway:latest" 8080
create_deployment "ingestion-engine" "ingestion-engine:latest" 8080
create_deployment "code-parser" "code-parser:latest" 8080
create_deployment "vectorizer-service" "vectorizer-service:latest" 8080
create_deployment "context-orchestrator" "context-orchestrator:latest" 8080
create_deployment "documentation-hub" "documentation-hub:latest" 8080

echo "K8s manifests created in k8s/"
