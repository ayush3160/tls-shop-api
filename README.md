# tls-shop-api

A deliberately large Spring Boot REST API backed by **MongoDB over TLS**. The TLS
link is **CA-validated but not certificate-pinned**: the app trusts any server
certificate that chains to a trusted CA, so the MongoDB leaf cert can rotate
without any client change. There is no hardcoded server cert or public-key hash.

Domain: a small e-commerce backend — **users, categories, products, orders, reviews** —
chosen to exercise many API shapes (CRUD, pagination, sorting, filtering, search,
sub-resources, status transitions, aggregate recomputation, and every error path).

## Stack
- Java 17, Spring Boot 3.3 (web, data-mongodb, validation, actuator)
- MongoDB 7 (TLS `requireTLS`, one-way / no client cert)

## Layout
```
pom.xml
docker-compose.yml            # TLS MongoDB
src/main/java/io/keploy/shop/
  config/MongoTlsConfig.java   # TLS-but-not-pinned driver config  <-- the key file
  model/ repository/ service/ controller/ dto/ exception/
scripts/
  gen-certs.sh                 # CA + server cert + Java truststore
  seed-and-hit.sh              # seeds data AND hits every route
```

## The TLS design (not pinned)
`config/MongoTlsConfig.java` builds the Mongo driver's `SSLContext`:
- Default: the **JVM system CA truststore** (standard public-CA validation).
- Optional: an operator-supplied **CA truststore** (`MONGO_TLS_TRUSTSTORE`) — still
  trusting a *CA*, not a specific leaf cert. This is what "not pinned" means.
- Dev-only escape hatch: `MONGO_TLS_ALLOW_INVALID=true` trusts any cert (encrypted
  but unauthenticated) for quick local self-signed testing.

Because trust is anchored at the CA, a pinning client would break when the server
rotates its certificate — this one does not.

## Quick start (local, end-to-end TLS)
```bash
# 1. Generate a CA, a MongoDB server cert, and a Java truststore holding the CA
./scripts/gen-certs.sh

# 2. Start MongoDB with TLS required (host port 27019 -> container 27017)
docker compose up -d mongodb

# 3. Run the API, pointing it at the TLS MongoDB and the CA truststore
export MONGODB_URI='mongodb://localhost:27019/shopdb?tls=true'
export MONGO_TLS_TRUSTSTORE="$PWD/certs/truststore.p12"
export MONGO_TLS_TRUSTSTORE_PASSWORD=changeit
export SERVER_PORT=8090
mvn spring-boot:run

# 4. In another shell: seed data and exercise every route
BASE_URL=http://localhost:8090 ./scripts/seed-and-hit.sh
```
`GET /api/health/db` reports `"tls": true` and a live ping latency.

## Configuration (env vars)
| Var | Default | Meaning |
|-----|---------|---------|
| `MONGODB_URI` | `mongodb://localhost:27017/shopdb?tls=true` | Mongo connection string |
| `MONGO_TLS_ENABLED` | `true` | Toggle driver TLS |
| `MONGO_TLS_TRUSTSTORE` | *(empty)* | CA truststore (PKCS12/JKS); empty = JVM default CAs |
| `MONGO_TLS_TRUSTSTORE_PASSWORD` | *(empty)* | Truststore password |
| `MONGO_TLS_ALLOW_INVALID` | `false` | Dev-only: trust any cert |
| `SERVER_PORT` | `8080` | HTTP port |

To point at MongoDB Atlas instead, just set a `mongodb+srv://…?tls=true` URI and
leave the truststore empty (Atlas certs chain to public CAs).

## Route map
```
GET    /health
GET    /api/health/db
GET    /actuator/health

# Categories
GET    /api/categories            ?active= | ?parentId=
POST   /api/categories
GET    /api/categories/{id}
GET    /api/categories/slug/{slug}
PUT    /api/categories/{id}
PATCH  /api/categories/{id}
DELETE /api/categories/{id}

# Products
GET    /api/products              ?page&size&sort&dir&categoryId&minPrice&maxPrice&tag&active
GET    /api/products/search       ?q=
GET    /api/products/{id}
GET    /api/products/sku/{sku}
GET    /api/products/{id}/reviews
POST   /api/products
PUT    /api/products/{id}
PATCH  /api/products/{id}
PATCH  /api/products/{id}/stock   {"delta":-5} | {"set":100}
DELETE /api/products/{id}

# Users
GET    /api/users                 ?page&size&sort&dir&status&role
GET    /api/users/search          ?q=
GET    /api/users/count           ?status=
GET    /api/users/{id}
GET    /api/users/by-email        ?email=
POST   /api/users
PUT    /api/users/{id}
PATCH  /api/users/{id}
DELETE /api/users/{id}

# Orders  (prices/totals computed server-side; stock decremented)
GET    /api/orders                ?page&size&status
GET    /api/orders/{id}
GET    /api/orders/user/{userId}
GET    /api/orders/count          ?status=
POST   /api/orders
PATCH  /api/orders/{id}/status    {"status":"PAID"}
POST   /api/orders/{id}/cancel    # restocks
DELETE /api/orders/{id}

# Reviews  (recompute product rating aggregate on write)
GET    /api/reviews               ?productId= | ?userId=
GET    /api/reviews/{id}
POST   /api/reviews
POST   /api/reviews/{id}/helpful
DELETE /api/reviews/{id}
```

## Kubernetes (kind)
Manifests live in `k8s/` (namespace, MongoDB Deployment+Service, API
Deployment+Service, ConfigMap). The API is exposed on **NodePort 30090**.

```bash
# One-shot: build jar + image, load into kind, create secrets, apply, wait
./k8s/deploy.sh                       # KIND_CLUSTER defaults to "kind"

# …or manually:
./scripts/gen-certs.sh
mvn -o clean package -DskipTests
docker build -t tls-shop-api:1.0.0 .
kind load docker-image tls-shop-api:1.0.0 --name kind
kubectl -n tls-shop create secret generic mongo-certs \
  --from-file=ca.crt=certs/ca.crt --from-file=mongodb.pem=certs/mongodb.pem \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n tls-shop create secret generic app-truststore \
  --from-file=truststore.p12=certs/truststore.p12 \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n tls-shop create secret generic app-secrets \
  --from-literal=MONGO_TLS_TRUSTSTORE_PASSWORD=changeit \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -k k8s/

# verify + seed (if the kind node maps host port 30090)
curl -s http://localhost:30090/api/health/db          # -> {"tls":true,...}
BASE_URL=http://localhost:30090 ./scripts/seed-and-hit.sh
```

In-cluster the app connects to `mongodb://mongodb:27017/shopdb?tls=true`. The
Service is deliberately named `mongodb` so it matches the server cert SAN — CA
validation + hostname check pass without pinning. TLS material is injected as
Secrets (`mongo-certs`, `app-truststore`) created from `gen-certs.sh` output, not
committed to git.

Tear down: `kubectl delete -k k8s/ && kubectl delete ns tls-shop`.

## Notes
- Order pricing is authoritative: the server prices each line from the catalogue,
  computes tax (8%) + flat shipping, decrements stock, and rejects oversell (409).
- `scripts/seed-and-hit.sh` covers happy paths **and** negative cases
  (409 duplicates, 400 validation, 404 not-found) — 58 checks total.
- `certs/` and `target/` are git-ignored; regenerate certs with `gen-certs.sh`.
