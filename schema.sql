-- ======================================================
--  Full schema for Linked-Store backend
--  Run once against the `linked_store` database.
-- ======================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ================== stores ==================
CREATE TABLE IF NOT EXISTS stores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name VARCHAR(255) NOT NULL,
    latitude DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL,
    stripe_connect_id VARCHAR(255) NOT NULL,
    subscription_status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    logo_url VARCHAR(1024),
    hero_image_url VARCHAR(1024),
    active_subscription_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ================== store_users ==================
CREATE TABLE IF NOT EXISTS store_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    phone_number VARCHAR(50) NOT NULL,
    email VARCHAR(255),
    pin_hash VARCHAR(255),
    api_key_hash VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE'
);

-- ================== products ==================
CREATE TABLE IF NOT EXISTS products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    primary_image_url VARCHAR(1024),
    thumbnail_url VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE
);

-- ================== product_variants ==================
CREATE TABLE IF NOT EXISTS product_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    sku VARCHAR(100) NOT NULL,
    wholesale_price_cents INT NOT NULL,
    retail_price_cents INT NOT NULL,
    stock_quantity INT NOT NULL DEFAULT 0,
    variant_attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    version INT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    image_url VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_positive_stock CHECK (stock_quantity >= 0)
);

-- ================== transactions ==================
CREATE TABLE IF NOT EXISTS transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    originating_store_id UUID NOT NULL REFERENCES stores(id),
    fulfilling_store_id UUID NOT NULL REFERENCES stores(id),
    stripe_payment_intent_id VARCHAR(255),
    total_retail_cents INT NOT NULL,
    wholesale_payout_cents INT NOT NULL,
    arbitrage_margin_cents INT NOT NULL,
    platform_fee_cents INT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL,
    runner_id UUID REFERENCES store_users(id),
    customer_name VARCHAR(255),
    customer_email VARCHAR(255),
    customer_phone VARCHAR(50),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_distinct_stores CHECK (originating_store_id != fulfilling_store_id)
);

-- ================== transaction_items ==================
CREATE TABLE IF NOT EXISTS transaction_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id),
    quantity INT NOT NULL DEFAULT 1,
    unit_wholesale_cents INT,
    unit_retail_cents INT
);

-- ================== inventory_locks ==================
CREATE TABLE IF NOT EXISTS inventory_locks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    store_id UUID NOT NULL REFERENCES stores(id),
    locked_quantity INT NOT NULL DEFAULT 1,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'HELD',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ================== qr_tokens ==================
CREATE TABLE IF NOT EXISTS qr_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    runner_id UUID NOT NULL REFERENCES store_users(id),
    secure_token VARCHAR(255) UNIQUE NOT NULL,
    fallback_code VARCHAR(16) UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    scanned_at TIMESTAMP WITH TIME ZONE DEFAULT NULL
);

-- ================== subscription_plans ==================
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
    max_stores INTEGER,
    max_runners INTEGER,
    max_products INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ
);

-- ================== subscriptions ==================
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

ALTER TABLE stores DROP CONSTRAINT IF EXISTS fk_stores_active_subscription;
ALTER TABLE stores
    ADD CONSTRAINT fk_stores_active_subscription
    FOREIGN KEY (active_subscription_id) REFERENCES subscriptions(id);

-- ================== indexes ==================
CREATE INDEX IF NOT EXISTS idx_stores_geolocation                ON stores (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_stores_subscription_status        ON stores(subscription_status);
CREATE INDEX IF NOT EXISTS idx_store_users_store                 ON store_users(store_id);
CREATE INDEX IF NOT EXISTS idx_store_users_role                  ON store_users(role);
CREATE INDEX IF NOT EXISTS idx_products_attributes_gin           ON products USING gin (attributes);
CREATE INDEX IF NOT EXISTS idx_products_status                   ON products(status);
CREATE INDEX IF NOT EXISTS idx_variants_attributes_gin           ON product_variants USING gin (variant_attributes);
CREATE INDEX IF NOT EXISTS idx_product_variants_product          ON product_variants(product_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_store            ON product_variants(store_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_sku              ON product_variants(sku);
CREATE INDEX IF NOT EXISTS idx_product_variants_status           ON product_variants(status);
CREATE INDEX IF NOT EXISTS idx_transactions_originating          ON transactions(originating_store_id);
CREATE INDEX IF NOT EXISTS idx_transactions_fulfilling           ON transactions(fulfilling_store_id);
CREATE INDEX IF NOT EXISTS idx_transactions_status               ON transactions(status);
CREATE INDEX IF NOT EXISTS idx_transactions_created              ON transactions(created_at);
CREATE INDEX IF NOT EXISTS idx_transaction_items_transaction     ON transaction_items(transaction_id);
CREATE INDEX IF NOT EXISTS idx_transaction_items_variant         ON transaction_items(variant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_locks_expiry            ON inventory_locks (expires_at, status);
CREATE INDEX IF NOT EXISTS idx_inventory_locks_variant           ON inventory_locks(variant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_locks_store             ON inventory_locks(store_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_qr_secure_token            ON qr_tokens (secure_token);
CREATE INDEX IF NOT EXISTS idx_qr_tokens_transaction             ON qr_tokens(transaction_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_store               ON subscriptions(store_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status              ON subscriptions(status);
CREATE INDEX IF NOT EXISTS idx_subscriptions_cancel_at_period_end ON subscriptions(cancel_at_period_end);
CREATE UNIQUE INDEX IF NOT EXISTS uk_subscriptions_provider_id   ON subscriptions(provider_subscription_id)
    WHERE provider_subscription_id IS NOT NULL;
