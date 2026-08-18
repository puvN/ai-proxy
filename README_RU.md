# ai-proxy — AI Gateway as a Service

Лёгкий self-hosted AI-гейтвей из двух взаимодействующих сервисов:

- **gateway** (`gateway/`, Spring Boot WebFlux, порт **8080**) — быстрый reverse-proxy к AI-провайдерам (OpenAI, Anthropic, Gemini и любым другим с HTTP REST API). Аутентифицирует клиентов по заголовку `X-Gateway-Key`, считает квоты в Redis, проксирует запросы с твоими собственными API-ключами (pass-through, ключи не хранятся) и отдаёт метрики Prometheus.
- **control-plane** (`control-plane/`, Spring Boot MVC + Postgres, порт **8081**) — пользователи, gateway-ключи, коды доступа для подписки, аналитика использования, админ-интерфейс и REST API. Пишет ключи и лимиты в тот же Redis, откуда их читает gateway.

Всё поднимается локально в Docker Compose: Postgres, Redis, gateway, control-plane, Prometheus и Grafana.

## Структура репозитория

```
gateway/            # модуль прокси (WebFlux, Redis)
control-plane/      # модуль пользователей/ключей/подписок (MVC + JPA + Postgres)
deploy/             # docker-compose, конфиги Prometheus/Grafana, контейнерные smoke-тесты
plans/              # план архитектуры
```

## Запуск системы

Требования: Docker с Compose v2.

```bash
cd deploy
docker compose up -d --build
```

| Сервис | Адрес | Назначение |
|---|---|---|
| gateway | http://localhost:8080 | прокси; `/actuator/health`, `/actuator/prometheus` |
| control-plane | http://localhost:8081 | веб-интерфейс + REST API |
| Prometheus | http://localhost:9090 | собирает метрики с gateway и control-plane |
| Grafana | http://localhost:3000 | дашборд «AI Proxy» (логин `admin`/`admin`) |
| Loki | http://localhost:3100 | хранилище логов; Promtail доставляет в него логи всех контейнеров |
| Postgres | localhost:5432 | база `ai_proxy`, схема `public` |
| Redis | localhost:6379 | счётчики квот, gateway-ключи, лимиты |

Остановить стек (данные сохраняются):

```bash
docker compose down
```

С флагом `-v` дополнительно удаляется том Postgres (`ai-proxy-pgdata`).

### Конфигурация

Всё настраивается переменными окружения (см. `deploy/docker-compose.yml` и `deploy/.env.example`).

**Gateway:**

| Переменная | По умолчанию | Описание |
|---|---|---|
| `PORT` | `8080` | HTTP-порт |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / пусто | подключение к Redis |
| `PROVIDERS_JSON` | gemini+openai+anthropic | базовые URL провайдеров; каждый ключ становится префиксом пути, например `/openai/v1/...` |
| `GATEWAY_MODE` | `true` | включить аутентификацию по `X-Gateway-Key` |
| `GATEWAY_KEY_HEADER` | `X-Gateway-Key` | заголовок с gateway-ключом |
| `GATEWAY_FAIL_OPEN` | `false` | пропускать запросы, если не удалось проверить ключ |
| `QUOTA_FAIL_OPEN` | `true` | пропускать запросы, если недоступен сервис квот |
| `QUOTA_DAILY_LIMIT` | `20` | дневной лимит бесплатного тарифа |
| `QUOTA_MONTHLY_LIMIT` | `40` | месячный лимит бесплатного тарифа |
| `ALLOWED_IPS` / `ADMIN_TOKEN` | пусто | legacy IP-allowlist + токен `/admin/allow-ip` |
| `LOG_LEVEL_GATEWAY` | `DEBUG` | уровень логов для `ru.mcs.aiproxy` |

**Control-plane:**

| Переменная | По умолчанию | Описание |
|---|---|---|
| `PORT` | `8081` | HTTP-порт |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | локальная `ai_proxy`/`postgres`/`postgres` | подключение к Postgres |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | подключение к Redis |
| `ADMIN_EMAILS` | пусто | email'ы через запятую; эти пользователи становятся админами при регистрации |
| `FREE_DAILY_LIMIT` / `FREE_MONTHLY_LIMIT` | `20` / `40` | лимиты бесплатного тарифа |
| `PRO_DAILY_LIMIT` / `PRO_MONTHLY_LIMIT` | `-1` / `-1` | `-1` = безлимит |
| `LOG_LEVEL_CONTROL_PLANE` | `DEBUG` | уровень логов для `ru.mcs.controlplane` |

### Как считаются квоты

- Учитываются только «модельные» пути (настраиваемый список `model-paths`: `/openai/v1/chat/completions`, `/openai/v1/responses`, `/anthropic/v1/messages`, `generateContent`). Стриминг считается одним запросом; запрос засчитывается в момент старта, поэтому неуспешные вызовы тоже тратят квоту.
- Бесплатный тариф: блокировка при исчерпании **любого** лимита — 20/день **или** 40/мес. Возвращается `429` с заголовками `X-RateLimit-*`.
- PRO (активируется по коду доступа) по умолчанию безлимитный.
- После активации PRO gateway обновляет локальный кеш лимитов в течение ~60 секунд (TTL), поэтому изменение применяется не мгновенно.

### Legacy IP-allowlist

Если в запросе нет `X-Gateway-Key`, gateway переключается на старый IP-allowlist (`ALLOWED_IPS` + динамические записи через `POST /admin/allow-ip` с заголовком `X-Admin-Token`). При пустом allowlist и включённом gateway-режиме запросы без ключа получают `403`.

## Запуск тестов

### Юнит-тесты

```bash
./gradlew test
```

### End-to-end smoke-тесты (контейнерные, кросс-платформенные)

Одноразовый контейнер `ai-proxy-smoke-tests` запускается рядом со стеком, выполняет тесты, пишет результаты в общий volume и завершается.

```bash
cd deploy
docker compose --profile smoke run --rm smoke-tests
```

Либо, чтобы оставить именованный контейнер для просмотра логов:

```bash
docker compose --profile smoke up --force-recreate smoke-tests
```

Что проверяет smoke-тест: здоровье сервисов; регистрацию админа и пользователя; логин; создание gateway-ключа; ошибки аутентификации (без ключа → 403, плохой ключ → 401, неизвестный провайдер → 404); квоту с `429` ровно на 21-м вызове; активацию PRO-кода (тариф становится безлимитным); наличие метрик Prometheus.

### Где смотреть результаты тестов

1. **Логи контейнера** (доступны и после завершения контейнера):
   ```bash
   docker compose logs smoke-tests
   ```
2. **Файлы на хосте** — контейнер монтирует их в `deploy/test-results/`:
   - `results.log` — человекочитаемый вывод (`PASS=n FAIL=m`),
   - `results.xml` — JUnit XML, готов для CI (Jenkins/GitLab и т.п.).
3. **Exit code**: `0` — всё прошло, `1` — есть падения. `docker compose run` пробрасывает код возврата, поэтому CI может опираться на него напрямую.

## Ручное тестирование

### Веб-интерфейс (control-plane)

Открой http://localhost:8081, зарегистрируйся (email из `ADMIN_EMAILS`, например `admin@example.com` в дефолтном compose, станет админом), войди и используй:
- `/dashboard` — создать gateway-ключ (plaintext `ak_...` показывается один раз), посмотреть использование, активировать код подписки;
- `/admin` — генерировать коды доступа, менять тарифы пользователей.

### REST API (control-plane)

```bash
# регистрация (admin@example.com станет админом, т.к. он в ADMIN_EMAILS)
curl -s -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"admin-pass-123"}'

# логин, сохраняем сессионную куку
curl -s -c cookies.txt -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"admin-pass-123"}'

# создать gateway-ключ (plaintext вернётся один раз)
curl -s -b cookies.txt -X POST http://localhost:8081/api/keys

# текущее использование
curl -s -b cookies.txt http://localhost:8081/api/usage

# админ: сгенерировать PRO-код
curl -s -b cookies.txt -X POST http://localhost:8081/api/admin/codes \
  -H "Content-Type: application/json" -d '{"count":1,"tier":"PRO"}'

# активировать код
curl -s -b cookies.txt -X POST http://localhost:8081/api/subscriptions/activate \
  -H "Content-Type: application/json" -d '{"code":"SUB-XXXX-XXXX-XXXX"}'
```

### Gateway (запросы к моделям)

Gateway-ключ передаётся в `X-Gateway-Key`, а провайдерский — в его обычном месте (`Authorization`, `x-api-key` или `?key=`):

```bash
curl -s -X POST http://localhost:8080/openai/v1/chat/completions \
  -H "X-Gateway-Key: ak_..." \
  -H "Authorization: Bearer sk-..." \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}'
```

Ожидаемые статусы:

| Сценарий | Результат |
|---|---|
| Валидный gateway-ключ, модельный вызов | запрос форвардится провайдеру |
| Нет `X-Gateway-Key` | `403` (IP-allowlist) |
| Невалидный gateway-ключ | `401` |
| Неизвестный провайдер (например `/nosuch/v1/x`) | `404` |
| Квота бесплатного тарифа исчерпана | `429` + заголовки `X-RateLimit-*` |

### Наблюдаемость

```bash
curl -s http://localhost:8080/actuator/prometheus | grep gateway_quota_exceeded_total
curl -s http://localhost:8081/actuator/prometheus | grep http_server_requests_seconds_count
```

Открой Grafana http://localhost:3000 (`admin`/`admin`) — дашборд **AI Proxy** показывает request rate, ошибки 5xx, латентность p95, превышения квоты и счётчики невалидных ключей.

## Поиск логов приложений в Grafana (Loki)

Promtail собирает stdout каждого контейнера (gateway, control-plane и инфраструктуры) и складывает в Loki. Чтобы искать логи в Grafana:

1. Открой http://localhost:3000 (`admin`/`admin`).
2. Перейди в **Explore** (иконка компаса) и выбери источник данных **Loki**.
3. Используй браузер меток или пиши LogQL-запросы, например:

```logql
{service="gateway"}                        # все логи gateway
{service="control-plane", level="ERROR"}   # только ошибки
{service="gateway"} |= "Quota exceeded"    # полнотекстовый фильтр
{service="gateway"} |= "Invalid gateway key"
```

Доступные метки: `service` (gateway, control-plane, postgres, redis, …), `container`, `level` (TRACE/DEBUG/INFO/WARN/ERROR), `project`.

Замечания:
- Логи появляются с небольшой задержкой — Promtail обновляет список контейнеров каждые 5 секунд.
- Данные Loki хранятся в volume `loki-data`.
- Логи можно запрашивать напрямую из Loki: `curl "http://localhost:3100/loki/api/v1/label/service/values"`.

> Примечание: в PowerShell на Windows `curl` — алиас на `Invoke-WebRequest`; используй `curl.exe`.
