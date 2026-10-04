# Linked-Store Backend — User Guide

> Owner-facing manual for GLOBAL_ADMINs / OWNERs / STORE_ADMINs.
> Fill `docs/images/` with PNG/JPG screenshots and then replace each
> `![](images/...)` path below with the corresponding file.

---

## Table of Contents

1. [First Login & Root Admin](#1-first-login--root-admin)
2. [Roles & Permissions](#2-roles--permissions)
3. [Creating a Store & Stripe Connect Onboarding](#3-creating-a-store--stripe-connect-onboarding)
4. [Adding Operators (Clerks, Runners, Reps)](#4-adding-operators-clerks-runners-reps)
5. [Store Staff Sign In](#5-store-staff-sign-in)
6. [Products & Inventory](#6-products--inventory)
   - 6.1 Manual product entry
   - 6.2 Bulk upload (JSON / CSV / XML)
7. [Subscriptions & Plans](#7-subscriptions--plans)
8. [Checkout, Runners & QR Pickup](#8-checkout-runners--qr-pickup)
9. [Returns & Refunds](#9-returns--refunds)
10. [Docker & Render Deployment](#10-docker--render-deployment)

---

## 1. First Login & Root Admin

On first boot the backend seeds one GLOBAL_ADMIN account (controlled by the
environment variable `LINKEDSTORE_PLATFORM_ROOT_ADMIN=true`, which is the
default). Default credentials are defined in `application.yml` and can be
overridden by env vars:

| Variable | Default |
|---|---|
| `LINKEDSTORE_AUTH_DEFAULT_ADMIN_EMAIL` | `admin@linked.store` |
| `LINKEDSTORE_AUTH_DEFAULT_ADMIN_PASSWORD` | `Admin123!` |

Navigate to `/login`, sign in, then use **Users → Add operator** to create
more privileged accounts and immediately change the root admin's password.

> Replace the screenshot after capturing your own admin dashboard on `/admin`:
>
> ![Admin Dashboard Overview](images/admin-dashboard-overview.png)

---

## 2. Roles & Permissions

All users (operators, owners, runners) live in a single `store_users` table,
distinguished by their `role` enum:

| Role | Access |
|---|---|
| `GLOBAL_ADMIN` | Everything, across every store. Manages subscription plans. Creates stores, onboards owners. |
| `OWNER` | Full access to one store (or multiple) plus billing & subscription. |
| `STORE_ADMIN` | Store operators & product management. No billing / subscription changes. |
| `STORE_REPRESENTATIVE` | Inventory + customer transactions, no operator management. |
| `CLERK` | Creates sales, refunds, and uploads inventory. |
| `RUNNER` | Pickup & delivery only; scoped to `/runner/pickup` route. |

![Role Matrix](images/role-matrix.png)

---

## 3. Creating a Store & Stripe Connect Onboarding

1. Sign in as `GLOBAL_ADMIN` → **Stores** tab → **Add Store**.
2. Fill in business details (name, address, lat/lng, currency).
3. The backend auto-generates an 8-digit `gateway_code` and triggers the
   Stripe Connect onboarding link.
4. The store owner opens that link, completes KYC, and the `stripe_connect_id`
   column is populated automatically once they finish.
5. After onboarding, choose a plan from the **Subscription** tab.

> See `docs/images/store-onboarding-steps.png` for the visual flow.
> ![Store Onboarding](images/store-onboarding-steps.png)

---

## 4. Adding Operators (Clerks, Runners, Reps)

Path in the UI: `/admin?tab=users` → **Add operator**.

Fields:
- **Full name** + **work email** (unique).
- **Role** (STORE_ADMIN / STORE_REPRESENTATIVE / CLERK / RUNNER).
- **Phone** (optional).
- **Initial password** (the operator changes this via a password reset flow or
  the owner shares it out-of-band; emails logins use the JWT auth flow).

You can **Suspend** or **Remove** an operator from the same list. Removal
soft-deletes permissions; active tokens still expire within `ACCESS_TOKEN_MINUTES`.

![Add Operator Form](images/add-operator.png)

---

## 5. Store Staff Sign In

Two branded entry points:

- Administrators / Owners — `/login`
- Store team (Clerks, Runners, Representatives, Store Admins) — `/staff-login`

Both routes authenticate against the same `store_users` table. JWT tokens are
returned with a configurable TTL (default 30 minutes) and a 7-day refresh
token. Failed logins suspend the account automatically for 5 minutes after
the sixth attempt.

![Staff Login](images/staff-login.png)

---

## 6. Products & Inventory

### 6.1 Manual product entry

1. Pick a store (top-left store selector).
2. Navigate to **Products & Inventory** → **Add Product**.
3. Fill in **Title**, **Description**, **Status** (ACTIVE / DRAFT / PENDING /
   OUT_OF_STOCK / INACTIVE / ARCHIVED).
4. Add at least one **Variant**: SKU, Wholesale price (what you pay the
   originating store), Retail price (what customer pays = wholesale + margin),
   stock on hand, optional variant image + gallery.

Price rule enforced in the frontend (for customer-facing checkout):
> `customer price = retail_price_cents + wholesale_price_cents`

### 6.2 Bulk upload (JSON / CSV / XML)

In the **Products & Inventory** view, click one of the **Template** chips to
download pre-filled sample files, then drag-and-drop the edited file onto the
dropzone.

- **JSON**: `frontend/products/` or `C:\Users\core101\Desktop\products\` for
  images (absolute Windows paths are resolved first by `ProductImageResolver`).
- **CSV**: UTF-8 BOM for Excel compatibility. One variant per row; duplicate
  SKUs across stores trigger auto-rename with a warning in the upload report.
- **XML**: Same field structure as JSON, wrapped in `<products><product>` tags.

The upload report displays:
- Products created / updated / skipped
- Variants created / updated / skipped
- Per-row errors with line numbers

![Product Bulk Upload](images/product-upload-dropzone.png)

---

## 7. Subscriptions & Plans

Subscription plans are **DB-first** (table `subscription_plans`) managed by
the GLOBAL_ADMIN only via **Admin Dashboard → Plans**. The store-specific UI
for buying a plan is at `/pricing` and `/admin?tab=subscription`.

Rules to know:
- A plan's `price_cents`, `interval_unit`, `interval_count` can be left NULL
  for "CUSTOM / Enterprise" plans — these are routed to the Contact Sales
  page when a user tries to subscribe.
- Annual pricing is enabled by setting `annual_price_cents` +
  `annual_discount_percent`; the pricing grid shows a Monthly / Yearly toggle.
- Quota columns use `NULL` to represent "Unlimited": `max_stores`, `max_runners`,
  `max_products`, `max_connected_stores`, `monthly_order_limit`.
- Return URLs for Stripe Checkout are generated **dynamically** from the
  browser `Origin` header (never hardcoded).

![Plans Grid](images/pricing-grid.png)

---

## 8. Checkout, Runners & QR Pickup

Hyperlocal flow (1 transaction = 2 stores + a runner):

1. **Originating store** (where the customer is) creates a checkout with
   items they don't have in stock.
2. **Fulfilling store** is the location with actual stock (chosen by the
   inventory availability endpoint).
3. Payment goes through Stripe — **split ledger**:
   - Wholesale payout → fulfilling store's Stripe Connect ID
   - Margin → originating store
   - `platform_fee_cents` (default 0) → platform account.
4. A **QR token** + **fallback code** are generated. The runner scans the QR
   code at the fulfilling store (`/runner/pickup`) to mark the pickup.
5. Status transitions: `PAID → IN_TRANSIT → READY_FOR_PICKUP → DELIVERED`.

Closed-loop guarantee: the `qr_tokens.scanned_at` column can only be written
once, preventing double pickup.

![QR Pickup Flow](images/qr-pickup-flow.png)

---

## 9. Returns & Refunds

Post-delivery:

1. **STORE_ADMIN** opens the **Transactions** tab → opens a specific txn →
   **Issue Refund**.
2. The backend creates a row in `refunds` and calls Stripe Refund API. If
   successful, it sets `refunds.status = SUCCEEDED` and reverses the margin /
   fulfiller amounts back from each Connect account.
3. **Inspection** step: the fulfilling store records what they received back
   in `returned_inspection_records` (statuses UNDER_INSPECTION → PASSED or
   REJECTED → RESTOCKED if reusable).

Partial refunds and multi-item returns are supported; the frontend lets the
user pick specific transaction_items + quantities before submitting.

![Returns Flow](images/returns-flow.png)

---

## 10. Docker & Render Deployment

The backend ships with a multi-stage Dockerfile + Render blueprint.

- Build image locally:
  ```
  docker build -t linked-store-api backend
  ```
- Deploy with Render Blueprint: push to GitHub and open `render.yaml` in
  the Render Dashboard → Blueprints → New Blueprint Instance. A Postgres 16
  DB plus the API service + the Angular frontend service are created for you.
- Required env vars (the blueprint documents all of them):
  `SPRING_DATASOURCE_URL`, `LINKEDSTORE_AUTH_JWT_SECRET`,
  `STRIPE_SECRET_KEY`, `MAIL_HOST`/`MAIL_USERNAME`/`MAIL_PASSWORD`,
  `API_BASE_ORIGIN`, `LINKEDSTORE_PLATFORM_ROOT_ADMIN`,
  `SEED_CATALOG`, `R2_*` (optional), `LOCATION_IQ_*` (optional).

![Render Deploy](images/render-deploy.png)
