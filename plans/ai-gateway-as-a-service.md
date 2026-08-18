# План: ai-gateway-as-a-service на базе ai-proxy

## Целевая архитектура

```
                    ┌─────────────────────────────────────────────┐
                    │                  VPS / docker-compose       │
 client ──────────► │  gateway (ai-proxy, WebFlux, лёгкий JVM)    │
 (X-Gateway-Key +   │   ├─ GatewayKeyFilter  (валидация ключа)    │
  pass-through key) │   ├─ QuotaService      (Redis, атомарно)    │
                    │   └─ forward → провайдер (pass-through)     │
                    │              │  Redis (квоты, кеш лимитов)  │
                    │              ▼                              │
                    │  redis        postgres                      │
                    │    ▲            ▲                           │
                    │  control-plane (Spring MVC + JPA)  ────────┼── web UI + REST API
                    │  telegram-bot (Spring Boot + telegrambots)─┼── Telegram
                    └─────────────────────────────────────────────┘
```

- **gateway** — тот же лёгкий WebFlux-proxy, единственное новое внешнее взаимодействие — Redis (sub-ms). Никаких JDBC/JPA/UI, business-logic не тянет.
- **control-plane** — отдельный JVM: пользователи, ключи, коды доступа, подписки, аналитика. Полная изоляция от прокси.
- **telegram-bot** — отдельный JVM: управление ключами/квотой/подпиской через TG.

## 1. Реструктуризация в Gradle multi-module

```
ai-proxy/
├── build.gradle            ← агрегатор (java, dependencyManagement)
├── settings.gradle         ← include ':gateway', ':control-plane', ':telegram-bot'
├── gateway/                ← существующий код переезжает сюда (src/java/ru/mcs/aiproxy)
│     deps: webflux, actuator, jackson + spring-boot-starter-data-redis-reactive
├── control-plane/
│     deps: spring-boot-starter-web, thymeleaf, spring-data-jpa, postgres, flyway, spring-security, argon2/bcrypt
├── telegram-bot/
│     deps: spring-boot-starter-web + org.telegram:telegrambots
└── deploy/docker-compose.yml
```

Каждый модуль собирает свой `bootJar` → свой Docker-образ. Это гарантирует, что контрольная плоскость физически не может аффектить прокси (изоляция по JVM).

## 2. gateway — доработки (модуль остаётся лёгким)

**Идентификация (`GatewayKeyFilter`, WebFilter):**
- Читает `X-Gateway-Key`. Хэширует SHA-256, смотрит Redis `gateway:key:{hash}` → кеш `{userId, tier, limits}` (пишется control-plane, TTL ~1h).
- Нет ключа / невалидный → 401. Невалидный кешируется коротко (negative cache), чтобы не долбить Redis.
- Заголовок `X-Gateway-Key` вырезается перед upstream — провайдер его не видит. Провайдерские ключи (Authorization / x-api-key / ?key=) проходят транзитом без изменений и не логируются.
- **Backward-compat:** если `X-Gateway-Key` отсутствует — срабатывает прежний IP-allowlist (`AuthFilter`). Существующие клиенты не ломаются.

**Квота (`QuotaService`):**
- Считается **только для вызовов модели** по паттернам пути (конфигурируемый список): `/gemini/.../models/...:generateContent`, `/openai/v1/chat/completions`, `/anthropic/v1/messages`. Стриминг = 1 запрос, считается в момент начала (включая неуспешные).
- Атомарный Lua-скрипт по ключам `quota:{userId}:daily:{yyyy-MM-dd}` и `quota:{userId}:monthly:{yyyy-MM}`: INCR + установка TTL до конца дня/месяца в одном вызове.
- Лимиты free `{daily: 20, monthly: 40}` / подписчик `{unlimited}` берутся из кеша `gateway:user:{userId}:limits` (TTL ~1h, инвалидация DEL при смене тарифа). **Блок по первому исчерпанному** → 429 + JSON + заголовки `X-RateLimit-*`.
- **Fail-open:** при недоступности Redis запрос пропускается (лог + метрика). Флаг `app.quota.failOpen=true` для переключения.
- **Redis-задержка не блокирует** поток: полностью reactive (Lettuce).

**Конфиг `application.yml`:**
```yaml
app:
  gateway-mode: true          # включать ли проверку X-Gateway-Key
  quota:
    fail-open: true
    model-paths: [/openai/v1/chat/completions, /anthropic/v1/messages, ...]
  security: {enabled: true, ...}   # прежний IP-allowlist остаётся как fallback
```

## 3. control-plane — пользователи, ключи, коды, подписки

**БД (Postgres + Flyway):**
- `users(id, email, password_hash, tier[FREE|PRO], created_at)`
- `api_keys(id, user_id, key_hash, key_prefix, created_at, revoked_at)` — **plaintext не хранится**, SHA-256 хэш; показывается один раз при создании
- `access_codes(id, code, tier, status[NEW|CLAIMED], claimed_by, claimed_at, expires_at)`
- `subscriptions(id, user_id, tier, started_at, ends_at, source[CODE])`
- `usage_daily(id, user_id, date, requests)` — необязательный снапшот для аналитики (из Redis-счётчиков)

**REST API:**
- `POST /api/auth/register`, `POST /api/auth/login` (Spring Security + BCrypt, сессия/JWT)
- `POST /api/keys` → вернуть ключ один раз; `GET /api/keys` (префикс/статус); `DELETE /api/keys/{id}`
- `GET /api/usage` → текущие счётчики дня/месяца (читает Redis)
- `POST /api/subscriptions/activate {code}` → активация кода, обновление tier + инвалидация Redis-кеша лимитов
- `GET /api/me`
- Admin: `POST /api/admin/codes` (генерация), `GET /api/admin/users`, `PUT /api/admin/users/{id}/tier`

**Web UI (Thymeleaf, минимальный):** `/login`, `/register`, `/dashboard` (ключи + квота + активация кода), `/admin` (коды, пользователи). Без отдельного фронтенд-сборки.

**Синхронизация с Redis:** при создании ключа → `SET gateway:key:{hash}`; при активации кода/смене tier → `SET gateway:user:{id}:limits` + `DEL gateway:key:*`. Ревизия → `DEL`.

## 4. telegram-bot (фаза 1 — управление)

Команды: `/start`, `/register`, `/key` (создать gateway-ключ), `/usage`, `/subscribe <код>`, `/status`.
Бот — клиент REST API control-plane (не трогает gateway напрямую).

**Чат-релей (фаза 2, отдельно):** «отправь сообщение — бот сходит в модель через gateway». Требует решения по хранению провайдерского ключа пользователя — это единственное место, где возникает конфликт с «не хранить токены». Варианты: не хранить вовсе (пользователь шлёт ключ с каждым сообщением), либо шифровать в БД бота с явным согласием. Выносим в отдельную итерацию — фиксируем как открытый вопрос.

## 5. Деплой (VPS + docker-compose)

```yaml
services:
  redis:      redis:7-alpine
  postgres:   postgres:16-alpine + volume
  gateway:      build ./gateway      → :8080
  control-plane: build ./control-plane → :8081
  bot:        build ./telegram-bot   → (profile off по умолчанию)
  caddy:      TLS-терминация → gateway/control-plane (acme)
```
- Каждый модуль: свой `Dockerfile` от общей build-стадии (multi-stage, собираем все jars сразу).
- Заменяем `render.yaml`/README на инструкцию VPS; прежний деплой на Render можно оставить для gateway в legacy-режиме (только IP-allowlist, без Redis).

## 6. Этапы (майлстоуны)

| Этап | Содержание | Результат |
|---|---|---|
| M1 | Restructure → `:gateway`; `GatewayKeyFilter` + `QuotaService` (Redis, Lua, fail-open); backward-compat IP-режим; unit-тесты квоты/фильтра | Лёгкий прокси с квотами |
| M2 | `:control-plane`: БД + Flyway, REST API, Spring Security, Thymeleaf (dashboard/admin), коды доступа | Веб-сервис + API |
| M3 | `:telegram-bot`: регистрация, ключи, usage, подписка | TG-интерфейс |
| M4 | docker-compose + Caddy/TLS, README, миграция legacy-клиентов | Прод-деплой |
| P2 | бот-чат-релей, платёжки (Stars/YooKassa), аналитика | Опционально |

## Открытые вопросы для следующих итераций
- Чат-релей в Telegram: судьба провайдерских ключей в боте (P2).
- Нужен ли лимит на подписчиков (например, 1000/день) или честный unlimited.
- Реальная платёжка вместо кодов (P2).
