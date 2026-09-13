# Project Summary: Hyperlocal Omnichannel Retail Network (Linked-Store)

## 1. Project Description
The **Hyperlocal Omnichannel Retail Network (Linked-Store)** is a B2B2C retail utility infrastructure application designed to convert fragmented, independent storefronts into a unified, cross-promoting retail ecosystem. 

Conventionally, independent small shops operate as standalone entities with isolated inventories and Point of Sale (POS) setups. When a customer cannot find a specific item (e.g., a specific sneaker size or color) in a shop, they typically leave empty-handed. Linked-Store solves this "lost-sale" dilemma by offering an anonymous **"endless aisle"** platform using standard PostgreSQL database structures and real-time physical confirmations.

### Core Mechanics:
* **Anonymity:** When a customer scans a QR code on a shelf in a store, they browse matching or alternative inventories located within a tight walking radius. The identity of the neighboring supplying merchant is completely hidden from the consumer to protect the merchants' customer footprints.
* **Instant B2B Arbitrage:** When the customer purchases an item, the hosting shop instantly buys the product at a pre-negotiated **Wholesale Price** from the neighboring supplier and sells it at **Market Retail Price** to the customer. The hosting shop acts as the transaction host, capitalizing on their foot traffic without upfront inventory risk. The supplying shop liquidates stock efficiently without spending marketing dollars.
* **Monetization Model:** The platform operates strictly on a **Subscription-Based SaaS Model** (fixed monthly fee per storefront). It charges **0% transaction commission fees**, allowing merchants to retain 100% of their negotiated arbitrage margins.

---

## 2. Complete Transaction Flow

The transaction lifecycle utilizes a specialized **Two-Phase Commit for Physical Logistics** alongside a **Closed-Loop Custody Transfer** to eliminate inventory latency and prevent fraud.

```text
CUSTOMER (at Host Store)          ORIGINATING STAFF               SYSTEM / PLATFORM              FULFILLING STAFF
     │                                 │                                 │                                 │
     │ 1. Scans Shelf QR Code          │                                 │                                 │
     ├─────────────────────────────────┼────────────────────────────────>│                                 │
     │ 2. Taps "Check Availability"    │                                 │                                 │
     ├─────────────────────────────────┼────────────────────────────────>│ 3. Dispatches Reservation Alert │
     │                                 │                                 ├────────────────────────────────>│
     │                                 │                                 │                                 │ 4. Locates item variant
     │                                 │                                 │                                 │    & pulls off display
     │                                 │                                 │ 5. Taps "Reserve" (Hold state)  │
     │                                 │                                 |<────────────────────────────────┤
     │ 6. Receives "Available" Message │                                 │                                 │
     │    & 15-Minute Countdown Timer  │                                 │                                 │
     |<────────────────────────────────┼─────────────────────────────────┤                                 │
     │                                 │                                 │                                 │
     │ 7. Clicks "Buy Now" & Pays      │                                 │                                 │
     ├─────────────────────────────────┼────────────────────────────────>│ 8. Executes Stripe Split Ledger │
     │                                 │                                 │    - Wholesale to Fulfilling    │
     │                                 │                                 │    - Margin to Originating      │
     │                                 │ 9. Receives Pickup Order        │                                 │
     │                                 │    & Secure Dynamic QR Token    │                                 │
     │                                 │<────────────────────────────────┤                                 │
     │                                 │                                 │                                 │
     │                                 │ 10. Physical Walk to Supplier   │                                 │
     │                                 ├──────────────────────────────────────────────────────────────────>│
     │                                 │                                 │                                 │ 11. Scans Originating QR
     │                                 │                                 │ 12. Validates Secure Custody    │ via Merchant App
     │                                 │                                 │<────────────────────────────────┤
     │                                 │ 13. Settles Ledger From Escrow  │
     │                                 │                                 ├────────────────────────────────>│
     │                                 │ 14. Hands over product asset    │                                 │
     │                                 │<──────────────────────────────────────────────────────────────────┤
     │                                 │                                 │                                 │
     │ 15. Customer receives product   │                                 │                                 │
     │     at Host Store's counter     │                                 │                                 │
     |<────────────────────────────────┤                                 │                                 │
```

---

## 3. Database Schema (PostgreSQL Optimized)

The database utilizes a relational parent-child abstraction layer paired with native `jsonb` fields to handle complex, multi-attribute product variations. Explicit indexes ensure fast local geospatial processing and instant reservation checks.

### Entity-Relationship Diagram (ERD)
```
┌──────────────┐         ┌────────────────┐         ┌───────────────────┐
│    Stores    │1     *  │    Products    │1     *  │  Product_Variants  │
│  (Merchant)  ├────────>│ (JSONB Metadata├────────>│ (Specific SKU/Size)│
└──────┬───────┘         └────────────────┘         └─────────┬─────────┘
       │ 1                                                    │ 1
       │                                                      │
       │ *                                                    │ *
┌──────▼───────┐         ┌────────────────┐         ┌─────────▼─────────┐
│ Store_Users  │         │  Transactions  │1     *  │  Inventory_Locks  │
│  (Employees) │         │(B2B2C Arbitrage├────────>│(15-Min Var. Holds)│
└──────┬───────┘         └───────▲────────┘         └───────────────────┘
       │ 1                       │ 1
       │ *                       │ *
┌──────▼───────┐         ┌───────┴────────┐
│  QR_Tokens   │*       1│  Transaction_  │
│ (Secure Code)├────────>│     Items      │
└──────────────┘         └────────────────┘
```

### Table Layout Definitions

#### `stores`
```sql
CREATE TABLE stores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name VARCHAR(255) NOT NULL,
    latitude DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL,
    stripe_connect_id VARCHAR(255) NOT NULL,
    subscription_status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, PAST_DUE, CANCELED
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

#### `store_users`
```sql
CREATE TABLE store_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL, -- OWNER, CLERK, RUNNER
    phone_number VARCHAR(50) NOT NULL
);
```

#### `products`
Acts as the global parent catalog layer. Unstructured generic metadata (brand, gender, category) is nested within the `attributes` column to maintain schema flexibility.
```sql
CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb, -- e.g., {"brand": "Nike", "category": "running"}
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

#### `product_variants`
Manages granular physical inventory elements. Every distinct size, color, or style variation has its own tracking parameters and direct link to the supplying storefront.
```sql
CREATE TABLE product_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE, -- Identity masked from frontend
    sku VARCHAR(100) NOT NULL,
    wholesale_price_cents INT NOT NULL, -- Payout value for Fulfilling Store
    retail_price_cents INT NOT NULL,    -- Purchase value for Consumer inside Originating Store
    stock_quantity INT NOT NULL DEFAULT 0,
    variant_attributes JSONB NOT NULL DEFAULT '{}'::jsonb, -- e.g., {"size": "10.5", "color": "Red"}
    version INT NOT NULL DEFAULT 0, -- Leveraged for Optimistic Locking handling race conditions
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_positive_stock CHECK (stock_quantity >= 0)
);
```

#### `transactions`
Models the lifecycle of the exchange. Column names explicitly mirror domain roles (`originating_store_id` vs `fulfilling_store_id`) rather than arbitrary lettering.
```sql
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    originating_store_id UUID NOT NULL REFERENCES stores(id), -- The footprint host capturing the consumer footprint
    fulfilling_store_id UUID NOT NULL REFERENCES stores(id),  -- The neighboring merchant supplying the product asset
    stripe_payment_intent_id VARCHAR(255),
    total_retail_cents INT NOT NULL,
    wholesale_payout_cents INT NOT NULL,
    arbitrage_margin_cents INT NOT NULL,
    status VARCHAR(50) NOT NULL, -- PENDING_RESERVATION, RESERVED, PAID, PICKED_UP, EXPIRED, CANCELED
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Safety constraint to prevent an infinite routing bug where a store buys from itself
    CONSTRAINT chk_distinct_stores CHECK (originating_store_id != fulfilling_store_id)
);
```

#### `transaction_items`
```sql
CREATE TABLE transaction_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id), -- Binds strictly to specific size variant
    quantity INT NOT NULL DEFAULT 1
);
```

#### `inventory_locks`
```sql
CREATE TABLE inventory_locks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    store_id UUID NOT NULL REFERENCES stores(id), -- The Fulfilling store holding the asset
    locked_quantity INT NOT NULL DEFAULT 1,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL, -- Enforces the 15-minute countdown clock
    status VARCHAR(50) NOT NULL DEFAULT 'HELD', -- HELD, RELEASED_TO_SALE, RELEASED_TO_STOCK
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

#### `qr_tokens`
```sql
CREATE TABLE qr_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    runner_id UUID NOT NULL REFERENCES store_users(id),
    secure_token VARCHAR(255) UNIQUE NOT NULL, -- Hashed payload embedded inside the pickup image
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    scanned_at TIMESTAMP WITH TIME ZONE DEFAULT NULL
);
```

### Performance Optimization & GIN Indexing Rules
```sql
-- High-speed geospatial computations for local distance matrix lookups
CREATE INDEX idx_stores_geolocation ON stores (latitude, longitude);

-- GIN index on parent product attributes for fast generic category or brand matches
CREATE INDEX idx_products_attributes_gin ON products USING gin (attributes);

-- GIN index on variant attributes for ultra-fast size/color extractions during scans
CREATE INDEX idx_variants_attributes_gin ON product_variants USING gin (variant_attributes);

-- Real-time tracking index for system clock cleanup workers processing stale 15-minute locks
CREATE INDEX idx_inventory_locks_expiry ON inventory_locks (expires_at, status);

-- Unique high-performance search index used during physical counter checkout QR verification scans
CREATE UNIQUE INDEX idx_qr_secure_token ON qr_tokens (secure_token);
```

---

