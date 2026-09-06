# ai-proxy — AI Gateway as a Service

A lightweight, self-hosted AI gateway with two cooperating services:

- **gateway** (`gateway/`, Spring Boot WebFlux, port **8080**) — a fast reverse proxy to AI providers (OpenAI, Anthropic, Gemini, and any HTTP REST API). It authenticates clients with an `X-Gateway-Key` header, enforces quotas in Redis, forwards requests to providers with your own API keys (pass-through, keys are never stored), and exposes Prometheus metrics.
- **control-plane** (`control-plane/`, Spring Boot MVC + Postgres, port **8081**) — users, gateway API keys, subscription access codes, usage, an admin web UI, and a REST API. It writes keys and limits into the same Redis so the gateway picks them up.

The whole stack runs locally with Docker Compose: Postgres, Redis, gateway, control-plane, Prometheus, and Grafana.

## Repository layout

```
gateway/            # proxy module (WebFlux, Redis)
control-plane/      # users/keys/subscriptions module (MVC + JPA + Postgres)
deploy/             # docker-compose, Prometheus/Grafana config, containerized smoke tests
plans/              # architecture plan
```

## Run the system

Prerequisites: Docker with Compose v2.

```bash
cd deploy
docker compose up -d --build
```

| Service | Address | Notes |
|---|---|---|
| gateway | http://localhost:8080 | proxy; `/actuator/health`, `/actuator/prometheus` |
| control-plane | http://localhost:8081 | web UI + REST API |
| Prometheus | http://localhost:9090 | scrapes gateway + control-plane |
| Grafana | http://localhost:3000 | dashboard "AI Proxy" (login `admin`/`admin`) |
| Loki | http://localhost:3100 | log storage; Promtail ships all container logs here |
| Tempo | http://localhost:3200 | trace storage (OTLP on 4317/4318); OTel agents export traces here |
| Postgres | localhost:5432 | database `ai_proxy`, schema `public` |
| Redis | localhost:6379 | quota counters, gateway keys, limits |

Stop everything (data kept):

```bash
docker compose down
```

Add `-v` to also remove the Postgres data volume (`ai-proxy-pgdata`).

### Configuration

Everything is driven by environment variables (see `deploy/docker-compose.yml` and `deploy/.env.example`).

**Gateway:**

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / empty | Redis connection |
| `PROVIDERS_JSON` | gemini+openai+anthropic | provider base URLs; each key becomes a URL prefix, e.g. `/openai/v1/...` |
| `GATEWAY_MODE` | `true` | enable `X-Gateway-Key` authentication |
| `GATEWAY_KEY_HEADER` | `X-Gateway-Key` | header that carries the gateway key |
| `GATEWAY_FAIL_OPEN` | `false` | allow requests if key resolution fails |
| `QUOTA_FAIL_OPEN` | `true` | allow requests if the quota service fails |
| `QUOTA_DAILY_LIMIT` | `20` | free tier daily limit |
| `QUOTA_MONTHLY_LIMIT` | `40` | free tier monthly limit |
| `ALLOWED_IPS` / `ADMIN_TOKEN` | empty | legacy IP allowlist + `/admin/allow-ip` token |
| `LOG_LEVEL_GATEWAY` | `DEBUG` | log level for `ru.mcs.aiproxy` |

**Control-plane:**

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8081` | HTTP port |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | local `ai_proxy`/`postgres`/`postgres` | Postgres connection |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis connection |
| `ADMIN_EMAILS` | empty | comma-separated emails; these users become admins on registration |
| `FREE_DAILY_LIMIT` / `FREE_MONTHLY_LIMIT` | `20` / `40` | free tier limits |
| `PRO_DAILY_LIMIT` / `PRO_MONTHLY_LIMIT` | `-1` / `-1` | `-1` = unlimited |
| `LOG_LEVEL_CONTROL_PLANE` | `DEBUG` | log level for `ru.mcs.controlplane` |

### How quotas work

- Only "model call" paths are counted (configurable `model-paths`: `/openai/v1/chat/completions`, `/openai/v1/responses`, `/anthropic/v1/messages`, `generateContent`). Streaming counts as one request; the request is counted at the start, so failed calls still consume quota.
- Free tier: block when **either** limit is exhausted — 20/day **or** 40/month. A `429` is returned with `X-RateLimit-*` headers.
- PRO (activated by an access code) is unlimited by default.
- After activating PRO, the gateway's local limits cache refreshes within ~60 seconds (TTL), so the change is not instant.

### Legacy IP allowlist mode

If a request does **not** carry `X-Gateway-Key`, the gateway falls back to the old IP allowlist (`ALLOWED_IPS` plus dynamic entries via `POST /admin/allow-ip` with `X-Admin-Token`). With an empty allowlist and gateway mode enabled, requests without a key get `403`.

## Run the tests

### Unit tests

```bash
./gradlew test
```

### End-to-end smoke tests (containerized, cross-platform)

A one-shot container `ai-proxy-smoke-tests` runs next to the stack, executes the tests, writes results to a shared volume, and exits.

```bash
cd deploy
docker compose --profile smoke run --rm smoke-tests
```

Or, to keep the named container for log inspection:

```bash
docker compose --profile smoke up --force-recreate smoke-tests
```

What the smoke test verifies: service health; admin + user registration; login; gateway key creation; auth failures (no key → 403, bad key → 401, unknown provider → 404); quota returning `429` exactly on call 21; PRO code activation making the user unlimited; Prometheus metrics being exposed.

### Where to view test results

1. **Container logs** (works even after the container has exited):
   ```bash
   docker compose logs smoke-tests
   ```
2. **Artifacts on the host**, bind-mounted from the container into `deploy/test-results/`:
   - `results.log` — human-readable output (`PASS=n FAIL=m`),
   - `results.xml` — JUnit XML, ready for CI (Jenkins/GitLab/etc.).
3. **Exit code**: `0` = all passed, `1` = at least one failure. `docker compose run` propagates it, so a CI job can rely on the exit code directly.

## Manual testing

### Web UI (control-plane)

Open http://localhost:8081, register (an email listed in `ADMIN_EMAILS`, e.g. `admin@example.com` in the default compose file, becomes an admin), log in, and use:
- `/dashboard` — create a gateway API key (the plaintext `ak_...` is shown only once), view usage, activate a subscription code;
- `/admin` — generate access codes, change user tiers.

### REST API (control-plane)

```bash
# register (admin@example.com becomes admin because it is in ADMIN_EMAILS)
curl -s -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"admin-pass-123"}'

# login, keep the session cookie
curl -s -c cookies.txt -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"admin-pass-123"}'

# create a gateway key (plaintext is returned once)
curl -s -b cookies.txt -X POST http://localhost:8081/api/keys

# current usage
curl -s -b cookies.txt http://localhost:8081/api/usage

# admin: generate a PRO access code
curl -s -b cookies.txt -X POST http://localhost:8081/api/admin/codes \
  -H "Content-Type: application/json" -d '{"count":1,"tier":"PRO"}'

# activate a code
curl -s -b cookies.txt -X POST http://localhost:8081/api/subscriptions/activate \
  -H "Content-Type: application/json" -d '{"code":"SUB-XXXX-XXXX-XXXX"}'
```

### Gateway (AI model requests)

Send the gateway key in `X-Gateway-Key` and your provider key in its usual place (`Authorization`, `x-api-key`, or `?key=`):

```bash
curl -s -X POST http://localhost:8080/openai/v1/chat/completions \
  -H "X-Gateway-Key: ak_..." \
  -H "Authorization: Bearer sk-..." \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}'
```

Expected statuses:

| Scenario | Result |
|---|---|
| Valid gateway key, model call | forwarded to the provider |
| No `X-Gateway-Key` | `403` (IP allowlist) |
| Invalid gateway key | `401` |
| Unknown provider path (e.g. `/nosuch/v1/x`) | `404` |
| Free tier quota exhausted | `429` + `X-RateLimit-*` headers |

### Observability

```bash
curl -s http://localhost:8080/actuator/prometheus | grep gateway_quota_exceeded_total
curl -s http://localhost:8081/actuator/prometheus | grep http_server_requests_seconds_count
```

Open Grafana at http://localhost:3000 (`admin`/`admin`) — the provisioned **AI Proxy** dashboard shows request rate, error rate, latency p95, quota exceeded, and invalid-key counters.

## Searching application logs in Grafana (Loki)

Promtail collects stdout of every container (gateway, control-plane, and infra) and stores it in Loki. To search logs in Grafana:

1. Open http://localhost:3000 (`admin`/`admin`).
2. Go to **Explore** (the compass icon) and select the **Loki** data source.
3. Use the label browser or write LogQL queries, for example:

```logql
{service="gateway"}                        # all gateway logs
{service="control-plane", level="ERROR"}   # errors only
{service="gateway"} |= "Quota exceeded"    # full-text filter
{service="gateway"} |= "Invalid gateway key"
```

Available labels: `service` (gateway, control-plane, postgres, redis, …), `container`, `level` (TRACE/DEBUG/INFO/WARN/ERROR), `project`.

Notes:
- Logs appear with a small delay — Promtail refreshes container targets every 5 seconds.
- Loki data persists in the `loki-data` volume.
- Logs can also be queried directly against Loki: `curl "http://localhost:3100/loki/api/v1/label/service/values"`.

## Distributed tracing (Tempo)

Both applications run with the **OpenTelemetry Java agent** (attached via `JAVA_TOOL_OPTIONS=-javaagent:...`), which auto-instruments Spring WebFlux/MVC, WebClient, Lettuce and JDBC. Spans are exported via OTLP to **Grafana Tempo**.

Every request that goes through the **gateway** produces one trace with a single `trace_id`: the inbound HTTP server span, the Redis calls, and the outbound call to the AI provider are all in that same trace. The same `trace_id` is also injected into the application logs, so you can correlate logs and traces.

A gateway request trace typically contains these spans (all under one `trace_id`):
- `POST /...` (server) — the inbound request;
- `gateway.key-resolve` — Redis lookup of the gateway key;
- `gateway.quota-consume` — Redis quota check;
- `POST` (client) — the outbound call to the AI provider.

### Viewing traces

1. Grafana → http://localhost:3000 (`admin`/`admin`).
2. **Explore** → select the **Tempo** data source and run TraceQL, for example:

```traceql
{ service.name = "gateway" }
{ service.name = "gateway" } && { http.route = "/openai/v1/chat/completions" }
```

Or use the **Service graph** (Tempo → Service map, backed by the `serviceMap` datasource).

### Correlating logs and traces

- In the log line, the trace id appears in square brackets: `[4a45b8b8f8adae16bd9ccaf30c4bb196]`.
- Loki exposes it as the `trace_id` label, so you can query logs of one trace:
  ```logql
  {service="gateway", trace_id="4a45b8b8f8adae16bd9ccaf30c4bb196"}
  ```
- In Grafana Explore (Loki) a log line has a **View trace** shortcut; in a Tempo trace you can jump back to the corresponding logs (tracesToLogs → Loki).

### Propagation

If a client sends the W3C `traceparent` header, the gateway joins that trace (the trace id continues from the caller); otherwise the gateway generates a new root trace.

> Note: on Windows PowerShell `curl` is an alias for `Invoke-WebRequest` — use `curl.exe`.
