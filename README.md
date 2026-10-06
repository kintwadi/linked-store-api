# Linked-Store Backend — Run as Executable JAR

Spring Boot 3.4 / Java 17 (release=17, builds on JDK 25) backend for the Hyperlocal
Omnichannel Retail Network **Linked-Store**. Postgres 15+ database, Cloudflare R2
image storage, Stripe payment + subscriptions.

This document only covers **running the pre-built JAR**. For the Maven-based
developer workflow (compile + run in one command), see section *Developer workflow*
at the bottom.

---

## 1. Build the JAR

From PowerShell or `cmd.exe`:

```powershell
cd backend
.\setup.bat mvn clean package -DskipTests
```

Output:

```
backend\target\linkedstore-1.0.0-SNAPSHOT.jar            ← executable Spring Boot JAR
backend\target\linkedstore-1.0.0-SNAPSHOT.jar.original   ← plain JAR (ignore)
```

`setup.bat` sets every credential env var before invoking Maven, so you never
need to copy/paste secrets into the terminal.

---

## 2. Prerequisites

| Component | Minimum | Notes |
|---|---|---|
| JDK | 17 | The JAR targets `release=17`; JDK 17, 21 or 25 all work at runtime. |
| PostgreSQL | 15+ | Database name `linked_store` on `localhost:5432`. |
| Cloudflare R2 account | n/a | All required keys are already in `setup.bat`. |
| Stripe account | n/a | Test mode keys + price IDs are already in `setup.bat`. |

---

## 3. Environment Configuration

Every credential and toggle is exposed as an **environment variable**.
`application.yml` resolves them with `${…}` placeholders — no secrets are ever
hard-coded inside the JAR.

### 3.1 Credentials (already provided in `setup.bat`)

| Variable | Purpose |
|---|---|
| `R2_ACCOUNT_ID` | Cloudflare R2 account id |
| `R2_ACCESS_KEY_ID` | R2 S3-compatible access key |
| `R2_SECRET_ACCESS_KEY` | R2 S3-compatible secret |
| `R2_BUCKET_NAME` | R2 bucket (default `share`) |
| `R2_ENDPOINT` | `https://<account>.r2.cloudflarestorage.com` |
| `R2_PUBLIC_URL` | `https://pub-….r2.dev` public bucket base URL |
| `STRIPE_PUBLIC_KEY` | Stripe publishable key (pk_…) |
| `STRIPE_SECRET_KEY` | Stripe secret key (sk_…) → used for `Stripe.apiKey` |
| `STRIPE_WEBHOOK_SECRET` | Stripe webhook signing secret (whsec_…) |
| `SUBSCRIPTION_PLUS_STRIPE_PRICE_ID` | Stripe `price_…` id for the Plus tier |
| `SUBSCRIPTION_PRO_STRIPE_PRICE_ID` | Stripe `price_…` id for the Pro tier |

### 3.2 Database

| Variable | Default if unset |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/linked_store` |
| `SPRING_DATASOURCE_USERNAME` | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` |

### 3.3 Runtime behavior

| Variable | Default | Meaning |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `default` | Set to `prod` in production. |
| `SERVER_PORT` | `8080` | HTTP listen port |

---

## 4. How to Run the JAR

### 4.1 Recommended one-liner (Windows cmd / PowerShell)

```powershell
cd backend
.\setup.bat java -jar target\linkedstore-1.0.0-SNAPSHOT.jar
```

`setup.bat` first exports all the env variables listed above, then execs the
`java -jar …` subcommand with those variables in the process block.

### 4.2 Two-step (useful when you want to set extra vars manually)

```cmd
cd backend
call setup.bat
REM — now all vars are set in this shell session —
set SERVER_PORT=9000
java -jar target\linkedstore-1.0.0-SNAPSHOT.jar
```

### 4.3 Production (secrets pre-loaded by your runner)

When your container / SCM / scheduler already injects the env variables
(12-factor style), you don't need `setup.bat` at all:

```bash
# Linux / container example
export R2_ACCOUNT_ID="…"
# … and the other 13 vars …
java -jar linkedstore-1.0.0-SNAPSHOT.jar
```

---

## 5. Database Schema

`spring.jpa.hibernate.ddl-auto=validate` is **enabled by default** (see
`application.yml`). The application refuses to boot unless every table and column
already exists in Postgres. Run this SQL once against `linked_store` before the
first start:

```sql
-- 8 tables defined in project-context.md (Hyperlocal Retail ERD)
-- + subscription catalog added for payment / subscription module
-- + image URL columns added on stores / products / product_variants

CREATE TABLE IF NOT EXISTS subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code VARCHAR(50) NOT NULL CONSTRAINT uk_subscription_plans_code UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    description VARCHAR(1024),
    price_cents INTEGER NOT NULL,
    interval_unit VARCHAR(20) NOT NULL DEFAULT 'MONTH',
    interval_count INTEGER NOT NULL DEFAULT 1,
    currency VARCHAR(10) NOT NULL DEFAULT 'usd',
    stripe_price_id VARCHAR(255),
    trial_days INTEGER DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    max_stores INTEGER, max_runners INTEGER, max_products INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL,
    plan_id UUID CONSTRAINT fk_subscriptions_plan REFERENCES subscription_plans(id),
    status VARCHAR(30) NOT NULL DEFAULT 'TRIALING',
    provider VARCHAR(20) NOT NULL DEFAULT 'STRIPE',
    provider_subscription_id VARCHAR(255),
    provider_customer_id VARCHAR(255),
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    trial_start TIMESTAMPTZ,
    trial_end TIMESTAMPTZ,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    canceled_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_subscriptions_store        ON subscriptions(store_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status       ON subscriptions(status);
CREATE INDEX IF NOT EXISTS idx_subscriptions_cancel_at_pe ON subscriptions(cancel_at_period_end);

ALTER TABLE stores ADD COLUMN IF NOT EXISTS active_subscription_id UUID
    CONSTRAINT fk_stores_active_subscription REFERENCES subscriptions(id);
ALTER TABLE stores           ADD COLUMN IF NOT EXISTS logo_url       VARCHAR(1024);
ALTER TABLE stores           ADD COLUMN IF NOT EXISTS hero_image_url VARCHAR(1024);
ALTER TABLE products         ADD COLUMN IF NOT EXISTS primary_image_url VARCHAR(1024);
ALTER TABLE products         ADD COLUMN IF NOT EXISTS thumbnail_url     VARCHAR(1024);
ALTER TABLE product_variants ADD COLUMN IF NOT EXISTS image_url         VARCHAR(1024);
```

After the first successful boot you'll see the Plus / Pro plans seeded into
`subscription_plans` automatically by [SubscriptionPlanSeeder.java](src/main/java/com/vicinity24/core/linkedstore/api/config/SubscriptionPlanSeeder.java).

---

## 6. Smoke Test the Running App

```powershell
# Image configuration (R2 config, max sizes, scopes, allowed types)
curl -s http://localhost:8080/api/images/config | ConvertFrom-Json

# Subscription public config (Stripe public key, 0% platform fee, tier list w/ Stripe price IDs)
curl -s http://localhost:8080/api/subscriptions/config | ConvertFrom-Json

# Full subscription tier catalog (Plus / Pro)
curl -s http://localhost:8080/api/subscriptions/plans | ConvertFrom-Json
```

All three endpoints are unauthenticated public read endpoints (they never leak
secret keys — only `STRIPE_PUBLIC_KEY`, tier metadata, and Stripe `price_…`
identifiers).

---

## 7. Feature Snapshot (what the JAR exposes)

### 7.1 Hyperlocal Retail flow endpoints

| Method | Path | Module | Purpose |
|---|---|---|---|
| POST | `/api/inventory/check-availability` | Inventory | 15-min 2-phase stock reservation, 30s sweep rollback, `FOR UPDATE SKIP LOCKED` |
| POST | `/api/checkout/pay` | Checkout | 0% platform fee, Stripe split ledger (wholesale→Fulfilling, margin→Originating) via PaymentProviderFactory |
| POST | `/api/fulfillment/verify-pickup` | Fulfillment | Closed-loop QR auth, SHA-256 tokens, atomic anti-replay mark-as-scanned |
| GET  | `/api/fulfillment/transactions/{id}/qr` | Fulfillment | QR payload + token readback |

### 7.2 Subscription + Payment module (factory pattern)

| Method | Path | Purpose |
|---|---|---|
| GET  | `/api/subscriptions/config` | Safe publishable key + tier catalog |
| GET  | `/api/subscriptions/plans` | Plus / Pro catalog with Stripe `price_…` ids |
| GET  | `/api/subscriptions/store/{id}` | Full subscription history of a store |
| GET  | `/api/subscriptions/store/{id}/current` | Current subscription (plan code, status, period end) |
| POST | `/api/subscriptions/store/{id}/purchase` | Pay via PaymentProviderFactory → persist Store FK → activate |
| POST | `/api/subscriptions/store/{id}/trial/{planCode}` | Activate free trial (one-time per store) |
| POST | `/api/subscriptions/store/{id}/cancel` | Cancel-at-period-end (keeps access until renewal) |

Payment is routed through the **provider factory**:

- `api.payment.PaymentProvider` — the SPI (add PayPal/M-Pesa/… by implementing one interface).
- `api.payment.PaymentProviderFactory` — Spring registry that picks a provider by id.
- `api.payment.impl.StripePaymentProvider` — the only implementation today; wraps
  `PaymentIntent.create` + `Transfer.create` (split ledger).
- `CheckoutService` and `SubscriptionService` both call the factory. They contain
  **zero raw `com.stripe.Stripe.*` calls**.

### 7.3 Cloudflare R2 image pipeline

| Method | Path | Scope |
|---|---|---|
| POST | `/api/images/upload?scope=store_logo\|product\|variant\|runner` | Generic multipart (validates 20MB cap, content-type, extension) |
| POST | `/api/images/store-logo` | Uploads store logo, sets `stores.logo_url` |
| POST | `/api/images/product` | Uploads product image, sets `products.primary_image_url` |
| POST | `/api/images/variant` | Uploads variant image, sets `product_variants.image_url` |
| DELETE | `/api/images/by-key` | Hard-delete by object key |
| GET | `/api/images/config` | Max size, allowed content types, valid scopes |

Uploaded keys: `stores/{storeId}/{scope}/{scopeId}/yyyy/MM/dd/{nonce}_{name}.{ext}`.
Returned public URLs: `${R2_PUBLIC_URL}/<key>` (all keys live in the shared bucket
named by `R2_BUCKET_NAME`).

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `SchemaManagementException: Schema-validation: missing column ...` | `ddl-auto=validate`; Postgres missing new image or subscription columns | Run the SQL in section 5. |
| `BeanCreationException CloudflareR2Config` | R2 env vars not exported | Run `setup.bat` before `java -jar`, or export vars yourself. |
| `R2_NOT_CONFIGURED` returned from `/api/images/upload` | `setup.bat` was not invoked for this process | Same fix as above. |
| `STRIPE_ERROR` / Stripe 401 on `/api/checkout/pay` | `STRIPE_SECRET_KEY` mismatch | Confirm the value in `setup.bat` and re-start; or set the env var in the shell and restart. |
| JVM exits with 1, Spring Banner never prints | Wrong JDK version | Run `java -version` — needs JDK 17+ (25 is fine). |

---

## 9. Docker & Render Deployment

Two ways to run the backend in production: (a) pre-built JAR, (b) the included
multi-stage **Dockerfile** (the default path for Render hosting).

### 9.1 Build the Docker image locally

```bash
docker build -t linked-store-api backend
```

The image is split in two stages:
- `Stage 1 (builder)` — `maven:3.9-eclipse-temurin-17`, runs
  `mvn -DskipTests package` then explodes the Spring Boot layered jar with
  `jarmode=layertools` so only the smallest layer (application code) rebuilds
  when Java sources change.
- `Stage 2 (runtime)` — `eclipse-temurin:17-jre-jammy`, runs as non-root
  `app` (uid 1001), exposes port 8080, sets container-friendly
  `-XX:MaxRAMPercentage=75`, `-XX:InitialRAMPercentage=40`,
  `-Djava.security.egd=file:/dev/./urandom`.

Run the image against a local Postgres:

```bash
docker run --rm -it --name api -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/linked_store \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=postgres \
  -e LINKEDSTORE_AUTH_JWT_SECRET='change-me-to-64-char-random-string!!OK' \
  linked-store-api
```

### 9.2 Render Deployment (Dashboard, 2 separate repos)

Backend and frontend live in **two separate GitHub repos**
(`linked-store-api` and `linked-store-client`), so they are deployed as
**two independent Render Web Services** plus one Render Postgres DB, all
provisioned from the Render Dashboard (no shared Blueprint YAML).

**Step 1 — Database**
- Dashboard → **PostgreSQL** → **New PostgreSQL**.
- Name = `linked-store-db`; Postgres Version = `16`; Plan = `Starter`;
  Region = `Oregon` (matches both web services).
- Click **Create Database** → wait until status = "Available".
- Copy these 3 values for **Step 2**: `Internal Database URL`, `Username`,
  `Password`.

**Step 2 — Backend Web Service**
- Dashboard → **Web Services** → **New Web Service** → connect repo
  `kintwadi/linked-store-api`, branch = `_home_dev`.
- Runtime = **Docker**; Dockerfile = `./Dockerfile` (at repo root).
- Plan = Starter (1); Region = Oregon; Health Check Path = `/`.
- **Environment → Add from .env (or paste these one by one):**
  - `SERVER_PORT=8080`
  - `SERVER_ADDRESS=0.0.0.0`
  - `SPRING_JPA_HIBERNATE_DDL_AUTO=update`
  - `SPRING_DATASOURCE_URL=<Internal Database URL from Step 1>`
  - `SPRING_DATASOURCE_USERNAME=<Username from Step 1>`
  - `SPRING_DATASOURCE_PASSWORD=<Password from Step 1>`
  - `LINKEDSTORE_AUTH_JWT_SECRET` → use **Generate** (Render feature) → a
    random 64+ char string is produced.
  - `LINKEDSTORE_AUTH_DEFAULT_ADMIN_PASSWORD` → also Generate.
  - `LINKEDSTORE_AUTH_ISSUER=linked-store`
  - `LINKEDSTORE_AUTH_ACCESS_TOKEN_MINUTES=30`
  - `LINKEDSTORE_AUTH_REFRESH_TOKEN_DAYS=7`
  - `LINKEDSTORE_AUTH_DEFAULT_ADMIN_EMAIL=admin@linked.store`
  - `LINKEDSTORE_PLATFORM_ROOT_ADMIN=true`
  - `SEED_CATALOG=false`
  - `API_BASE_ORIGIN=https://dinretail.com`
  - `API_BASE_URL=https://vicinity24api.com`
  - `LINKEDSTORE_API_PUBLIC_URL=https://vicinity24api.com`
  - `LINKEDSTORE_CONNECT_FRONTEND_RETURN_URL=https://dinretail.com/admin`
  - `LINKEDSTORE_CONNECT_FRONTEND_REFRESH_URL=https://dinretail.com/admin`
  - `LINKEDSTORE_BRAND_DISPLAY_NAME=Linked-Store`
- Paste the **SECRET** vars via an Env Group (recommended, single source of
  truth, secret-scoped):
  `STRIPE_SECRET_KEY`, `STRIPE_PUBLIC_KEY`, `STRIPE_WEBHOOK_SECRET`,
  `SUBSCRIPTION_PLUS_STRIPE_PRICE_ID`, `SUBSCRIPTION_PRO_STRIPE_PRICE_ID`,
  `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`,
  `MAIL_SMTP_SSL_ENABLE`, `MAIL_SMTP_STARTTLS_ENABLE`,
  `MAIL_SMTP_SSL_TRUST`, `MAIL_FROM`, `MAIL_CONTACT_SALES_TO`,
  `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`,
  `R2_BUCKET_NAME`, `R2_ENDPOINT`, `R2_PUBLIC_URL`,
  `LOCATION_IQ_API_KEY`, `LOCATION_IQ_BASE_URL`,
  `LOCATION_IQ_COUNTRYCODES`, `LOCATION_IQ_TIMEOUT_MS`.
  Use the corresponding values in `backend/render.env` as your paste
  checklist (do NOT commit that file to GitHub — just copy its contents
  into the Env Group editor).
- Click **Create Web Service**. Once it boots (green "Live"), go to
  Settings → **Custom Domains** → Add `vicinity24api.com` +
  `www.vicinity24api.com`. Follow Render's CNAME/ALIAS/APEX instructions
  for your DNS host. Wait until the page shows "Active → Connected"
  (TLS cert issued). Copy the final public URL — you need it for Step 3.

**Step 3 — Frontend Web Service** (same flow, using frontend repo):
- Web Service → connect repo `kintwadi/linked-store-client`, branch `main`.
- Runtime = **Docker**; Dockerfile = `./Dockerfile`; Plan = Starter.
- Env: `API_PROXY_URL=https://vicinity24api.com` (or, while Custom
  Domains are still provisioning, paste the backend service's
  `*.onrender.com` URL as a temporary fallback).
- Custom Domains → Add `dinretail.com` + `www.dinretail.com`.

### 9.3 Runtime Env Vars — Quick Reference

Every credential and toggle is exposed as an env var. `application.yml`
defaults are listed in parentheses.

| Variable | Scope |
|---|---|
| `SPRING_DATASOURCE_URL` | JDBC Postgres connection string |
| `SERVER_PORT` | HTTP port (8080) |
| `SERVER_ADDRESS` | Bind address (0.0.0.0) |
| `LINKEDSTORE_AUTH_JWT_SECRET` | HS256 signing key for access tokens (64 chars minimum) |
| `LINKEDSTORE_AUTH_ACCESS_TOKEN_MINUTES` | Access token TTL (30) |
| `LINKEDSTORE_AUTH_REFRESH_TOKEN_DAYS` | Refresh token TTL (7) |
| `LINKEDSTORE_PLATFORM_ROOT_ADMIN` | Seed GLOBAL_ADMIN on first boot? (true) |
| `SEED_CATALOG` | Seed demo catalog + demo stores? (false on Render) |
| `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` | Payment + webhook signature |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_SMTP_SSL_ENABLE` | Hostinger: `smtp.hostinger.com:465` + SSL |
| `R2_*` | Cloudflare R2 image storage (keys, endpoint, bucket, public URL) |
| `LOCATION_IQ_*` | Optional — store geocoding |
| `API_BASE_ORIGIN` | Frontend public origin (used for Stripe return URLs) |

---

## 10. Developer Workflow (shortcuts)

```powershell
cd backend

# 1. Build + skip tests
.\setup.bat mvn clean package -DskipTests

# 2. Build + run in a single command (no separate jar launch)
.\setup.bat mvn spring-boot:run

# 3. Plain Maven (setup.bat still auto-runs during initialize phase)
mvn clean package -DskipTests
mvn spring-boot:run

# 4. Disable the auto setup.bat hook for a specific build
mvn clean package -DskipTests -Dskip.setup.bat=true
```
