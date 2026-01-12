pipeline {
    agent any
    
    // Enterprise Configuration
    environment {
        REGISTRY_URL = 'artifactory.corp.internal/docker-local'
        KUBE_NAMESPACE = 'decode-platform-dev'
    }

    stages {
        // 1. Parallel Build Stage: Smart detection of changed modules
        stage('Build & Push Artifacts') {
            parallel {
                
                // --- Backend Microservice ---
                stage('Context Orchestrator') {
                    when { changeset "context-orchestrator/**" }
                    steps {
                        dir('context-orchestrator') {
                            echo "🚀 Detected changes in Backend. Building..."
                            sh 'mvn clean package -DskipTests'
                            
                            script {
                                docker.build("${REGISTRY_URL}/context-orchestrator:${BUILD_NUMBER}")
                                      .push()
                            }
                        }
                    }
                }

                // --- Frontend Application ---
                stage('Web Frontend') {
                    when { changeset "web-frontend/**" }
                    steps {
                        dir('web-frontend') {
                            echo "🎨 Detected changes in Frontend. Building..."
                            sh 'npm install && npm run build'
                            
                            script {
                                docker.build("${REGISTRY_URL}/web-frontend:${BUILD_NUMBER}")
                                      .push()
                            }
                        }
                    }
                }

                // --- Python Parser Service ---
                stage('Code Parser') {
                    when { changeset "code-parser/**" }
                    steps {
                        dir('code-parser') {
                            echo "🐍 Detected changes in Parser. Building..."
                            
                            script {
                                docker.build("${REGISTRY_URL}/code-parser:${BUILD_NUMBER}")
                                      .push()
                            }
                        }
                    }
                }
            }
        }

        // 2. Deployment Stage: Updates IT Kubernetes Environment
        stage('Deploy to IT Cluster') {
            steps {
                script {
                    // Update K8s manifests with the new BUILD_NUMBER
                    sh "sed -i 's/:latest/:${BUILD_NUMBER}/g' k8s/**/*.yaml"
                    
                    // Apply to Cluster
                    sh "kubectl apply -f k8s/ -n ${KUBE_NAMESPACE}"
                }
            }
        }
    }
}
