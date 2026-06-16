# Resident Dues Management System — Technical Specification

> **Document Status:** Draft v1.6
> **Prepared for:** AI Coding Agents / Backend Developers
> **Effective Date:** 2026-05-31
> **Last Updated:** 2026-06-16
> **System Start Date:** 1 January 2024

---

## Revision History

| Version | Date | Changes |
|---|---|---|
| v1.0 | 2026-05-31 | Initial specification. |
| v1.1 | 2026-06-01 | Synced to implementation after a design review resolved ambiguous wording. Five behavioral clarifications: (1) prepayment discount applies to the forward block when the account is clean **after** a payment clears arrears/penalties — not only when clean before (§2.3, §6.4); (2) the minimum-payment floor is measured on **gross** alone, deposit applies during allocation (§2.2); (3) penalties are a **pure recomputed function** of history with no ledger, §6.3 is canonical over §2.4; (4) **houses are permanent** — no soft-delete, no `DELETE`; ownership changes via `PUT` (§3.2, §5.3); (5) per-house **pessimistic lock** + deterministic same-date payment ordering for atomic recompute (§6.2, §6.4). Monthly-dues report shape defined with discount reconciliation (§5.5). Tech-stack choices pinned (§7). |
| v1.2 | 2026-06-09 | Added **Rental Guarantee** domain concept (§1.1), occupancy `status` on House (§3.2), rental-guarantee business rules with configurable duration threshold and default amount (§2.5, §3.6), tenant contact fields (`tenant_name`, `tenant_email`, `tenant_phone`) on House when `status = RENTED`, exclusive physical occupancy (`OWNED` = owner resides; `RENTED` = tenant resides, owner elsewhere), and House API/CSV field updates (§5.3, §9.1). |
| v1.3 | 2026-06-10 | Added rental-guarantee **payment tracking and receipts** — `RentalGuaranteePayment` entity (§3.7), obligation-cycle model via `rental_guarantee_obligation_id` on House (§3.2), collection rules (§2.6), CRUD API + receipt response (§5.6), access matrix update (§4.2), optional CSV import (§9.3). |
| v1.4 | 2026-06-10 | Added rental-guarantee **refund on lease end** — `RentalGuaranteeRefund` entity (§3.8), refund rules (§2.7), tenant contact snapshots on payment, auto `PENDING` refund on lease termination, refund completion API (§5.7). Clarified pending refunds do **not** block new-tenant registration (§2.7). |
| v1.5 | 2026-06-15 | Added **Dashboard** aggregated endpoint (§5.5) — role-aware summary for Administrator (KPIs, calendar-year income trend, recent payments, top arrears, operational alerts) and Supervisor (alerts only). Access matrix updated (§4.2). |
| v1.6 | 2026-06-16 | **RT-scoped supervisors.** A supervisor is now confined to exactly one RT. Added `rt_id` FK on `AppUser` with `UNIQUE` (strict 1:1) and an activity-aware requirement; deactivation releases the RT for handoff (§3.5). Rewrote role definitions and the access matrix with a Scope column; added §4.3 **RT Scoping for Supervisors** (principal-derived RT, silent `rt_id` override on lists/reports/dashboard, `403` on out-of-RT single-resource/create/update, fail-closed on null RT) and the `rtId`/`rtCode` login identity. House **read** opened to supervisors (own RT). Dashboard (§5.5) now returns the **same full payload** for supervisors, RT-scoped, replacing the alerts-only shape. Per-endpoint scope notes on §5.3/§5.4/§5.6/§5.7. RT delete guard also blocks a linked supervisor (§5.2). |

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Business Rules](#2-business-rules)
3. [Data Model](#3-data-model)
4. [Access Control](#4-access-control)
5. [REST API Specification](#5-rest-api-specification)
6. [Calculation Engine](#6-calculation-engine)
7. [Technical Stack](#7-technical-stack)
8. [Non-Functional Requirements](#8-non-functional-requirements)
9. [CSV / Text Import Specification](#9-csv--text-import-specification)
10. [Glossary](#10-glossary)

---

## 1. System Overview

This document defines the complete functional and technical requirements for the **Resident Dues Management System (RDMS)** — a backend service that manages residential cluster dues, payments, arrears, penalties, and discounts. The specification is intended as a direct implementation guide for AI coding agents or backend developers.

### 1.1 Domain Concepts

| Term | Definition |
|---|---|
| **Cluster** | A residential compound divided into multiple RT (Rukun Tetangga) neighbourhood units. |
| **RT** | A sub-unit of the cluster (e.g. RT 01, RT 02, RT 03). Managed by the Administrator. |
| **House / Residence** | Identified by block code and house number (e.g. Block E, No. 20). At any time exactly one person **resides** in the house: the owner (`OWNED`) or the tenant (`RENTED`). The property owner is always registered separately as the dues contact. |
| **Owner** | The legal property owner. Always registered on the house as `owner_name`, `email`, `phone`. When `status = OWNED`, the owner **resides** in the house. When `status = RENTED`, the owner **does not** reside in the house — contact fields are for dues/admin correspondence only. |
| **Tenant** | The person **residing** in the house when `status = RENTED`. Stored as `tenant_name`, `tenant_email`, `tenant_phone`. Mutually exclusive with owner occupancy — owner and tenant never live in the same house simultaneously. |
| **Active Date** | The date from which dues obligations begin for a house. Defaults to **1 January 2024**. |
| **Dues Period** | A calendar month. Dues obligations start from the house Active Date. |
| **Deposit** | A partial prepayment less than one full month's dues, held as credit and applied against the next payment. |
| **Rental Guarantee** | One-time insurance/guarantee fee (*jaminan sewa*) owed when a house is **rented** (leased to a tenant) for a duration at or above a configurable threshold. Distinct from **Deposit** (dues prepayment credit). Obligation amount is set per house at registration or activation; collection is recorded separately via a **Rental Guarantee Receipt**. |
| **Rental Guarantee Receipt** | Proof of guarantee-fee payment. Auto-generated `receipt_number`, linked to the house and an **obligation cycle**. Does not pass through the monthly-dues allocation engine (§6.4). |
| **Rental Guarantee Refund** | Return of the collected guarantee fee to the tenant when a lease ends. Linked 1:1 to the original receipt; tracked as `PENDING` until cash is returned. |
| **Occupancy Status** | Who **physically resides** in the house: `OWNED` (owner lives there) or `RENTED` (tenant lives there; owner lives elsewhere). Owner and tenant contacts are always stored separately. |
| **Arrears** | Unpaid monthly dues accumulated past their due date. |
| **Penalty** | A surcharge applied automatically when arrears reach specified thresholds. |

---

## 2. Business Rules

### 2.1 Dues Rate Schedule

Monthly dues amounts are period-dependent:

| Rate Tier | Applicable Period | Monthly Amount (IDR) |
|---|---|---|
| **Legacy Rate** | Active Date — 31 January 2026 | Rp 100,000 |
| **Current Rate** | 1 February 2026 — present | Rp 120,000 |

> **NOTE:** When calculating arrears or processing payments that span the rate-change boundary (February 2026), the system **MUST** apply the correct rate to each individual month. Arrears for months prior to February 2026 are calculated at Rp 100,000; arrears from February 2026 onward at Rp 120,000.

---

### 2.2 Payment Processing Rules

Payments are processed sequentially. Each payment triggers the following evaluation in order:

1. Validate that the payment amount is at least equal to one month's applicable dues.
2. Check for outstanding arrears and penalties.
3. If arrears/penalties exist: apply the payment to clear the oldest outstanding period first (chronological **FIFO**), calculating the correct rate per period.
4. After all arrears and penalties are cleared, any remaining balance covers current and future months.
5. If the remaining balance after covering all full months is less than one full month's dues, store it as a **Deposit** for the following period.
6. On the next payment, the system automatically deducts the existing Deposit from the required amount due.

> **IMPORTANT:** The minimum payment amount is one (1) month's dues at the applicable rate, measured on the **gross amount received alone**. Payments whose `gross_amount` is below this threshold **MUST** be rejected. An existing Deposit does **not** lower this cash floor — the deposit is folded into the allocation only *after* the gross amount has passed the minimum check (step 2 onward).

---

### 2.3 Prepayment Discounts

Discounts apply to the **forward prepayment block** of a payment — the months covered *beyond* the current period. A payment is eligible for the discount when, **after** it has cleared all arrears and penalties, the account is fully clean (zero arrears up to and including the payment month, zero outstanding penalty) and cash still remains to prepay future months. Arrears and penalties are always settled at the full per-period rate; the discount is never applied to catch-up amounts.

> **NOTE:** "Zero arrears at the time of payment" is evaluated **after** this payment's arrears/penalty settlement. A single payment may both clear back-dues at full rate *and* earn the discount on the remaining forward block.

Discounts are applied per qualifying block:

| Prepayment Block | Discount per Block | Granularity |
|---|---|---|
| 6 months in advance | 50% of one month's dues | Per 6-month multiple |
| 12 months in advance | 100% of one month's dues (one free month) | Per 12-month multiple |

**Calculation examples** at the Current Rate (Rp 120,000):

- Pays **6 months** ahead: `6 × 120,000 − 60,000 discount = Rp 660,000`
- Pays **12 months** ahead: `12 × 120,000 − 120,000 discount = Rp 1,320,000`
- Pays **18 months** ahead (= 12 + 6): apply 12-month discount once + 6-month discount once
- Pays **24 months** ahead (= 12 × 2): apply 12-month discount twice

> **RULE:** Discounts do NOT stack across tiers. Evaluate qualifying multiples from the largest block downward, then handle the remainder at the next tier.

> **VALUATION:** Each discount is valued **per-period** using `getDuesRate` of the specific month it is attributed to — the 12-month free month uses that month's full rate; the 6-month half-discount uses 50% of that month's rate. This is recorded in the `discount_applied` column of the affected period's `PaymentAllocation` row, and is therefore correct when a forward block straddles the February 2026 rate boundary.

---

### 2.4 Penalty Rules

Penalties are assessed automatically based on **consecutive months without any payment**. The threshold counter resets whenever a payment (of any amount) is recorded.

| Consecutive Non-Payment Duration | Penalty Amount | Granularity |
|---|---|---|
| 6 consecutive months | Rp 60,000 | Per 6-month multiple |
| 12 consecutive months | Rp 120,000 | Per 12-month multiple |

**Penalty accumulation logic:**

- Months 1–5 without payment: no penalty (arrears accumulate only).
- Month 6 without payment: first Rp 60,000 penalty added.
- Month 12 without payment: Rp 120,000 penalty added for the 12-month threshold.
- The 12-month penalty supersedes the 6-month penalties for the same period to avoid double-counting — implement one canonical rule.

> **IMPLEMENTATION NOTE:** Define a deterministic penalty calculation function: given a start date, end date, and payment history, it returns the total penalty owed. Both arrears and penalties are included in the "outstanding balance" that triggers automatic settlement on each payment.

> **CANONICAL RULE:** The algorithm in **§6.3 is canonical** and supersedes any looser reading of this section. A month carries "payment activity" if any payment was recorded in it; such a month resets the consecutive-non-payment counter. The 12-month tier supersedes the 6-month tier within a gap purely as a consequence of the §6.3 formula (the 6-month term sees only `G mod 12`), so there is no double counting.

> **NO PENALTY LEDGER:** Penalties are **not persisted**. They are a pure recomputed function of `(active_date, reference month, payment-activity months)`, re-derived on every read and on every payment create/update/delete. Penalties settle **partially / as a lump** against the outstanding balance (`pay = min(remaining, outstanding_penalty)`); available cash is never left idle as a Deposit while a penalty stands unpaid.

---

### 2.5 Rental Guarantee Rules

When a house is occupied by a **tenant** (`status = RENTED`) under a lease whose duration meets or exceeds a configurable minimum, the tenant must pay a **Rental Guarantee** fee. The obligation amount is stored on the house at registration or when occupancy/lease details are updated. Actual collection is tracked via `RentalGuaranteePayment` receipts (§2.6, §3.7) — **not** via the monthly-dues `Payment` / allocation engine (§6.4).

| Setting | Default | Description |
|---|---|---|
| `rdms.rental-guarantee.min-duration-months` | `6` | Minimum lease length (months) that triggers the guarantee obligation |
| `rdms.rental-guarantee.default-amount-idr` | `300000` | Default guarantee amount (Rp 300,000) pre-filled on create; overridable per house |

**Rules:**

1. `status = OWNED` → `tenant_name`, `tenant_email`, `tenant_phone`, `lease_duration_months`, and `rental_guarantee_amount_idr` **MUST** be `NULL`. No guarantee applies.
2. `status = RENTED` → `tenant_name`, `tenant_email`, `tenant_phone`, and `lease_duration_months` **MUST** be provided (`lease_duration_months` a positive integer).
3. If `lease_duration_months >= min-duration-months` → `rental_guarantee_amount_idr` **MUST** be provided and `> 0`. On create, default to `default-amount-idr` when omitted; Administrator may override.
4. If `lease_duration_months < min-duration-months` → `rental_guarantee_amount_idr` **MUST** be `NULL` (no guarantee obligation).
5. Changing `status` from `RENTED` to `OWNED` clears `tenant_name`, `tenant_email`, `tenant_phone`, `lease_duration_months`, `rental_guarantee_amount_idr`, and `rental_guarantee_obligation_id`.
6. **Physical occupancy is exclusive:** `OWNED` means the owner resides in the house and no tenant is registered. `RENTED` means a tenant resides in the house and the owner does **not** — the owner's `owner_name` / `email` / `phone` remain the off-premises dues contact.

> **NOTE:** `status` reflects **who lives in the house**, not legal title. The permanent house record and dues ledger remain house-anchored to the property owner (§3.2).

---

### 2.6 Rental Guarantee Collection Rules

Guarantee-fee payments are recorded as **receipts** in `RentalGuaranteePayment` (§3.7). They are independent of dues `Payment` rows.

**Obligation cycle**

Each time a guarantee obligation becomes due or changes, the system assigns a new `rental_guarantee_obligation_id` (UUID) on the house. A new obligation is created when:

- A house is created or updated to `status = RENTED` with `rental_guarantee_amount_idr` set (§2.5 rule 3), or
- While `status = RENTED`, any of `tenant_name`, `tenant_email`, `tenant_phone`, `lease_duration_months`, or `rental_guarantee_amount_idr` changes and a guarantee still applies.

The previous obligation's receipts remain in the database for audit; they no longer satisfy the new obligation.

**Payment / receipt rules**

1. A guarantee receipt may be recorded only when the house has a non-null `rental_guarantee_obligation_id` and `rental_guarantee_amount_idr`.
2. `amount_idr` on the receipt **MUST** equal `rental_guarantee_amount_idr` exactly — partial or excess payments are rejected.
3. At most **one** receipt per obligation cycle — enforce `UNIQUE (obligation_id)` on `RentalGuaranteePayment`.
4. `paid_by_name`, `paid_by_email`, and `paid_by_phone` are auto-populated from the house's current `tenant_name`, `tenant_email`, and `tenant_phone` at receipt creation (immutable snapshots used later for refunds, §2.7).
5. On create, the system auto-generates an immutable `receipt_number` (format §3.7). This is the printable receipt identifier.
6. `PUT` on a receipt may update `payment_date` and `note` only. `amount_idr`, `obligation_id`, `receipt_number`, and `paid_by_name` are immutable.
7. `DELETE` removes the receipt only when no `RentalGuaranteeRefund` exists for it; otherwise rejected. Deleting an unrefunded receipt on the **current** obligation returns that obligation to **UNPAID**.
8. Guarantee receipts do **not** affect dues arrears, penalties, deposit, or `PaymentAllocation` (§6.4).

**Paid / unpaid status**

For the house's **current** `rental_guarantee_obligation_id`:

- **UNPAID** — no `RentalGuaranteePayment` row with matching `obligation_id`.
- **PAID** — exactly one matching receipt exists.

---

### 2.7 Rental Guarantee Refund Rules

When a lease ends, any guarantee fee collected for that lease **must be returned** to the tenant who paid it. Refunds are tracked in `RentalGuaranteeRefund` (§3.8) — separate from collection receipts and from dues payments.

**Lease termination events**

A lease is considered ended when `PUT /houses/{id}` (or CSV upsert) closes the current obligation cycle:

1. `status` changes from `RENTED` to `OWNED` (rent over; owner moves back in), or
2. While `status = RENTED`, any of `tenant_name`, `tenant_email`, `tenant_phone`, `lease_duration_months`, or `rental_guarantee_amount_idr` changes (new tenant or new lease terms).

**Auto-refund trigger**

Inside the same transaction as the house update, **before** clearing tenant fields or assigning a new `rental_guarantee_obligation_id`:

1. Look up `RentalGuaranteePayment` for the house's **current** `rental_guarantee_obligation_id`.
2. If a receipt exists and has no `RentalGuaranteeRefund` row yet → create a refund record with `status = PENDING`.
3. Snapshot `refunded_to_name` / `refunded_to_email` / `refunded_to_phone` from the receipt's `paid_by_*` fields (§3.7) — not from the incoming request — so recipient identity survives after tenant fields are cleared.
4. Then apply the house update (clear tenant / regenerate obligation) per §2.5.

> **NOTE:** House updates are **not blocked** by a pending refund. Administrators may end the lease first and process the physical return later. Pending refunds surface via list/report endpoints (§5.7).

**No gate on new tenants**

A `PENDING` or `COMPLETED` refund for a **previous** obligation cycle **MUST NOT** block:

- Registering a new tenant on the same house (`status = RENTED` with new `tenant_*` / lease fields),
- Transitioning `OWNED` → `RENTED` for a new lease, or
- Collecting a new guarantee payment for the new `rental_guarantee_obligation_id`.

Outstanding refunds are tracked independently per closed obligation. The system **MUST NOT** reject house create/update or guarantee receipt create solely because an earlier refund is still `PENDING`.

**Refund completion rules**

1. Only a `PENDING` refund may be completed.
2. `amount_idr` on the refund **MUST** equal the linked receipt's `amount_idr` exactly — full refund only; partial refunds are rejected.
3. Completing a refund sets `status = COMPLETED`, records `refund_date`, and generates an immutable `refund_number` (format §3.8).
4. A receipt with a `COMPLETED` refund cannot be deleted. A receipt with a `PENDING` refund cannot be deleted — cancel the refund first (§5.7).
5. Refunds do **not** affect dues arrears, penalties, deposit, or `PaymentAllocation` (§6.4).

**Receipt lifecycle (per obligation cycle)**

```
(no receipt) → UNPAID → (payment recorded) → PAID → (lease ends) → REFUND_PENDING → (refund completed) → REFUNDED
```

If the lease ends but no guarantee was ever collected for that obligation, no refund row is created.

---

## 3. Data Model

### 3.1 Entity: RT (Neighbourhood Unit)

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID / BIGSERIAL | PK | Surrogate primary key |
| `rt_code` | VARCHAR(10) | UNIQUE, NOT NULL | Human-readable identifier, e.g. `"RT 01"` |
| `description` | TEXT | NULLABLE | Optional notes about the RT |
| `created_at` | TIMESTAMPTZ | NOT NULL | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |

---

### 3.2 Entity: House (Residence)

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID / BIGSERIAL | PK | Surrogate primary key |
| `rt_id` | FK → RT.id | NOT NULL | The RT this house belongs to |
| `block_code` | VARCHAR(10) | NOT NULL | Block identifier, e.g. `"E"` |
| `house_number` | VARCHAR(10) | NOT NULL | House number, e.g. `"20"` |
| `owner_name` | VARCHAR(200) | NOT NULL | Name of the property owner (dues contact; resides in house only when `status = OWNED`) |
| `email` | VARCHAR(255) | NOT NULL | Owner contact email — off-premises when `status = RENTED` |
| `phone` | VARCHAR(30) | NOT NULL | Owner contact phone / WhatsApp — off-premises when `status = RENTED` |
| `active_date` | DATE | NOT NULL, DEFAULT `2024-01-01` | Date from which dues are calculated |
| `status` | ENUM | NOT NULL, DEFAULT `OWNED` | Who resides in the house: `OWNED` (owner lives there) \| `RENTED` (tenant lives there; owner elsewhere) |
| `tenant_name` | VARCHAR(200) | NULLABLE | Name of the resident tenant; **required** when `status = RENTED`, otherwise `NULL` |
| `tenant_email` | VARCHAR(255) | NULLABLE | Resident tenant email; **required** when `status = RENTED`, otherwise `NULL` |
| `tenant_phone` | VARCHAR(30) | NULLABLE | Resident tenant phone / WhatsApp; **required** when `status = RENTED`, otherwise `NULL` |
| `lease_duration_months` | SMALLINT | NULLABLE | Lease length in whole months; **required** when `status = RENTED`, otherwise `NULL` |
| `rental_guarantee_amount_idr` | BIGINT (IDR) | NULLABLE | Rental Guarantee fee for this house; **required** when `status = RENTED` and `lease_duration_months >= min-duration-months` (§2.5), otherwise `NULL` |
| `rental_guarantee_obligation_id` | UUID | NULLABLE | Identifies the **current** guarantee obligation cycle; set when obligation due, cleared when `OWNED` or no guarantee; regenerated on obligation change (§2.6) |
| `created_at` | TIMESTAMPTZ | NOT NULL | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |

**Composite unique index:** `(rt_id, block_code, house_number)`

**Check constraints (recommended):**

- `(status = 'OWNED' AND tenant_name IS NULL AND tenant_email IS NULL AND tenant_phone IS NULL AND lease_duration_months IS NULL AND rental_guarantee_amount_idr IS NULL AND rental_guarantee_obligation_id IS NULL) OR (status = 'RENTED' AND tenant_name IS NOT NULL AND tenant_email IS NOT NULL AND tenant_phone IS NOT NULL AND lease_duration_months IS NOT NULL AND lease_duration_months > 0)`
- When `rental_guarantee_amount_idr IS NOT NULL`: `rental_guarantee_obligation_id IS NOT NULL`
- When `rental_guarantee_amount_idr IS NULL`: `rental_guarantee_obligation_id IS NULL`
- When `status = 'RENTED'` and `lease_duration_months < min-duration-months`: `rental_guarantee_amount_idr IS NULL`
- When `status = 'RENTED'` and `lease_duration_months >= min-duration-months`: `rental_guarantee_amount_idr IS NOT NULL AND rental_guarantee_amount_idr > 0`

> **NOTE:** A House is a **permanent** physical residence — it is never deleted and has no soft-delete flag. When the property owner changes, `owner_name` / `email` / `phone` are updated in place. When a house transitions to `RENTED`, the owner stops residing there and tenant contact is recorded; reverting to `OWNED` clears the tenant (owner moves back in). The dues ledger (`active_date`, payments, arrears, penalties, deposit) is **house-anchored** to the property and carries across owner/tenant changes unchanged.

---

### 3.3 Entity: Payment

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID / BIGSERIAL | PK | Surrogate primary key |
| `house_id` | FK → House.id | NOT NULL | The paying household |
| `payment_date` | DATE | NOT NULL | Calendar date the payment was received |
| `gross_amount` | BIGINT (IDR) | NOT NULL, > 0 | Raw amount received before any allocation |
| `note` | TEXT | NULLABLE | Optional payment reference / description |
| `created_by` | FK → AppUser.id | NOT NULL | User who recorded the payment |
| `created_at` | TIMESTAMPTZ | NOT NULL | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |

---

### 3.4 Entity: PaymentAllocation

Normalises how a single Payment is distributed across periods and penalty items.

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID / BIGSERIAL | PK | Surrogate primary key |
| `payment_id` | FK → Payment.id | NOT NULL | Parent payment |
| `allocation_type` | ENUM | NOT NULL | `DUES` \| `PENALTY` \| `DEPOSIT` |
| `period_year` | SMALLINT | NULLABLE | Applicable year (NULL for DEPOSIT / PENALTY) |
| `period_month` | SMALLINT | NULLABLE | Applicable month 1–12 (NULL for DEPOSIT / PENALTY) |
| `amount` | BIGINT (IDR) | NOT NULL | Amount allocated to this line item |
| `discount_applied` | BIGINT (IDR) | NOT NULL, DEFAULT 0 | Discount amount deducted from this period |

---

### 3.5 Entity: AppUser

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID / BIGSERIAL | PK | Surrogate primary key |
| `username` | VARCHAR(100) | UNIQUE, NOT NULL | Login identifier |
| `password_hash` | TEXT | NOT NULL | Bcrypt / Argon2 hash |
| `role` | ENUM | NOT NULL | `ADMINISTRATOR` \| `SUPERVISOR` |
| `rt_id` | FK → RT.id | NULLABLE, **UNIQUE** | RT the supervisor is confined to (§4.3). `NULL` for administrators; required for **active** supervisors (see constraint below) |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT TRUE | Soft-disable flag |
| `created_at` | TIMESTAMPTZ | NOT NULL | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |

**`rt_id` constraints:**

- **UNIQUE** — at most one user may reference a given RT. Because PostgreSQL treats `NULL` values as distinct, multiple administrators (all `NULL`) coexist freely; the constraint enforces a strict **1:1** between an RT and its supervisor.
- **Activity-aware requirement** — `rt_id` MUST be non-null when `role = SUPERVISOR` AND `is_active = true`; MUST be `NULL` when `role = ADMINISTRATOR`. A **deactivated** supervisor (`is_active = false`) MAY have `rt_id = NULL`.
- **Supervisor handoff** — deactivating a supervisor clears their `rt_id`, *releasing* the RT so it can be assigned to a new supervisor while the old row is retained for audit history. Assigning an RT that an **active** supervisor already holds is rejected with **409 Conflict** (§4.3).
- **Index:** `(rt_id)` UNIQUE.

---

### 3.6 Rental Guarantee Configuration

Global defaults for §2.5 and receipt generation (§3.7). Stored in `application.properties` / environment variables — no separate DB table required.

| Property | Type | Default | Description |
|---|---|---|---|
| `rdms.rental-guarantee.min-duration-months` | INTEGER | `6` | Lease-length threshold (months) that triggers guarantee obligation |
| `rdms.rental-guarantee.default-amount-idr` | BIGINT | `300000` | Default `rental_guarantee_amount_idr` on house create when guarantee applies and amount omitted |
| `rdms.rental-guarantee.receipt-prefix` | STRING | `RG` | Prefix for auto-generated `receipt_number` values |
| `rdms.rental-guarantee.refund-prefix` | STRING | `RF` | Prefix for auto-generated `refund_number` values |

Per-house `rental_guarantee_amount_idr` on `House` (§3.2) holds the authoritative obligation amount once set.

---

### 3.7 Entity: RentalGuaranteePayment

Records collection of a Rental Guarantee fee. Separate from dues `Payment` (§3.3).

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID | PK | Surrogate primary key |
| `house_id` | FK → House.id | NOT NULL | House that owed the guarantee |
| `obligation_id` | UUID | NOT NULL, UNIQUE | Must match `House.rental_guarantee_obligation_id` at time of payment (§2.6) |
| `receipt_number` | VARCHAR(30) | UNIQUE, NOT NULL | Auto-generated receipt identifier, e.g. `"RG-2026-000042"` — immutable |
| `payment_date` | DATE | NOT NULL | Calendar date the guarantee fee was received |
| `amount_idr` | BIGINT (IDR) | NOT NULL, > 0 | Must equal `rental_guarantee_amount_idr` of the obligation |
| `paid_by_name` | VARCHAR(200) | NOT NULL | Tenant name snapshot at receipt creation |
| `paid_by_email` | VARCHAR(255) | NOT NULL | Tenant email snapshot at receipt creation |
| `paid_by_phone` | VARCHAR(30) | NOT NULL | Tenant phone snapshot at receipt creation |
| `note` | TEXT | NULLABLE | Optional reference / description |
| `created_by` | FK → AppUser.id | NOT NULL | User who recorded the receipt |
| `created_at` | TIMESTAMPTZ | NOT NULL | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |

**Receipt number generation:** `{prefix}-{YYYY}-{sequence}` where `prefix` defaults to `RG` (`rdms.rental-guarantee.receipt-prefix`, §3.6) and `sequence` is a zero-padded monotonic counter per calendar year (e.g. 6 digits). Generated inside the same DB transaction as receipt insert.

**Indexes:** `(house_id)`, `(obligation_id)` UNIQUE, `(receipt_number)` UNIQUE, `(payment_date)`.

---

### 3.8 Entity: RentalGuaranteeRefund

Records return of a guarantee fee when a lease ends. One refund per receipt.

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID | PK | Surrogate primary key |
| `payment_id` | FK → RentalGuaranteePayment.id | NOT NULL, UNIQUE | The receipt being refunded |
| `house_id` | FK → House.id | NOT NULL | Denormalised for listing/filtering |
| `obligation_id` | UUID | NOT NULL | Obligation cycle the refund closes (copied from receipt) |
| `refund_number` | VARCHAR(30) | UNIQUE, NULLABLE | Auto-generated on completion, e.g. `"RF-2026-000012"` — immutable once set |
| `status` | ENUM | NOT NULL | `PENDING` \| `COMPLETED` |
| `amount_idr` | BIGINT (IDR) | NOT NULL, > 0 | Must equal linked receipt `amount_idr` |
| `refunded_to_name` | VARCHAR(200) | NOT NULL | Tenant name snapshot (from receipt `paid_by_name`) |
| `refunded_to_email` | VARCHAR(255) | NOT NULL | Tenant email snapshot (from receipt `paid_by_email`) |
| `refunded_to_phone` | VARCHAR(30) | NOT NULL | Tenant phone snapshot (from receipt `paid_by_phone`) |
| `refund_date` | DATE | NULLABLE | Date cash was returned; **required** when `status = COMPLETED` |
| `note` | TEXT | NULLABLE | Optional reference / description |
| `created_by` | FK → AppUser.id | NOT NULL | User who triggered lease termination (auto-created refund) |
| `completed_by` | FK → AppUser.id | NULLABLE | User who recorded refund completion |
| `created_at` | TIMESTAMPTZ | NOT NULL | When lease ended and refund became due |
| `completed_at` | TIMESTAMPTZ | NULLABLE | When refund was marked completed |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |

**Refund number generation:** `{prefix}-{YYYY}-{sequence}` where `prefix` defaults to `RF` (`rdms.rental-guarantee.refund-prefix`, §3.6). Generated on completion, same transaction as status → `COMPLETED`.

**Indexes:** `(payment_id)` UNIQUE, `(house_id)`, `(status)`, `(refund_number)` UNIQUE partial where not null.

---

## 4. Access Control

### 4.1 Role Definitions

| Role | Permissions |
|---|---|
| **ADMINISTRATOR** | Full access — all operations on all resources, **cluster-wide** (every RT). |
| **SUPERVISOR** | Confined to **exactly one RT** (`AppUser.rt_id`, §3.5). Within that RT only: Create, Read, Update, Delete dues payments, rental-guarantee receipts, and rental-guarantee refunds; read-only access to houses; and all reporting and the dashboard. No access to RT or House management, user management, or CSV imports. All data is automatically scoped to the supervisor's RT (§4.3). |

### 4.2 Endpoint-Level Access Matrix

The **Scope** column states the row-level visibility: an administrator sees the whole cluster; a supervisor sees only resources belonging to their own RT (§4.3).

| Endpoint Group | ADMINISTRATOR | SUPERVISOR | Supervisor scope |
|---|:---:|:---:|---|
| RT Management (CRUD) | ✅ | ❌ | — |
| House / Resident Management (write/CRUD) | ✅ | ❌ | — |
| House Read (list + detail) | ✅ | ✅ (read-only) | Own RT only |
| House CSV Import | ✅ | ❌ | — |
| User Management | ✅ | ❌ | — |
| Payment Management (CRUD) | ✅ | ✅ | Own RT only |
| Payment CSV Import | ✅ | ❌ | — |
| Rental Guarantee Receipt Management (CRUD) | ✅ | ✅ | Own RT only |
| Rental Guarantee Refund Management | ✅ | ✅ | Own RT only |
| Rental Guarantee CSV Import | ✅ | ❌ | — |
| Arrears & Penalty Report | ✅ | ✅ | Own RT only |
| Monthly Dues Report | ✅ | ✅ | Own RT only |
| Dashboard Summary | ✅ (full, cluster-wide) | ✅ (full, RT-scoped) | Own RT only |

> **NOTE:** `SecurityConfig` gates access by **role and HTTP path** only (coarse-grained). Row-level RT confinement for supervisors is enforced in the **service layer** per §4.3 — the path matchers alone are not the scoping boundary.

---

### 4.3 RT Scoping for Supervisors

A supervisor's effective RT is **always** derived from their own user record (`AppUser.rt_id`, §3.5) resolved from the authenticated JWT principal — **never** from request parameters or bodies. Administrators are unscoped (cluster-wide). The following rules apply uniformly across payments (§5.4), the dashboard (§5.5), reporting (§5.6), and rental-guarantee receipts/refunds (§5.7, §5.8):

| Operation kind | Rule for a supervisor |
|---|---|
| **List / report / dashboard** (collection or aggregate) | Results are silently restricted to the supervisor's RT. Any client-supplied `rt_id` filter is **ignored and overridden** with the supervisor's own RT (no error). Administrators retain the `rt_id` filter as supplied. |
| **Single-resource read** (`GET /…/{id}`, `GET /houses/{id}`) | If the resource resolves to an RT other than the supervisor's RT → **403 Forbidden**. (A payment/receipt/refund's RT is derived via its house: `resource → house → rt_id`.) |
| **Create / Update** (`POST`, `PUT`) | The target house MUST belong to the supervisor's RT, else **403 Forbidden**. |
| **No assigned / null RT** (defensive) | A supervisor whose `rt_id` is `NULL` (a data error per §3.5) is treated as scoped to an empty set — scoped endpoints return empty results or `403`, never cluster-wide data (**fail closed**). |

**Login identity.** So a client can label and pre-filter the UI, the authentication response exposes the supervisor's RT identity:

```json
{ "token": "<JWT>", "role": "SUPERVISOR", "username": "budi", "rtId": "<UUID>", "rtCode": "RT 05" }
```

`rtId` and `rtCode` are **`null`** for administrators. These fields are identity hints only — the backend remains the enforcement gate regardless of what the client sends.

---

## 5. REST API Specification

### 5.1 Conventions

- **Base path:** `/api/v1`
- **Authentication:** Bearer JWT in `Authorization` header.
- **Dates:** ISO 8601 (`YYYY-MM-DD` for dates, `YYYY-MM-DDTHH:mm:ssZ` for timestamps).
- **Monetary values:** Integer IDR (e.g. `100000` for Rp 100,000).
- **Pagination:** `page` (0-based) and `size` query parameters on list endpoints.
- **Errors:** RFC 7807 Problem Detail JSON — `{ "type", "title", "status", "detail", "instance" }`.

---

### 5.2 RT Endpoints *(Administrator only)*

| Method | Path | Status | Description |
|---|---|:---:|---|
| GET | `/rts` | 200 | List all RTs |
| POST | `/rts` | 201 | Create a new RT |
| GET | `/rts/{id}` | 200 | Get RT by ID |
| PUT | `/rts/{id}` | 200 | Update RT |
| DELETE | `/rts/{id}` | 204 | Delete RT (only if **no houses AND no supervisor** are linked; §3.5) |

---

### 5.3 House Endpoints *(write: Administrator only; read: Administrator + Supervisor)*

| Method | Path | Status | Description |
|---|---|:---:|---|
| GET | `/houses` | 200 | List houses (filterable by `rt_id`). **Supervisor:** restricted to own RT; supplied `rt_id` overridden (§4.3) |
| POST | `/houses` | 201 | Create a new house *(Administrator only)* |
| GET | `/houses/{id}` | 200 | Get house by ID (includes `rental_guarantee` summary per §5.6 when applicable). **Supervisor:** `403` if house is outside own RT (§4.3) |
| PUT | `/houses/{id}` | 200 | Update house — also the mechanism for an **ownership change** (update `owner_name`/`email`/`phone`) or occupancy/lease change (`status`, `tenant_name`/`tenant_email`/`tenant_phone`, `lease_duration_months`, `rental_guarantee_amount_idr`). Lease termination auto-creates a `PENDING` refund when applicable (§2.7). |
| POST | `/houses/import` | 202 | Bulk import from CSV/TXT file (`multipart/form-data`) |

> **NOTE:** There is **no** `DELETE /houses/{id}`. Houses are permanent (see §3.2); ownership transfers are performed with `PUT /houses/{id}`. A `DELETE` to this path returns `405 Method Not Allowed`.

**`POST /houses` — request body (rented house with guarantee):**
```json
{
  "rt_id": "<UUID>",
  "block_code": "E",
  "house_number": "20",
  "owner_name": "Budi Santoso",
  "email": "budi@example.com",
  "phone": "+6281234567890",
  "active_date": "2024-01-01",
  "status": "RENTED",
  "tenant_name": "Andi Wijaya",
  "tenant_email": "andi@example.com",
  "tenant_phone": "+6289876543210",
  "lease_duration_months": 12,
  "rental_guarantee_amount_idr": 300000
}
```

> **VALIDATION:** Enforce §2.5 and §3.2 check constraints. When `status = RENTED`, `lease_duration_months >= min-duration-months`, and `rental_guarantee_amount_idr` is omitted, apply `default-amount-idr`. When a guarantee obligation is created, assign a new `rental_guarantee_obligation_id` (§2.6). Return HTTP 400 on violation.

**CSV import column order:**
```
rt_code, block_code, house_number, owner_name, email, phone, active_date, status, tenant_name, tenant_email, tenant_phone, lease_duration_months, rental_guarantee_amount_idr
```

> **CSV defaults:** `status` defaults to `OWNED` when omitted. When `status = RENTED`, `tenant_name`, `tenant_email`, and `tenant_phone` are required. `lease_duration_months` and `rental_guarantee_amount_idr` may be omitted; when guarantee applies, `rental_guarantee_amount_idr` defaults to `default-amount-idr`.

---

### 5.4 Payment Endpoints

| Method | Path | Status | Description |
|---|---|:---:|---|
| GET | `/payments` | 200 | List payments (filterable by `house_id`, `rt_id`, date range) |
| POST | `/payments` | 201 | Record a new payment; triggers automatic allocation engine |
| GET | `/payments/{id}` | 200 | Get payment + allocation breakdown |
| PUT | `/payments/{id}` | 200 | Update payment (re-runs allocation engine) |
| DELETE | `/payments/{id}` | 204 | Delete payment (re-calculates account balance) |
| POST | `/payments/import` | 202 | Bulk import from CSV/TXT *(Admin only)* |

> **SUPERVISOR SCOPE (§4.3):** `GET /payments` is restricted to the supervisor's RT (supplied `rt_id` overridden). `GET/PUT/DELETE /payments/{id}` return `403` when the payment's house is outside the supervisor's RT. `POST /payments` returns `403` when `house_id` is outside the supervisor's RT.

**`POST /payments` — request body:**
```json
{
  "house_id": "<UUID>",
  "payment_date": "2025-06-01",
  "gross_amount": 720000,
  "note": "optional reference"
}
```

**`POST /payments` — response body** includes the computed `allocations` array and updated account summary (`deposit_balance`, `next_period_covered`).

**Payment CSV import column order:**
```
block_code, house_number, payment_date, gross_amount, note
```

---

### 5.5 Dashboard Endpoint

| Method | Path | Status | Description |
|---|---|:---:|---|
| GET | `/dashboard` | 200 | Role-aware dashboard summary. **Administrator:** cluster-wide. **Supervisor:** same payload shape, RT-scoped (§4.3) |

**Access:** Both roles receive the **same full payload shape**. The administrator's figures are cluster-wide; the supervisor's figures are restricted to their own RT (§4.3). The supervisor response additionally carries `rtId` / `rtCode` so the UI can label the scope.

**Computation rules.** The rules below describe the administrator (cluster-wide) computation. For a **supervisor**, every aggregation is restricted to houses where `house.rt_id = supervisor.rt_id` — `totalCollectedIdr`, `currentMonth`, `houseStats`, `monthlyTrend`, `recentPayments`, `topArrears`, and `alerts` all reflect that single RT.

| Field | Rule (administrator = cluster; supervisor = own RT) |
|---|---|
| `totalCollectedIdr` | `SUM(payment.gross_amount)` — all-time gross cash received |
| `currentMonth.percent` | `round(collectedIdr × 100 / expectedIdr)`; `0` when `expectedIdr = 0` |
| `currentMonth.expectedIdr` / `collectedIdr` | Totals for the reference calendar month (same aggregation as `/reports/dues/monthly`, current-month row) |
| `houseStats.paidUp` | Houses where `arrearsTotal = 0` **and** `penaltyTotal = 0` at reference month |
| `houseStats.inArrears` | Houses where `arrearsTotal + penaltyTotal > 0` |
| `monthlyTrend` | Twelve entries for the current calendar year (Jan–Dec), zero-filled; `collectedIdr` = dues cash allocated per month |
| `recentPayments` | Five most recent payments by `created_at DESC`; `primaryPeriod` = latest `DUES` allocation period on that payment; `housePaidUp` = account fully caught up (`arrearsTotal = 0` and `penaltyTotal = 0`) immediately after that payment in replay order |
| `topArrears` | Up to 10 houses with `totalOutstandingIdr = totalArrearsIdr + totalPenaltiesIdr > 0`, descending; ties broken by `ownerName` ascending |
| `alerts.pendingRefunds` | Count of refunds with `status = PENDING` |
| `alerts.unpaidGuarantees` | Count of houses where current guarantee obligation status is `UNPAID` |

**`GET /dashboard` — Administrator response shape:**
```json
{
  "role": "ADMINISTRATOR",
  "totalCollectedIdr": 12450000,
  "currentMonth": {
    "year": 2026,
    "month": 6,
    "expectedIdr": 18000000,
    "collectedIdr": 15300000,
    "percent": 85
  },
  "houseStats": {
    "total": 150,
    "paidUp": 120,
    "inArrears": 30
  },
  "monthlyTrend": [
    { "year": 2026, "month": 1, "collectedIdr": 0 }
  ],
  "recentPayments": [
    {
      "paymentId": "<UUID>",
      "houseId": "<UUID>",
      "ownerName": "Ahmad Dhani",
      "rtCode": "RT 02",
      "grossAmountIdr": 120000,
      "createdAt": "2026-06-15T10:05:00Z",
      "primaryPeriod": { "year": 2026, "month": 5 },
      "housePaidUp": true
    }
  ],
  "topArrears": [
    {
      "rank": 1,
      "houseId": "<UUID>",
      "ownerName": "Warga A",
      "rtCode": "RT 01",
      "totalOutstandingIdr": 1500000,
      "phone": "08123456789",
      "email": "warga@example.com"
    }
  ],
  "alerts": {
    "pendingRefunds": 2,
    "unpaidGuarantees": 5
  }
}
```

**`GET /dashboard` — Supervisor response shape:**

Identical to the administrator shape above, with all figures restricted to the supervisor's RT, plus `rtId` / `rtCode` identifying the scope:
```json
{
  "role": "SUPERVISOR",
  "rtId": "<UUID>",
  "rtCode": "RT 05",
  "totalCollectedIdr": 2480000,
  "currentMonth": { "year": 2026, "month": 6, "expectedIdr": 3600000, "collectedIdr": 3060000, "percent": 85 },
  "houseStats": { "total": 30, "paidUp": 24, "inArrears": 6 },
  "monthlyTrend": [ { "year": 2026, "month": 1, "collectedIdr": 0 } ],
  "recentPayments": [ /* same shape, own RT only */ ],
  "topArrears": [ /* same shape, own RT only */ ],
  "alerts": { "pendingRefunds": 1, "unpaidGuarantees": 2 }
}
```

> **NOTE:** JSON property names follow Jackson's default camelCase serialisation of Kotlin data classes. Both roles return the **same payload shape**; only the data scope differs (§4.3). `rtId` / `rtCode` are present for supervisors and `null` for administrators. Existing report endpoints (`/reports/**`) remain available for drill-down pages (Laporan Iuran, house-level arrears detail), also RT-scoped for supervisors.

---

### 5.6 Reporting Endpoints

| Method | Path | Status | Description |
|---|---|:---:|---|
| GET | `/reports/dues/monthly` | 200 | Cluster-wide monthly dues summary |
| GET | `/reports/dues/monthly?rt_id={id}` | 200 | Monthly dues summary for one RT |
| GET | `/reports/dues/monthly?house_id={id}` | 200 | Monthly dues history for one house |
| GET | `/reports/arrears/{house_id}` | 200 | Arrears & penalty detail for one house |

> **SUPERVISOR SCOPE (§4.3):** Monthly dues reports are restricted to the supervisor's RT — a `CLUSTER` request is downgraded to the supervisor's RT (scope returned as `RT`), and a supplied `rt_id` is overridden with their own. `GET /reports/arrears/{house_id}` returns `403` when the house is outside the supervisor's RT.

**`GET /reports/dues/monthly` — response shape:**
```json
{
  "scope": "CLUSTER",
  "house_id": null,
  "rt_id": null,
  "rows": [
    {
      "year": 2026,
      "month": 2,
      "expected_idr": 120000,
      "collected_idr": 120000,
      "discount_idr": 0,
      "outstanding_idr": 0,
      "paid": true
    }
  ],
  "total_expected_idr": 120000,
  "total_collected_idr": 120000,
  "total_discount_idr": 0
}
```

> **RECONCILIATION:** For every period the identity `expected_idr = collected_idr + discount_idr + outstanding_idr` holds. `collected_idr` is the actual cash received (a prepayment-discounted month therefore shows less cash than expected), `discount_idr` is the dues waived via prepayment discount, and `outstanding_idr` is what remains owed. `scope` is one of `CLUSTER` / `RT` / `HOUSE` depending on the query parameters.

**`GET /reports/arrears/{house_id}` — response shape:**
```json
{
  "house_id": "<UUID>",
  "owner_name": "string",
  "total_arrears_idr": 600000,
  "total_penalties_idr": 60000,
  "deposit_idr": 0,
  "unpaid_periods": [
    { "year": 2025, "month": 1, "dues_amount": 100000, "paid": false }
  ],
  "penalties": [
    { "trigger": "6_MONTHS", "assessed_after_period": "2025-06", "amount": 60000 }
  ]
}
```

---

### 5.7 Rental Guarantee Receipt Endpoints

| Method | Path | Status | Description |
|---|---|:---:|---|
| GET | `/rental-guarantee/payments` | 200 | List guarantee receipts (filterable by `house_id`, `rt_id`, `obligation_id`, date range) |
| POST | `/rental-guarantee/payments` | 201 | Record guarantee-fee payment; returns receipt |
| GET | `/rental-guarantee/payments/{id}` | 200 | Get receipt by ID (full printable payload) |
| PUT | `/rental-guarantee/payments/{id}` | 200 | Update `payment_date` and/or `note` |
| DELETE | `/rental-guarantee/payments/{id}` | 204 | Void receipt; current obligation becomes UNPAID |
| POST | `/rental-guarantee/payments/import` | 202 | Bulk import from CSV/TXT *(Administrator only)* |

> **SUPERVISOR SCOPE (§4.3):** Receipt and refund lists are restricted to the supervisor's RT (supplied `rt_id` overridden); single-resource reads, creates, and mutations return `403` when the receipt/refund's house lies outside the supervisor's RT.

**`POST /rental-guarantee/payments` — request body:**
```json
{
  "house_id": "<UUID>",
  "payment_date": "2026-06-09",
  "amount_idr": 300000,
  "note": "Transfer BCA"
}
```

> **VALIDATION:** Enforce §2.6. Reject with HTTP 400 when: house has no active obligation, `amount_idr` ≠ `rental_guarantee_amount_idr`, or obligation already PAID. `paid_by_name` / `paid_by_email` / `paid_by_phone` are set server-side from current tenant fields.

**`POST /rental-guarantee/payments` — response body (receipt):**
```json
{
  "id": "<UUID>",
  "receipt_number": "RG-2026-000042",
  "house_id": "<UUID>",
  "obligation_id": "<UUID>",
  "payment_date": "2026-06-09",
  "amount_idr": 300000,
  "paid_by_name": "Andi Wijaya",
  "paid_by_email": "andi@example.com",
  "paid_by_phone": "+6289876543210",
  "note": "Transfer BCA",
  "house": {
    "block_code": "E",
    "house_number": "20",
    "owner_name": "Budi Santoso",
    "tenant_name": "Andi Wijaya",
    "status": "RENTED"
  },
  "created_by": "<UUID>",
  "created_at": "2026-06-09T10:30:00Z"
}
```

**`GET /houses/{id}` — `rental_guarantee` summary (when guarantee applies):**
```json
{
  "required": true,
  "amount_idr": 300000,
  "obligation_id": "<UUID>",
  "status": "UNPAID",
  "receipt": null
}
```

When paid, `status` is `"PAID"` and `receipt` contains the matching `RentalGuaranteePayment` object (including `receipt_number`). When no guarantee applies (`OWNED` or short lease), omit `rental_guarantee` or set `"required": false`.

**Rental guarantee CSV import column order:**
```
block_code, house_number, payment_date, amount_idr, note
```

---

### 5.8 Rental Guarantee Refund Endpoints

| Method | Path | Status | Description |
|---|---|:---:|---|
| GET | `/rental-guarantee/refunds` | 200 | List refunds (filterable by `house_id`, `rt_id`, `status`, date range) |
| GET | `/rental-guarantee/refunds/{id}` | 200 | Get refund by ID |
| POST | `/rental-guarantee/refunds/{id}/complete` | 200 | Mark `PENDING` refund as returned; issues `refund_number` |
| DELETE | `/rental-guarantee/refunds/{id}` | 204 | Cancel a `PENDING` refund only (lease-end was recorded in error) |

> **AUTO-CREATE:** Refunds are created by the system on lease termination (§2.7). There is no manual `POST /refunds` — administrators only **complete** or **cancel** pending items.

**`POST /rental-guarantee/refunds/{id}/complete` — request body:**
```json
{
  "refund_date": "2026-12-01",
  "note": "Cash returned to tenant"
}
```

**`POST /rental-guarantee/refunds/{id}/complete` — response body:**
```json
{
  "id": "<UUID>",
  "refund_number": "RF-2026-000012",
  "status": "COMPLETED",
  "payment_id": "<UUID>",
  "receipt_number": "RG-2026-000042",
  "house_id": "<UUID>",
  "obligation_id": "<UUID>",
  "amount_idr": 300000,
  "refunded_to_name": "Andi Wijaya",
  "refunded_to_email": "andi@example.com",
  "refunded_to_phone": "+6289876543210",
  "refund_date": "2026-12-01",
  "note": "Cash returned to tenant",
  "completed_by": "<UUID>",
  "completed_at": "2026-12-01T14:00:00Z"
}
```

**`GET /rental-guarantee/payments/{id}`** includes nested `refund` when present (`null` \| `{ status, refund_number, … }`).

---

## 6. Calculation Engine

### 6.1 Dues Rate Function

Implement a pure function `getDuesRate(yearMonth: YearMonth): Long` that returns the applicable dues in IDR:

```
if yearMonth < 2026-02 → return 100_000
else                   → return 120_000
```

---

### 6.2 Account Balance Computation

For a given house and reference date, the system must derive:

- **Paid periods** — the set of `(year, month)` pairs fully covered by recorded payments.
- **Unpaid periods** — all periods from `active_date` up to the reference date not in paid periods.
- **Arrears total** — `Σ getDuesRate(period)` for each unpaid period.
- **Penalty total** — deterministic function over consecutive-month gaps in the payment history.
- **Deposit** — any partial credit remaining after the last payment allocation.

> This computation **MUST** be re-run whenever a payment is created, updated, or deleted.

> **ORDERING:** Payments are replayed in deterministic chronological order `(payment_date, created_at, id)`. The `created_at` / `id` tiebreak guarantees a stable, reproducible result when two payments share the same `payment_date`.

> **CONCURRENCY:** Recompute acquires a **pessimistic write-lock on the house row** (`SELECT … FOR UPDATE`) at the start of the transaction, so concurrent payment mutations against the same house are serialized and the full account state is rewritten atomically.

---

### 6.3 Penalty Calculation Algorithm

This is the **canonical** penalty rule referenced by §2.4. It is a pure function, recomputed on demand and never persisted. Given the payment history of a house:

1. Build a sorted timeline of all months from `active_date` to today.
2. Identify consecutive sequences of months with no payment activity.
3. For each gap sequence of length `G` months:
   - Add `floor(G / 12) × Rp 120,000` penalties.
   - Add `floor((G mod 12) / 6) × Rp 60,000` penalties.
4. Sum all penalty amounts → `total_penalties_idr`.

> **EXAMPLE:** A house with no payment for **15 consecutive months**:
> `floor(15/12) = 1 × Rp 120,000` + `floor(3/6) = 0 × Rp 60,000` = **Rp 120,000** total penalty.

---

### 6.4 Payment Allocation Algorithm

When `POST /payments` (or an update/delete) is processed, the entire house account is recomputed by replaying its full payment history inside a **single database transaction**, guarded by a **pessimistic write-lock on the house row** (see §6.2). Payments replay in `(payment_date, created_at, id)` order. For each payment:

0. Reject the payment if `gross_amount < getDuesRate(payment_month)` (minimum is one month's dues on **gross** alone, §2.2).
1. Set `remaining = gross_amount + deposit_balance` (carried from the previous payment).
2. For each unpaid period from the earliest through the payment month (oldest first):
   - `rate = getDuesRate(period)`
   - If `remaining >= rate`: mark period paid, create `PaymentAllocation(DUES, period, rate)`, `remaining -= rate`.
   - Else: store `remaining` as the new deposit and stop (still in arrears).
3. If arrears up to the payment month are fully cleared, settle outstanding penalties **partially/lump** (`pay = min(remaining, outstanding_penalty)`); record `PaymentAllocation(PENALTY, …)`. If a penalty remains outstanding, store `remaining` as deposit and stop.
4. **Forward block / prepayment discount:** if the account is now clean (arrears and penalties cleared by this payment) and `remaining > 0`, allocate forward months. Determine the maximum number of future months `M` affordable under the discounted requirement — `floor(M/12)` free months + (if `M mod 12 ≥ 6`) one half-month, no stacking, each discount valued per-period (§2.3) — create `PaymentAllocation(DUES, period, cash, discount_applied)` for each, and set `remaining` to the leftover.
5. Store `deposit_balance = remaining` (guaranteed `< getDuesRate(nextPeriod)`).
6. Persist all `PaymentAllocation` records (allocations are derived — existing rows for the house are replaced on every recompute).

> **SCOPE:** `RentalGuaranteePayment` receipts (§3.7) are **excluded** from this algorithm. Dues `Payment` rows only.

---

## 7. Technical Stack

| Component | Requirement |
|---|---|
| **JDK** | >= 21 (LTS) |
| **Language** | Kotlin (latest stable, JVM target 21) |
| **Build Tool** | Apache Maven |
| **Framework** | Spring Boot 3.x (Spring Web MVC or WebFlux) |
| **Database** | PostgreSQL 15+ |
| **ORM / Query** | Spring Data JPA + Hibernate |
| **Migration** | Flyway (SQL migrations) |
| **Primary Keys** | UUID |
| **Auth** | Spring Security + JWT (stateless); passwords hashed with BCrypt cost 12 |
| **Serialisation** | Jackson with Kotlin module |
| **Testing** | JUnit 5, MockK, Testcontainers (PostgreSQL) |
| **API Docs** | SpringDoc OpenAPI (Swagger UI at `/swagger-ui.html`) |

---

## 8. Non-Functional Requirements

| Area | Requirement |
|---|---|
| **Correctness** | All monetary calculations must use integer arithmetic in IDR. No floating-point for money. |
| **Consistency** | Dues payment creation, update, and deletion must run inside a database transaction that re-computes the full account state atomically. A **pessimistic write-lock on the house row** serializes concurrent mutations of the same house (§6.2). Rental-guarantee receipt/refund mutations and lease-terminating house updates acquire the same house-row lock. |
| **Security** | Passwords stored as Argon2id or bcrypt (cost ≥ 12). JWT expiry configurable via environment variable. |
| **Audit** | `created_at`, `updated_at`, and `created_by` columns on all mutable tables. |
| **Testability** | The payment allocation and penalty calculation engines must be pure functions testable without a running database. |
| **Configuration** | All environment-specific settings (DB, JWT secret, port, rental-guarantee thresholds — §3.6) via `application.properties` / environment variables. No secrets in source code. |
| **Error Handling** | Unhandled exceptions return structured RFC 7807 error responses. Validation errors return HTTP 400 with field-level detail. |

---

## 9. CSV / Text Import Specification

### 9.1 House Import

- **Accepted delimiters:** comma (`,`) or semicolon (`;`)
- **Header row:** optional — detected automatically.
- **Encoding:** UTF-8.
- **Column order:** `rt_code, block_code, house_number, owner_name, email, phone, active_date, status, tenant_name, tenant_email, tenant_phone, lease_duration_months, rental_guarantee_amount_idr`
- **`active_date`:** `YYYY-MM-DD` format; if omitted defaults to `2024-01-01`.
- **`status`:** `OWNED` or `RENTED`; if omitted defaults to `OWNED`.
- **`tenant_name` / `tenant_email` / `tenant_phone`:** Required when `status = RENTED`; must be empty/omitted when `OWNED`.
- **`lease_duration_months`:** Positive integer; required when `status = RENTED`.
- **`rental_guarantee_amount_idr`:** Integer IDR; required when `status = RENTED` and `lease_duration_months >= min-duration-months` (§2.5); defaults to `default-amount-idr` when omitted and guarantee applies.
- **Duplicate handling:** On duplicate `(rt_code + block_code + house_number)` — update existing record (upsert semantics).
- **Error handling:** On row-level error: skip row, collect error into import result response — do not abort entire import.

### 9.2 Payment Import

- **Column order:** `block_code, house_number, payment_date, gross_amount, note`
- **`gross_amount`:** Integer IDR, no currency symbols.
- **`payment_date`:** `YYYY-MM-DD`.
- Each valid row triggers the full payment allocation engine.
- **Import result response includes:** `total_rows`, `success_count`, `error_count`, `errors[]`.

### 9.3 Rental Guarantee Payment Import

- **Column order:** `block_code, house_number, payment_date, amount_idr, note`
- **`amount_idr`:** Integer IDR; must equal the house's current `rental_guarantee_amount_idr` (§2.6).
- **`payment_date`:** `YYYY-MM-DD`.
- Each valid row creates a `RentalGuaranteePayment` receipt if the house has an unpaid obligation.
- **Import result response includes:** `total_rows`, `success_count`, `error_count`, `errors[]`.

---

## 10. Glossary

| Abbreviation / Term | Meaning |
|---|---|
| IDR | Indonesian Rupiah |
| RT | Rukun Tetangga — a neighbourhood sub-unit |
| KK | Kepala Keluarga — head of household |
| Rental Guarantee | *Jaminan sewa* — one-time tenant insurance/guarantee fee when lease duration meets threshold (§2.5); not the dues **Deposit** |
| Rental Guarantee Receipt | Printable proof of guarantee-fee collection; `receipt_number` + `RentalGuaranteePayment` row (§2.6, §3.7) |
| Rental Guarantee Refund | Return of guarantee fee to tenant on lease end; `refund_number` + `RentalGuaranteeRefund` row (§2.7, §3.8) |
| Obligation cycle | UUID (`rental_guarantee_obligation_id`) identifying one guarantee collection period per house; resets on lease/tenant/amount change |
| Tenant | Person **residing** in the house when `status = RENTED`; owner lives elsewhere (§2.5, §3.2) |
| OWNED / RENTED | Physical occupancy — owner lives in house vs tenant lives in house (§3.2) |
| RDMS | Resident Dues Management System (this system) |
| FIFO | First In, First Out — oldest debts paid first |
| JWT | JSON Web Token |
| RFC 7807 | IETF standard for HTTP API error responses |

---

*— End of Specification —*
