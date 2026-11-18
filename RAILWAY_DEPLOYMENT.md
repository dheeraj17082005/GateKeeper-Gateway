# Railway Deployment Guide for Gatekeeper

This guide walks you through deploying Gatekeeper to [Railway](https://railway.app) with the **Smart Gateway publicly accessible** and all internal components (API Management, MySQL, Redis, Kafka) protected on Railway's private network.

---

## Architecture on Railway

```
                   [ Internet / User / Application Form ]
                                    │
                                    ▼ (HTTPS - Public Domain)
                    ┌───────────────────────────────┐
                    │     Smart Gateway Service     │ (Port $PORT)
                    └───────────────┬───────────────┘
                                    │ (Railway Private Network *.railway.internal)
          ┌─────────────────────────┼─────────────────────────┐
          ▼                         ▼                         ▼
┌───────────────────┐     ┌───────────────────┐     ┌───────────────────┐
│  API Management   │     │   Redis Plugin    │     │   Kafka Service   │
│  (Control Plane)  │     │  (Rate Limiting/  │     │   (Event Sync)    │
│   (Internal only) │     │   Idempotency)    │     │  (Internal only)  │
└─────────┬─────────┘     └───────────────────┘     └───────────────────┘
          ▼
┌───────────────────┐
│   MySQL Plugin    │
│  (Internal only)  │
└───────────────────┘
```

Only the **Smart Gateway Service** has a public URL (`https://<your-gateway>.up.railway.app`). The API Management control plane, MySQL database, Redis cache, and Kafka broker are not exposed to the public internet and communicate securely over Railway's private network.

---

## Step-by-Step Deployment Instructions

### 1. Create a Project on Railway
1. Sign in to [Railway](https://railway.app).
2. Click **New Project** -> **Empty Project**.
3. Name your project (e.g. `gatekeeper-prod`).

---

### 2. Add MySQL Database
1. In your project canvas, click **Create** / **New** -> **Database** -> **Add MySQL**.
2. Railway creates a private MySQL database and automatically generates the following variables:
   - `MYSQL_URL`
   - `MYSQLHOST`
   - `MYSQLPORT`
   - `MYSQLUSER`
   - `MYSQLPASSWORD`
   - `MYSQLDATABASE`

---

### 3. Add Redis Database
1. Click **New** -> **Database** -> **Add Redis**.
2. Railway creates a private Redis instance and generates:
   - `REDISHOST`
   - `REDISPORT`
   - `REDISPASSWORD`

---

### 4. Add Kafka Service
1. Click **New** -> **Docker Image**.
2. Specify the image: `apache/kafka:4.1.1`.
3. Rename the service to `kafka`.
4. Under **Variables**, add:
   - `KAFKA_NODE_ID`: `1`
   - `KAFKA_PROCESS_ROLES`: `broker,controller`
   - `KAFKA_LISTENERS`: `PLAINTEXT://:9092,CONTROLLER://:9093`
   - `KAFKA_ADVERTISED_LISTENERS`: `PLAINTEXT://kafka.railway.internal:9092`
   - `KAFKA_CONTROLLER_LISTENER_NAMES`: `CONTROLLER`
   - `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP`: `CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT`
   - `KAFKA_CONTROLLER_QUORUM_VOTERS`: `1@kafka.railway.internal:9093`
   - `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`: `1`
   - `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR`: `1`
   - `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR`: `1`
   - `KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS`: `0`
5. Do **NOT** generate a public domain for `kafka`.

---

### 5. Deploy API Management Service (Internal Service)
1. Click **New** -> **GitHub Repo** (select this repository).
2. Rename the service to `api-management`.
3. In **Settings**:
   - **Root Directory**: `Gatekeeper-Final/api-management-service`
   - **Build**: Uses the included `Dockerfile` automatically.
4. In **Variables**, add:
   - `GATEKEEPER_DB_URL`: `jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?allowPublicKeyRetrieval=true&sslMode=DISABLED`
   - `GATEKEEPER_DB_USER`: `${{MySQL.MYSQLUSER}}`
   - `GATEKEEPER_DB_PASSWORD`: `${{MySQL.MYSQLPASSWORD}}`
   - `GATEKEEPER_JWT_SECRET`: *(A secure 64-char hex string, generated with `openssl rand -hex 32`)*
   - `GATEKEEPER_ADMIN_PASSWORD`: *(A secure password for platform-admin)*
   - `GATEKEEPER_GATEWAY_PASSWORD`: *(A secure password for gateway service account)*
   - `GATEKEEPER_KAFKA_SERVERS`: `kafka.railway.internal:9092`
5. **Networking**: Ensure private networking is enabled. Do **NOT** generate a public domain (keep it internal). It will be reachable internally at `http://api-management.railway.internal:8081`.

---

### 6. Deploy Smart Gateway Service (Public Service)
1. Click **New** -> **GitHub Repo** (select this repository).
2. Rename the service to `smart-gateway`.
3. In **Settings**:
   - **Root Directory**: `Gatekeeper-Final/smart-gateway-service`
   - **Build**: Uses the included `Dockerfile` automatically.
4. In **Variables**, add:
   - `GATEKEEPER_MANAGEMENT_URL`: `http://api-management.railway.internal:8081`
   - `GATEKEEPER_GATEWAY_PASSWORD`: *(Must match the `GATEKEEPER_GATEWAY_PASSWORD` set in API Management)*
   - `GATEKEEPER_KAFKA_SERVERS`: `kafka.railway.internal:9092`
   - `GATEKEEPER_REDIS_HOST`: `${{Redis.REDISHOST}}`
   - `GATEKEEPER_REDIS_PORT`: `${{Redis.REDISPORT}}`
   - `GATEKEEPER_REDIS_PASSWORD`: `${{Redis.REDISPASSWORD}}`
   - `GATEKEEPER_ALLOWED_UPSTREAMS`: *(Comma-separated list of allowed upstream origins, e.g. `https://dummyjson.com,https://api.github.com` or your own backend services)*
5. In **Networking**:
   - Click **Generate Domain**. Railway will assign a public domain such as:
     `https://smart-gateway-production-xxxx.up.railway.app`

---

## Verifying the Live Deployment

1. **Gateway Health Check:**
   ```sh
   curl https://<YOUR_GATEWAY_DOMAIN>.up.railway.app/health
   ```
   Expected response:
   ```json
   {"serviceName":"Smart Gateway Service","serviceDescription":"Custom API gateway for routing, security, and traffic management","appStatus":"OK"}
   ```

2. **Route an API Request Through the Gateway:**
   - Once a route is configured in API Management pointing to an allowed upstream (such as `https://dummyjson.com`), make a request:
   ```sh
   curl -i https://<YOUR_GATEWAY_DOMAIN>.up.railway.app/<your-tenant-route>
   ```
   - Notice the forwarded headers, `X-Correlation-ID`, and rate limit headers returned by the live gateway!

---

## Submitting Your Application

For the application form, submit:
- **Live Gateway URL:** `https://<YOUR_GATEWAY_DOMAIN>.up.railway.app/health`
- **Example Routed Endpoint:** `https://<YOUR_GATEWAY_DOMAIN>.up.railway.app/<your-tenant-route>`
- **GitHub Repository Link:** *(Your repository link containing `Gatekeeper-Final`)*
