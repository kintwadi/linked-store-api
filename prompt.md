
Act as a Principal Software Engineer specialized in high-performance enterprise Java and distributed transaction networks. Architect a production-grade backend infrastructure utilizing Spring Boot 3.x and Java 17+ to manage the workflow for a Hyperlocal Omnichannel Retail platform.

additional requirements:
Use project-context.md for context. there contains sql schema and project specifications.
com.vicinity24.core.linkedstore.api
postgres: database name linked_store
password: postgres



Review the structural parameters below carefully:

Core Business Requirements to Code:
1. Two-Phase Reservation Commit: Implement an API endpoint `/api/inventory/check-availability` that initiates an asynchronous state lock. When a store clerk accepts the push notification, create an `InventoryLock` entry tied to a 15-minute strict countdown timer (`expires_at`) at the product variant level.
2. Async Expiry Schedulers: Write a Spring `@Scheduled` or Spring Batch task checking the `inventory_locks` table every 30 seconds. If an entry crosses `expires_at` without a completed status update, transition status to `RELEASED_TO_STOCK` and restore the physical catalog count via thread-safe database interactions.
3. Stripe Split Ledger Settlement: Implement an execution handler processing payment transactions via `/api/checkout/pay`. When executed, interface with Stripe Connect API hooks to build a dynamic `transfer_group` processing a single retail card capture while splitting downstream outputs directly into targeted merchant routing balances based on business domain roles:
   - Route Wholesale Cost (cents) directly to the Fulfilling Store
   - Route Arbitrage Margin (cents) directly to the Originating Store
   - Enforce 0% platform transaction fee capturing (\$0.00 platform cut).
4. Secure Closed-Loop QR Authentication Workflow: Build an endpoint `/api/fulfillment/verify-pickup` that accepts a secure encrypted token string passed by the Fulfilling Store's scanner app. Check the `qr_tokens` database validation rules. If validated, mark the parent transaction record state as `PICKED_UP`, complete the ledger capture from escrow, and reject replay attacks.

Technical Blueprint Constraints to Enforce:
- Rely on explicit relational JPA entities mapping to the provided database table schemas with JSONB attribute parsing support (`stores`, `store_users`, `products`, `product_variants`, `transactions`, `transaction_items`, `inventory_locks`, `qr_tokens`). Use database-level optimistic locking (`@Version`) on inventory variant structures to avoid race conditions.
- Design cleanly written REST Controllers, underlying Service layers implementing robust functional abstraction rules, and custom Spring Data Repository interfaces. Include custom queries mapping PostgreSQL jsonb search targets (`@>`).
- Write strict validation checks ensuring user roles match expected processing requirements during scanning actions. Provide clean global Exception Mapping handlers returning structured JSON error schemas when a reservation window has expired.
```