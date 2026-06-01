# Resident Dues Management System — Technical Specification

> **Document Status:** Draft v1.1
> **Prepared for:** AI Coding Agents / Backend Developers
> **Effective Date:** 2026-05-31
> **Last Updated:** 2026-06-01
> **System Start Date:** 1 January 2024

---

## Revision History

| Version | Date | Changes |
|---|---|---|
| v1.0 | 2026-05-31 | Initial specification. |
| v1.1 | 2026-06-01 | Synced to implementation after a design review resolved ambiguous wording. Five behavioral clarifications: (1) prepayment discount applies to the forward block when the account is clean **after** a payment clears arrears/penalties — not only when clean before (§2.3, §6.4); (2) the minimum-payment floor is measured on **gross** alone, deposit applies during allocation (§2.2); (3) penalties are a **pure recomputed function** of history with no ledger, §6.3 is canonical over §2.4; (4) **houses are permanent** — no soft-delete, no `DELETE`; ownership changes via `PUT` (§3.2, §5.3); (5) per-house **pessimistic lock** + deterministic same-date payment ordering for atomic recompute (§6.2, §6.4). Monthly-dues report shape defined with discount reconciliation (§5.5). Tech-stack choices pinned (§7). |

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
| **House / Residence** | Identified by block code and house number (e.g. Block E, No. 20). Each house has exactly one head-of-household (KK). |
| **Head of Household (KK)** | The registered representative of a house. Carries a name, email address, and phone number. |
| **Active Date** | The date from which dues obligations begin for a house. Defaults to **1 January 2024**. |
| **Dues Period** | A calendar month. Dues obligations start from the house Active Date. |
| **Deposit** | A partial prepayment less than one full month's dues, held as credit and applied against the next payment. |
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
| `owner_name` | VARCHAR(200) | NOT NULL | Name of the head of household |
| `email` | VARCHAR(255) | NOT NULL | Contact email address |
| `phone` | VARCHAR(30) | NOT NULL | Contact phone / WhatsApp number |
| `active_date` | DATE | NOT NULL, DEFAULT `2024-01-01` | Date from which dues are calculated |
| `created_at` | TIMESTAMPTZ | NOT NULL | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |

**Composite unique index:** `(rt_id, block_code, house_number)`

> **NOTE:** A House is a **permanent** physical residence — it is never deleted and has no soft-delete flag. When the head of household changes, the `owner_name` / `email` / `phone` fields are updated in place (current-owner snapshot). The dues ledger (`active_date`, payments, arrears, penalties, deposit) is **house-anchored** and carries across owners unchanged.

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
| `is_active` | BOOLEAN | NOT NULL, DEFAULT TRUE | Soft-disable flag |
| `created_at` | TIMESTAMPTZ | NOT NULL | Record creation timestamp |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |

---

## 4. Access Control

### 4.1 Role Definitions

| Role | Permissions |
|---|---|
| **ADMINISTRATOR** | Full access — all operations on all resources. |
| **SUPERVISOR** | Payment management only: Create, Read, Update, Delete payments and payment allocations. No access to RT or House management, user management, or CSV imports. |

### 4.2 Endpoint-Level Access Matrix

| Endpoint Group | ADMINISTRATOR | SUPERVISOR |
|---|:---:|:---:|
| RT Management (CRUD) | ✅ | ❌ |
| House / Resident Management (CRUD) | ✅ | ❌ |
| House CSV Import | ✅ | ❌ |
| User Management | ✅ | ❌ |
| Payment Management (CRUD) | ✅ | ✅ |
| Payment CSV Import | ✅ | ❌ |
| Arrears & Penalty Report | ✅ | ✅ |
| Monthly Dues Report | ✅ | ✅ |

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
| DELETE | `/rts/{id}` | 204 | Delete RT (only if no houses are linked) |

---

### 5.3 House Endpoints *(Administrator only)*

| Method | Path | Status | Description |
|---|---|:---:|---|
| GET | `/houses` | 200 | List all houses (filterable by `rt_id`) |
| POST | `/houses` | 201 | Create a new house |
| GET | `/houses/{id}` | 200 | Get house by ID |
| PUT | `/houses/{id}` | 200 | Update house — also the mechanism for an **ownership change** (update `owner_name`/`email`/`phone`) |
| POST | `/houses/import` | 202 | Bulk import from CSV/TXT file (`multipart/form-data`) |

> **NOTE:** There is **no** `DELETE /houses/{id}`. Houses are permanent (see §3.2); ownership transfers are performed with `PUT /houses/{id}`. A `DELETE` to this path returns `405 Method Not Allowed`.

**CSV import column order:**
```
rt_code, block_code, house_number, owner_name, email, phone, active_date
```

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

### 5.5 Reporting Endpoints

| Method | Path | Status | Description |
|---|---|:---:|---|
| GET | `/reports/dues/monthly` | 200 | Cluster-wide monthly dues summary |
| GET | `/reports/dues/monthly?rt_id={id}` | 200 | Monthly dues summary for one RT |
| GET | `/reports/dues/monthly?house_id={id}` | 200 | Monthly dues history for one house |
| GET | `/reports/arrears/{house_id}` | 200 | Arrears & penalty detail for one house |

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
| **Consistency** | Payment creation, update, and deletion must run inside a database transaction that re-computes the full account state atomically. A **pessimistic write-lock on the house row** serializes concurrent mutations of the same house (§6.2). |
| **Security** | Passwords stored as Argon2id or bcrypt (cost ≥ 12). JWT expiry configurable via environment variable. |
| **Audit** | `created_at`, `updated_at`, and `created_by` columns on all mutable tables. |
| **Testability** | The payment allocation and penalty calculation engines must be pure functions testable without a running database. |
| **Configuration** | All environment-specific settings (DB, JWT secret, port) via `application.properties` / environment variables. No secrets in source code. |
| **Error Handling** | Unhandled exceptions return structured RFC 7807 error responses. Validation errors return HTTP 400 with field-level detail. |

---

## 9. CSV / Text Import Specification

### 9.1 House Import

- **Accepted delimiters:** comma (`,`) or semicolon (`;`)
- **Header row:** optional — detected automatically.
- **Encoding:** UTF-8.
- **Column order:** `rt_code, block_code, house_number, owner_name, email, phone, active_date`
- **`active_date`:** `YYYY-MM-DD` format; if omitted defaults to `2024-01-01`.
- **Duplicate handling:** On duplicate `(rt_code + block_code + house_number)` — update existing record (upsert semantics).
- **Error handling:** On row-level error: skip row, collect error into import result response — do not abort entire import.

### 9.2 Payment Import

- **Column order:** `block_code, house_number, payment_date, gross_amount, note`
- **`gross_amount`:** Integer IDR, no currency symbols.
- **`payment_date`:** `YYYY-MM-DD`.
- Each valid row triggers the full payment allocation engine.
- **Import result response includes:** `total_rows`, `success_count`, `error_count`, `errors[]`.

---

## 10. Glossary

| Abbreviation / Term | Meaning |
|---|---|
| IDR | Indonesian Rupiah |
| RT | Rukun Tetangga — a neighbourhood sub-unit |
| KK | Kepala Keluarga — head of household |
| RDMS | Resident Dues Management System (this system) |
| FIFO | First In, First Out — oldest debts paid first |
| JWT | JSON Web Token |
| RFC 7807 | IETF standard for HTTP API error responses |

---

*— End of Specification —*
