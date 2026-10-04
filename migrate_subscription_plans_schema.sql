-- =================================================================
--  Linked-Store Backend — Incremental Postgres Migration Script
--
--  Use this ONLY against an EXISTING database that was created from
--  an older version of schema.sql.  For fresh installations, run
--  `schema.sql` instead — it covers everything below AND is fully
--  idempotent (IF NOT EXISTS / ADD COLUMN IF NOT EXISTS everywhere).
--
--  Safe to re-run: all ALTER / CREATE statements below target
--  IF NOT EXISTS / DROP CONSTRAINT IF EXISTS first.
-- =================================================================

SET client_min_messages = WARNING;

-- -----------------------------------------------------------------
--  1. subscription_plans — annual / CUSTOM-plan merchandising cols
-- -----------------------------------------------------------------
ALTER TABLE subscription_plans ALTER COLUMN price_cents     DROP NOT NULL;
ALTER TABLE subscription_plans ALTER COLUMN interval_unit   DROP NOT NULL;
ALTER TABLE subscription_plans ALTER COLUMN interval_count  DROP NOT NULL;

ALTER TABLE subscription_plans
    ADD COLUMN IF NOT EXISTS annual_price_cents INTEGER,
    ADD COLUMN IF NOT EXISTS annual_discount_percent INTEGER,
    ADD COLUMN IF NOT EXISTS billing_label_monthly VARCHAR(120),
    ADD COLUMN IF NOT EXISTS billing_label_annual VARCHAR(160),
    ADD COLUMN IF NOT EXISTS max_connected_stores INTEGER,
    ADD COLUMN IF NOT EXISTS monthly_order_limit INTEGER,
    ADD COLUMN IF NOT EXISTS badges VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS is_contact_sales_enabled BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS contact_sales_email VARCHAR(254),
    ADD COLUMN IF NOT EXISTS contact_sales_url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS sort_order INTEGER DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_subscription_plans_active
    ON subscription_plans(is_active) WHERE is_active = TRUE;
CREATE INDEX IF NOT EXISTS idx_subscription_plans_order
    ON subscription_plans(sort_order);

-- -----------------------------------------------------------------
--  2. subscription_plan_features — plan → labelled feature chips
-- -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subscription_plan_features (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES subscription_plans(id) ON DELETE CASCADE,
    label VARCHAR(255) NOT NULL,
    included BOOLEAN NOT NULL DEFAULT TRUE,
    highlight BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_plan_features_order UNIQUE (plan_id, display_order)
);
CREATE INDEX IF NOT EXISTS idx_plan_features_plan
    ON subscription_plan_features(plan_id);

-- -----------------------------------------------------------------
--  3. stores — additional operational columns
-- -----------------------------------------------------------------
ALTER TABLE stores
    ADD COLUMN IF NOT EXISTS country_code  VARCHAR(2)  DEFAULT 'US',
    ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3)  DEFAULT 'USD',
    ADD COLUMN IF NOT EXISTS address       TEXT        DEFAULT '',
    ADD COLUMN IF NOT EXISTS postal_code   VARCHAR(32) DEFAULT '',
    ADD COLUMN IF NOT EXISTS gateway_code  VARCHAR(16);

-- Backfill gateway_code for any existing rows missing it (8-digit random)
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT id FROM stores WHERE gateway_code IS NULL OR gateway_code = '' LOOP
        UPDATE stores
        SET gateway_code = to_char(trunc(random() * 100000000), 'FM00000000')
        WHERE id = r.id;
    END LOOP;
END $$;

ALTER TABLE stores ALTER COLUMN gateway_code SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_stores_gateway_code ON stores(gateway_code);

CREATE INDEX IF NOT EXISTS idx_stores_subscription_status
    ON stores(subscription_status);

-- -----------------------------------------------------------------
--  4. store_users — complete UserAccount column set
-- -----------------------------------------------------------------
ALTER TABLE store_users
    ADD COLUMN IF NOT EXISTS email              VARCHAR(255),
    ADD COLUMN IF NOT EXISTS password_salt      VARCHAR(128),
    ADD COLUMN IF NOT EXISTS password_hash      VARCHAR(512),
    ADD COLUMN IF NOT EXISTS refresh_token_hash VARCHAR(512),
    ADD COLUMN IF NOT EXISTS is_global_admin    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_login_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_at         TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS idx_store_users_email ON store_users(email);

-- -----------------------------------------------------------------
--  5. store_invites (NEW — invite-token based store onboarding)
-- -----------------------------------------------------------------
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
CREATE INDEX IF NOT EXISTS idx_store_invites_token  ON store_invites(invite_token);
CREATE INDEX IF NOT EXISTS idx_store_invites_store  ON store_invites(store_id);
CREATE INDEX IF NOT EXISTS idx_store_invites_status ON store_invites(status);

-- -----------------------------------------------------------------
--  6. products + product_variants — gallery image + full status enum
-- -----------------------------------------------------------------
ALTER TABLE products
    ADD COLUMN IF NOT EXISTS gallery_image_urls JSONB DEFAULT '[]'::jsonb;

ALTER TABLE product_variants
    ADD COLUMN IF NOT EXISTS gallery_image_urls JSONB DEFAULT '[]'::jsonb;

ALTER TABLE product_variants
    DROP CONSTRAINT IF EXISTS uk_product_variants_store_sku;
ALTER TABLE product_variants
    ADD CONSTRAINT uk_product_variants_store_sku UNIQUE (store_id, sku);

-- -----------------------------------------------------------------
--  7. subscriptions — full enum coverage + provider UNIQUE partial
-- -----------------------------------------------------------------
ALTER TABLE subscriptions
    ALTER COLUMN status SET DEFAULT 'TRIALING';

CREATE UNIQUE INDEX IF NOT EXISTS uk_subscriptions_provider_id
    ON subscriptions(provider_subscription_id)
    WHERE provider_subscription_id IS NOT NULL;

-- -----------------------------------------------------------------
--  8. transactions — runner FK + RUNNER role index
-- -----------------------------------------------------------------
ALTER TABLE transactions
    ADD CONSTRAINT IF NOT EXISTS fk_transactions_runner
    FOREIGN KEY (runner_id) REFERENCES store_users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_transactions_runner ON transactions(runner_id);

-- -----------------------------------------------------------------
--  9. refunds (NEW) — Stripe payout reversal linked to a txn
-- -----------------------------------------------------------------
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

-- -----------------------------------------------------------------
--  10. returned_inspection_records (NEW) — post-refund QC
-- -----------------------------------------------------------------
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

-- -----------------------------------------------------------------
--  11. inventory_locks — full status enum + composite index
-- -----------------------------------------------------------------
ALTER TABLE inventory_locks
    ADD CONSTRAINT IF NOT EXISTS inventory_locks_status_check
    CHECK (status IN ('HELD','RELEASED','CONFIRMED','EXPIRED'));
CREATE INDEX IF NOT EXISTS idx_inventory_locks_expiry
    ON inventory_locks(expires_at, status);

-- -----------------------------------------------------------------
--  12. qr_tokens — runner FK + expiry index
-- -----------------------------------------------------------------
ALTER TABLE qr_tokens
    ADD CONSTRAINT IF NOT EXISTS fk_qr_tokens_runner
    FOREIGN KEY (runner_id) REFERENCES store_users(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_qr_tokens_runner ON qr_tokens(runner_id);
CREATE INDEX IF NOT EXISTS idx_qr_tokens_expiry ON qr_tokens(expires_at);
