# Kubernetes sample (e‑commerce)

Illustrative manifests for **Archimo** infrastructure scanning and documentation—not a production-ready chart.

## Stack represented

| Concern | In-cluster / config |
|--------|----------------------|
| **HTTP front** | NGINX (static UI + reverse proxy to API gateway) |
| **API gateway** | Spring Cloud Gateway |
| **App** | Spring Boot sample JAR (`Dockerfile` in repo root) |
| **Database** | MariaDB |
| **Cache** | Redis |
| **Messaging** | Kafka (Bitnami) + ZooKeeper |
| **Object storage** | AWS S3 (bucket/region in ConfigMap; keys in Secret) |
| **Identity** | Google OAuth/OIDC + GCP Workload Identity annotation on the app pod |
| **Payments** | Stripe API (URL + secret placeholder) |
| **Banking control** | Visa Developer / fraud-control style API URL (sandbox) |
| **Mailing** | Mailchimp Marketing API base URL |

## Usage

```bash
# Build the app image (from archimo-sample-ecommerce/)
mvn -q -DskipTests package
docker build -t archimo-sample-ecommerce:local .

kubectl apply -f k8s/
```

Replace Secret placeholders before any real cluster. Tune images, storage, and ingress host for your environment.

## Files

- `00-namespace.yaml` — `ecommerce-sample` namespace  
- `01-configmap-integration-env.yaml` — JDBC, Redis, Kafka, S3, OAuth, Stripe, Visa, Mailchimp  
- `02-secret-placeholders.yaml` — fake credentials (replace in prod)  
- `10-mariadb.yaml`, `11-redis.yaml`, `12-zookeeper.yaml`, `13-kafka.yaml` — data & messaging  
- `20-ecommerce-app.yaml` — app Deployment/Service (GCP WI annotation)  
- `21-api-gateway.yaml` — Spring Cloud Gateway  
- `30-nginx-frontend.yaml` — NGINX + static `index.html` ConfigMap  
- `40-ingress.yaml` — NGINX Ingress (adjust host)
