-- =============================================================
--  Linked-Store Backend — Complete PostgreSQL Schema
--  Last updated 2026-10-04.
--
--  Run this ONCE against a Postgres 15+ database named
--  `linked_store` BEFORE the first `java -jar` / Docker start.
--
--  Spring Boot uses `spring.jpa.hibernate.ddl-auto=update` in
--  application.yml, so once all columns below exist, Hibernate
--  will safely track new ones; re-running this file is safe as
--  every statement is `IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`.
-- =============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =============================================================
--  1. stores
-- =============================================================
CREATE TABLE IF NOT EXISTS stores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name VARCHAR(255) NOT NULL,
    latitude DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL,
    country_code VARCHAR(2) DEFAULT 'US',
    currency_code VARCHAR(3) DEFAULT 'USD',
    stripe_connect_id VARCHAR(255) NOT NULL,
    subscription_status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    logo_url VARCHAR(1024),
    hero_image_url VARCHAR(1024),
    address TEXT DEFAULT '',
    postal_code VARCHAR(32) DEFAULT '',
    gateway_code VARCHAR(16) NOT NULL UNIQUE,
    active_subscription_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================
--  2. store_users  (reused as `UserAccount` table — JPA entity
--     binds to this same legacy name; all roles, incl. global
--     admins, store owners, clerks, runners live here.)
-- =============================================================
CREATE TABLE IF NOT EXISTS store_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID REFERENCES stores(id) ON DELETE CASCADE,

    name VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'CLERK'
        CHECK (role IN (
            'GLOBAL_ADMIN','OWNER','STORE_ADMIN','STORE_REPRESENTATIVE',
            'CLERK','RUNNER'
        )),
    phone_number VARCHAR(50) DEFAULT '',
    email VARCHAR(255) UNIQUE,

    -- password credentials (salted + hashed via PasswordService — BCrypt)
    password_salt VARCHAR(128),
    password_hash VARCHAR(512),
    refresh_token_hash VARCHAR(512),

    -- global admin toggle (role + this flag are both checked)
    is_global_admin BOOLEAN NOT NULL DEFAULT FALSE,

    -- lifecycle
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','SUSPENDED','INVITED','LOCKED')),
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_store_users_store   ON store_users(store_id);
CREATE INDEX IF NOT EXISTS idx_store_users_role    ON store_users(role);
CREATE INDEX IF NOT EXISTS idx_store_users_status  ON store_users(status);
CREATE INDEX IF NOT EXISTS idx_store_users_email   ON store_users(email);

-- =============================================================
--  3. store_invites — invite tokens to join a store under a role
-- =============================================================
CREATE TABLE IF NOT EXISTS store_invites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    invite_token VARCHAR(64) NOT NULL UNIQUE,
    target_role VARCHAR(50) NOT NULL
        CHECK (target_role IN (
            'STORE_ADMIN','STORE_REPRESENTATIVE','CLERK','RUNNER'
        )),
    prefill_email VARCHAR(255),
    created_by UUID NOT NULL REFERENCES store_users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    redeemed_by UUID REFERENCES store_users(id) ON DELETE SET NULL,
    redeemed_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','REDEEMED','EXPIRED','REVOKED'))
);

CREATE INDEX IF NOT EXISTS idx_store_invites_token   ON store_invites(invite_token);
CREATE INDEX IF NOT EXISTS idx_store_invites_store   ON store_invites(store_id);
CREATE INDEX IF NOT EXISTS idx_store_invites_status  ON store_invites(status);

-- =============================================================
--  4. products  (catalog-level; variants hold store-specific pricing)
-- =============================================================
CREATE TABLE IF NOT EXISTS products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN (
            'ACTIVE','DRAFT','PENDING','OUT_OF_STOCK','INACTIVE','ARCHIVED'
        )),
    primary_image_url VARCHAR(1024),
    thumbnail_url VARCHAR(1024),
    gallery_image_urls JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_products_attributes_gin ON products USING gin(attributes);
CREATE INDEX IF NOT EXISTS idx_products_status         ON products(status);

-- =============================================================
--  5. product_variants  (store SKUs — stock + prices live here)
-- =============================================================
CREATE TABLE IF NOT EXISTS product_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    store_id   UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,

    sku VARCHAR(100),
    wholesale_price_cents INT NOT NULL,
    retail_price_cents    INT NOT NULL,
    stock_quantity        INT NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),

    variant_attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    version INT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','OUT_OF_STOCK','INACTIVE','DISCONTINUED')),

    image_url VARCHAR(1024),
    gallery_image_urls JSONB DEFAULT '[]'::jsonb,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,

    CONSTRAINT uk_product_variants_store_sku UNIQUE (store_id, sku)
);

CREATE INDEX IF NOT EXISTS idx_variants_attributes_gin ON product_variants USING gin(variant_attributes);
CREATE INDEX IF NOT EXISTS idx_product_variants_product ON product_variants(product_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_store   ON product_variants(store_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_sku     ON product_variants(sku);
CREATE INDEX IF NOT EXISTS idx_product_variants_status  ON product_variants(status);

-- =============================================================
--  6. subscription_plans  (DB-first tier catalog)
-- =============================================================
CREATE TABLE IF NOT EXISTS subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code VARCHAR(50) NOT NULL
        CONSTRAINT uk_subscription_plans_code UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    description VARCHAR(1024),

    -- monthly billing (nullable for CUSTOM / quote-only plans)
    price_cents INTEGER,
    interval_unit VARCHAR(20) DEFAULT 'MONTH' CHECK (interval_unit IN ('MONTH','YEAR','WEEK','DAY')),
    interval_count INTEGER DEFAULT 1,
    currency VARCHAR(10) DEFAULT 'usd',
    stripe_price_id VARCHAR(255),
    trial_days INTEGER DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    -- quota features (NULL means "Unlimited")
    max_stores             INTEGER,
    max_runners            INTEGER,
    max_products           INTEGER,
    max_connected_stores   INTEGER,
    monthly_order_limit    INTEGER,

    -- upmarket / annual pricing
    annual_price_cents         INTEGER,
    annual_discount_percent    INTEGER,
    billing_label_monthly VARCHAR(120),
    billing_label_annual  VARCHAR(160),

    -- merchandising
    badges VARCHAR(1024),
    is_contact_sales_enabled BOOLEAN DEFAULT FALSE,
    contact_sales_email VARCHAR(254),
    contact_sales_url VARCHAR(512),
    sort_order INTEGER DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_subscription_plans_active ON subscription_plans(is_active) WHERE is_active = TRUE;
CREATE INDEX IF NOT EXISTS idx_subscription_plans_order  ON subscription_plans(sort_order);

-- =============================================================
--  7. subscription_plan_features  (child feature chips —
--     one plan -> many labelled rows, with include/highlight flags)
-- =============================================================
CREATE TABLE IF NOT EXISTS subscription_plan_features (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES subscription_plans(id) ON DELETE CASCADE,
    label VARCHAR(255) NOT NULL,
    included BOOLEAN NOT NULL DEFAULT TRUE,
    highlight BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_plan_features_order UNIQUE (plan_id, display_order)
);
CREATE INDEX IF NOT EXISTS idx_plan_features_plan ON subscription_plan_features(plan_id);

-- =============================================================
--  8. subscriptions  (per-store lifecycle record; provider = Stripe)
-- =============================================================
CREATE TABLE IF NOT EXISTS subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    plan_id UUID CONSTRAINT fk_subscriptions_plan REFERENCES subscription_plans(id),

    status VARCHAR(30) NOT NULL DEFAULT 'TRIALING'
        CHECK (status IN (
            'TRIALING','ACTIVE','PAST_DUE','UNPAID','CANCELED','INACTIVE','EXPIRED'
        )),
    provider VARCHAR(20) NOT NULL DEFAULT 'STRIPE'
        CHECK (provider IN ('STRIPE','MANUAL','INVOICE')),
    provider_subscription_id VARCHAR(255),
    provider_customer_id     VARCHAR(255),

    current_period_start TIMESTAMPTZ,
    current_period_end   TIMESTAMPTZ,
    trial_start          TIMESTAMPTZ,
    trial_end            TIMESTAMPTZ,

    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    canceled_at  TIMESTAMPTZ,
    ended_at     TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_store           ON subscriptions(store_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status          ON subscriptions(status);
CREATE INDEX IF NOT EXISTS idx_subscriptions_cancel_at_pe    ON subscriptions(cancel_at_period_end);
CREATE UNIQUE INDEX IF NOT EXISTS uk_subscriptions_provider_id
    ON subscriptions(provider_subscription_id)
    WHERE provider_subscription_id IS NOT NULL;

-- wire the stores.active_subscription_id FK (deferred because of circular dep)
ALTER TABLE stores DROP CONSTRAINT IF EXISTS fk_stores_active_subscription;
ALTER TABLE stores
    ADD CONSTRAINT fk_stores_active_subscription
    FOREIGN KEY (active_subscription_id) REFERENCES subscriptions(id);

-- =============================================================
--  9. transactions  (cross-store order ledger)
-- =============================================================
CREATE TABLE IF NOT EXISTS transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    originating_store_id UUID NOT NULL REFERENCES stores(id),
    fulfilling_store_id UUID NOT NULL REFERENCES stores(id),
    stripe_payment_intent_id VARCHAR(255),
    total_retail_cents     INT NOT NULL,
    wholesale_payout_cents INT NOT NULL,
    arbitrage_margin_cents INT NOT NULL,
    platform_fee_cents     INT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL
        CHECK (status IN (
            'CREATED','AWAITING_PAYMENT','PAID','PROCESSING',
            'READY_FOR_PICKUP','IN_TRANSIT','DELIVERED','COMPLETED',
            'CANCELLED','REFUNDED','FAILED'
        )),
    runner_id UUID REFERENCES store_users(id) ON DELETE SET NULL,
    customer_name  VARCHAR(255),
    customer_email VARCHAR(255),
    customer_phone VARCHAR(50),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_distinct_stores CHECK (originating_store_id <> fulfilling_store_id)
);

CREATE INDEX IF NOT EXISTS idx_transactions_originating ON transactions(originating_store_id);
CREATE INDEX IF NOT EXISTS idx_transactions_fulfilling  ON transactions(fulfilling_store_id);
CREATE INDEX IF NOT EXISTS idx_transactions_status      ON transactions(status);
CREATE INDEX IF NOT EXISTS idx_transactions_created     ON transactions(created_at);
CREATE INDEX IF NOT EXISTS idx_transactions_runner      ON transactions(runner_id);

-- =============================================================
--  10. transaction_items
-- =============================================================
CREATE TABLE IF NOT EXISTS transaction_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    variant_id     UUID NOT NULL REFERENCES product_variants(id),
    quantity       INT NOT NULL DEFAULT 1,
    unit_wholesale_cents INT,
    unit_retail_cents    INT
);
CREATE INDEX IF NOT EXISTS idx_transaction_items_transaction ON transaction_items(transaction_id);
CREATE INDEX IF NOT EXISTS idx_transaction_items_variant     ON transaction_items(variant_id);

-- =============================================================
--  11. refunds (Stripe-side payout reversal, linked to a txn)
-- =============================================================
CREATE TABLE IF NOT EXISTS refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SUCCEEDED','FAILED','CANCELED')),
    total_refunded_cents     INT NOT NULL,
    reversed_fulfiller_cents INT,
    reversed_originator_cents INT,
    currency VARCHAR(3) DEFAULT 'usd',
    stripe_refund_id VARCHAR(255),
    stripe_error TEXT,
    reason VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    created_by_user_id  VARCHAR(255),
    created_by_store_id VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_refunds_transaction_id ON refunds(transaction_id);
CREATE INDEX IF NOT EXISTS idx_refunds_status         ON refunds(status);

-- =============================================================
--  12. returned_inspection_records (post-refund QC on variants)
-- =============================================================
CREATE TABLE IF NOT EXISTS returned_inspection_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id      UUID REFERENCES refunds(id)             ON DELETE SET NULL,
    transaction_id UUID REFERENCES transactions(id)        ON DELETE SET NULL,
    variant_id     UUID REFERENCES product_variants(id)    ON DELETE SET NULL,
    product_id     UUID REFERENCES products(id)            ON DELETE SET NULL,
    fulfilling_store_id   UUID REFERENCES stores(id)       ON DELETE SET NULL,
    originating_store_id  UUID REFERENCES stores(id)       ON DELETE SET NULL,
    quantity INT NOT NULL DEFAULT 1,
    status VARCHAR(50) NOT NULL DEFAULT 'UNDER_INSPECTION'
        CHECK (status IN (
            'UNDER_INSPECTION','PASSED_INSPECTION','REJECTED','RESTOCKED'
        )),
    notes VARCHAR(2000),
    inspected_by_user_id  VARCHAR(255),
    inspected_by_store_id VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    inspected_at TIMESTAMPTZ,
    version INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_rir_refund_id      ON returned_inspection_records(refund_id);
CREATE INDEX IF NOT EXISTS idx_rir_tx_id          ON returned_inspection_records(transaction_id);
CREATE INDEX IF NOT EXISTS idx_rir_variant_id     ON returned_inspection_records(variant_id);
CREATE INDEX IF NOT EXISTS idx_rir_fulfill_store  ON returned_inspection_records(fulfilling_store_id);
CREATE INDEX IF NOT EXISTS idx_rir_origin_store   ON returned_inspection_records(originating_store_id);
CREATE INDEX IF NOT EXISTS idx_rir_status         ON returned_inspection_records(status);

-- =============================================================
--  13. inventory_locks  (2-phase stock reservation)
-- =============================================================
CREATE TABLE IF NOT EXISTS inventory_locks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id),
    store_id UUID NOT NULL REFERENCES stores(id),
    locked_quantity INT NOT NULL DEFAULT 1,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'HELD'
        CHECK (status IN ('HELD','RELEASED','CONFIRMED','EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_inventory_locks_expiry  ON inventory_locks(expires_at, status);
CREATE INDEX IF NOT EXISTS idx_inventory_locks_variant ON inventory_locks(variant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_locks_store   ON inventory_locks(store_id);

-- =============================================================
--  14. qr_tokens  (runner pickup QR, closed-loop scanned-once)
-- =============================================================
CREATE TABLE IF NOT EXISTS qr_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    runner_id      UUID NOT NULL REFERENCES store_users(id)   ON DELETE CASCADE,
    secure_token   VARCHAR(255) UNIQUE NOT NULL,
    fallback_code  VARCHAR(16)  UNIQUE,
    expires_at     TIMESTAMPTZ NOT NULL,
    scanned_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_qr_secure_token    ON qr_tokens(secure_token);
CREATE INDEX IF NOT EXISTS idx_qr_tokens_transaction     ON qr_tokens(transaction_id);
CREATE INDEX IF NOT EXISTS idx_qr_tokens_runner          ON qr_tokens(runner_id);
CREATE INDEX IF NOT EXISTS idx_qr_tokens_expiry          ON qr_tokens(expires_at);

-- =============================================================
--  15. store geolocation coverage index (used by store-search)
-- =============================================================
CREATE INDEX IF NOT EXISTS idx_stores_gps ON stores(latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_stores_subscription_status ON stores(subscription_status);
