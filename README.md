# Gatekeeper

Gatekeeper is a Spring Boot API gateway with a browser console for managing tenant routes. The local demo provisions a tenant, publishes a route, and retrieves a product catalog from a backend through the gateway.

## Run locally

Install Docker with Compose and start its daemon. The setup uses Java 21 and Maven inside Docker. For host builds, use a Java 21 JDK and Maven 3.9. The smoke test also needs Bash, curl, jq, and OpenSSL.

From this directory, run:

```sh
bash scripts/start-local.sh
```

The script starts Colima if it is the selected Docker runtime and is stopped, detects either Compose command, generates `.env` on first run, and verifies authentication and the demo route. Use a fresh MySQL database or Compose volume for this copy. The course database used a seed migration with fixed account credentials. Gatekeeper creates its service accounts on first boot. Updating the bootstrap values later leaves the existing account hashes in place.

Open the [Gatekeeper Console](http://127.0.0.1:8081/) and sign in as `platform-admin` with the password in `.env`. The gateway listens on port 8080. Analytics exposes `GET /health` on port 8082. Compose publishes all three services to loopback and keeps MySQL, Kafka, Redis, and the demo backend inside the Docker network.

```sh
mvn -B clean verify
docker compose ps
docker compose logs --tail=100 api-management smart-gateway analytics
docker compose down
```

## Architecture

| Component | Responsibility |
| --- | --- |
| API Management · 8081 | Console, role-based JWT authentication, organizations, routes, audit records, and route versions. Flyway migrates MySQL. |
| Smart Gateway · 8080 | Loads active routes, rewrites paths, applies header rules, forwards traffic, and returns the upstream response. |
| Kafka | Management publishes JSON change events to `gateway-configuration-events` after commit. The gateway treats each event as a signal to reload over HTTP and also reconciles every 30 seconds. |
| Redis | Per-route, per-client-IP rate limits and duplicate POST prevention for routes that enable those policies. |
| Analytics · 8082 | Service shell inherited from Project 05. Event ingestion and reporting are outside this MVP. MongoDB is unnecessary. |
| Demo catalog | Nginx serves a static catalog payload on the internal network. |

The gateway logs into `POST /api/auth/login` as `gateway-service`, then retrieves the active route snapshot from `GET /api/gateway/routes` using the returned bearer token. Routes for inactive organizations are excluded. The last successful snapshot remains in use during management outages.

Routes accept exact paths and trailing `/**` prefixes. The gateway preserves the raw query string and forwards request and response bodies up to 4 MiB each. The operator controls permitted upstream origins through the comma-separated `GATEKEEPER_ALLOWED_UPSTREAMS` variable. Compose defaults to `http://demo-backend:80`. The gateway does not follow upstream redirects.

When idempotency is enabled, a POST needs an `Idempotency-Key`. Redis reserves the key for 24 hours per route and observed client IP. A duplicate returns HTTP 409. The reservation is retained after an upstream error or timeout because the backend may already have performed the operation. Responses are not replayed. For rate-limited routes, the observed client IP comes from the connection, so clients behind the same reverse proxy may share a limit.

## Example API requests

Sign in to obtain a management token. This example reads the bootstrap password from `.env`.

```sh
jq -n --arg password "$(sed -n 's/^GATEKEEPER_ADMIN_PASSWORD=//p' .env)" \
  '{username:"platform-admin",password:$password}' |
  curl -fsS http://127.0.0.1:8081/api/auth/login \
    -H 'Content-Type: application/json' --data-binary @-
```

The smoke script uses that login to create and activate an organization, create a tenant administrator, and register a route with that tenant's token. It checks both the upstream catalog payload and the gateway's response headers. Each run creates a new demo tenant and prints its route for reuse. Substitute that printed path for `/<demo-slug>/catalog` below.

```sh
curl -i 'http://127.0.0.1:8080/<demo-slug>/catalog?source=manual' \
  -H 'X-Correlation-ID: gatekeeper-manual'
```

A successful response includes `X-Gatekeeper-Demo: catalog`, the correlation ID, and a body containing `"service":"gatekeeper-demo-catalog"`.

## Deployment on Railway

Gatekeeper is prepared for deployment on [Railway](https://railway.app):
- The **Smart Gateway Service** is publicly exposed to accept client requests and forward traffic.
- **API Management Service**, **MySQL**, **Redis**, and **Kafka** run privately inside Railway's secure internal network (`*.railway.internal`).
- Dynamic port assignment (`$PORT`), cloud Redis password authentication, and custom `Dockerfile` / `railway.json` descriptors are already configured.

For complete step-by-step instructions, see [RAILWAY_DEPLOYMENT.md](file:///Users/dheerajkumar/Gatekeeper-Project/Gatekeeper-Final/RAILWAY_DEPLOYMENT.md).

## Attribution

Based on [Aditya Tandon's Spring Framework Full Course](https://github.com/adityatandon15/Spring-Framework-Full-Course). API Management and Analytics come from Project 05, and Smart Gateway comes from Project 06. This adaptation adds request forwarding, environment-based account setup, an upstream allowlist, local orchestration, and an end-to-end catalog check.
