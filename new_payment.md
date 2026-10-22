# Multi-Party Marketplace Payment Strategy

This document outlines the evaluation of two payment routing strategies for our multi-party marketplace involving four entities: the **Customer**, the **Platform**, the **Product Owner Store**, and the **Arbitrage/Brokerage Store**. 

The system is built on **Stripe Connect** using **Separate Charges and Transfers**.

> **Implementation status:** Scenario 1 (Hold on Platform & Split at Product Pickup) is now **implemented** and is the live payment flow. See [`payment.md`](./payment.md) for the as-built workflow.

---

## Strategy Scenarios Overview

### Scenario 1: Hold on Platform & Split at Product Pickup (Recommended)
1. **Checkout:** The platform charges the customer's card. Funds are captured and held in the platform's primary Stripe account.
2. **Fulfillment Window:** Funds remain safely on the platform while the order is processed.
3. **Pickup Trigger:** Upon verified product pickup, the platform executes two distinct transfers using a shared `transfer_group`:
   * **Transfer A:** The specified payout amount goes to the Product Owner Store.
   * **Transfer B:** The brokerage/arbitrage cut goes to the Selling Store.
   * **Residual:** The remaining amount naturally stays in the platform's main Stripe account as the marketplace monetization fee; it is not sent through a separate platform connected account.

### Scenario 2: Immediate Pay to Owner, Deferred Pay to Arbitrage/Platform
1. **Checkout:** The platform charges the customer's card. The Product Owner Store receives its payout immediately into its connected account.
2. **Fulfillment Window:** The platform holds only the arbitrage cut and the platform fee.
3. **Pickup Trigger:** The Arbitrage Store and the Platform claim their respective portions only after the customer successfully picks up the product.

---

## Side-by-Side Comparison

| Operational Feature | Scenario 1: Split at Pickup | Scenario 2: Immediate Owner Pay |
| :--- | :--- | :--- |
| **Refund Workflow** | **Simple:** Full refund issued directly from the platform's held balance. | **Complex:** Requires clawing back funds from the owner, which can fail if already withdrawn. |
| **Chargeback Liability** | **Low Risk:** Platform maintains financial leverage over the lifecycle of the unfulfilled order. | **High Risk:** Platform is fully liable for customer disputes while the owner has already been paid. |
| **Stripe Implementation** | **Standard:** Fits cleanly into Stripe's `Separate Charges and Transfers` workflow using a single `transfer_group`. | **Fragile:** Requires managing asymmetric payout delays and manual error handling hooks. |
| **Platform Leverage** | **High:** Encourages fulfillment quality since neither store is paid until the customer receives the item. | **Low:** Product owner has no financial incentive to prioritize the pickup workflow post-checkout. |

---

## Why Scenario 1 is the Superior Strategic Choice

Scenario 1 is the industry-standard architecture for multi-sided marketplaces because it mitigates financial risk, ensures regulatory compliance, and simplifies engineering overhead.

### 1. Mitigation of "Negative Balance" Risk
If a customer cancels an order or files a dispute *before* picking up the product in Scenario 2, the platform must refund the customer. If the Product Owner Store has already transferred their immediate payout to an external bank account, the platform's Stripe balance will go negative. The platform is then forced to cover the deficit out-of-pocket while attempting to reverse a transfer from a third-party account—a process that frequently fails. Scenario 1 keeps funds central until the contract is fulfilled.

### 2. Perfect Alignment with Stripe Connect Architecture
Stripe’s **Separate Charges and Transfers** mechanism is explicitly engineered for Scenario 1. By passing a unique `transfer_group` string (e.g., `order_group_12345`) during the initial `PaymentIntent` creation, the platform can safely hold the collected charge. When the backend receives a webhook confirming the physical product pickup, it triggers two isolated `stripe.transfers.create()` API calls. This ensures clean accounting, atomic transactions, and transparent ledger tracking.

### 3. Regulatory and Marketplace Compliance
In many financial jurisdictions (such as PSD2 in Europe), immediate pass-through of funds to an actor before service rendering can inadvertently reclassify the platform as a money transmitter rather than a marketplace facilitator. Holding funds on the platform until a verification token (like a pickup QR code or PIN confirmation) is processed keeps the platform legally compliant as a mediator of the transaction.

