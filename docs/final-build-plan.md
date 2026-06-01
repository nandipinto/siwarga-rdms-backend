# RDMS Build Plan — Resident Dues Management System

## Context

Greenfield. Repo holds only `docs/RDMS_Technical_Specification.md`. No code yet (prior memory notes referenced files that do not exist on disk — stale). Goal: build the backend service in the spec — Kotlin + Spring Boot 3 + PostgreSQL — managing RT/houses/payments with arrears, penalties, deposits, prepayment discounts, CSV import, JWT auth, reports.

Tooling verified: JDK 21 (GraalVM), Maven 3.9.16, Docker 29 present.

## Stack decisions (defaults — confirm/override at approval)

- ORM: **Spring Data JPA + Hibernate**
- Migration: **Flyway** (SQL)
- PK: **UUID** (matches spec JSON examples)
- Build: **phased** — core domain + calc engine + tests first, then full API/auth/import/reports

## Architecture

```
com.siwarga
 ├─ config        (security, openapi, jackson)
 ├─ domain        (JPA entities: Rt, House, Payment, PaymentAllocation, AppUser, enums)
 ├─ repository    (Spring Data repos)
 ├─ calc          (PURE engine: DuesRate, PenaltyCalculator, AllocationEngine, AccountState) — no Spring, no DB
 ├─ service       (RtService, HouseService, PaymentService, ReportService, ImportService, UserService)
 ├─ web           (controllers, DTOs, RFC7807 exception handler)
 └─ security      (JWT filter, token service, UserDetails)
```

**Key principle from spec §8:** calc engine = pure functions, testable without DB. Keep `calc/` free of Spring/JPA — operate on plain value objects (`YearMonth`, `Long` IDR, payment history lists). Services adapt DB ↔ engine.

## Phases

### Phase 0 — Scaffold
- `pom.xml`: Kotlin (JVM 21), Spring Boot 3.3.x, starters (web, data-jpa, security, validation), PostgreSQL driver, Flyway, JJWT 0.12.x, OpenCSV, SpringDoc OpenAPI, jackson-module-kotlin. Test: JUnit5, MockK, Testcontainers.
- `application.properties` + env-var overrides (DB url/user/pass, JWT secret, JWT expiry, server port, ADMIN_USERNAME/PASSWORD). No secrets in source.
- `docker-compose.yml`: postgres 15, db `siwarga`, user/pass `siwarga/siwarga`, port 5432.
- Main app class.

### Phase 1 — Calc engine (pure, TDD) — the core risk
Files in `calc/`:
- `DuesRate.getRate(ym: YearMonth): Long` → `<2026-02 ? 100_000 : 120_000` (spec §6.1).
- `PenaltyCalculator` (spec §6.3): timeline active_date→ref, find consecutive no-payment gaps length G; `floor(G/12)*120_000 + floor((G mod 12)/6)*60_000`. Returns total + breakdown items. **Reconcile spec ambiguity:** §2.4 "counter resets on any payment" vs §6.3 "gap sequences" — implement §6.3 (gaps reset by any payment month) as canonical; document.
- `AccountState`: given house active_date, payment history, ref date → paid periods, unpaid periods, arrears total, penalty total, deposit.
- `AllocationEngine` (spec §6.4): `remaining = gross + deposit`; FIFO clear unpaid periods at per-period rate; then penalties; if zero arrears apply prepayment discount (§2.3: largest block down — 12mo=one free month per 12, 6mo=50% per 6, no stacking); leftover < next rate → deposit. Reject if gross < one month rate (spec §2.2). Returns allocations + new deposit + covered periods.
- Unit tests for every spec example: rate boundary, 15-month penalty=120k, 6/12/18/24-month discounts, FIFO across Feb-2026 boundary, deposit carry-forward, below-minimum rejection.

### Phase 2 — Persistence
- Entities + enums (`AllocationType`, `UserRole`, penalty `trigger`). Audit cols `created_at/updated_at/created_by`. Composite unique `(rt_id, block_code, house_number)`. House soft-delete flag.
- Flyway `V1__init.sql`: all tables, indexes, FKs.
- Repositories.
- Money = `Long` (BIGINT) everywhere, integer arithmetic only (spec §8).

### Phase 3 — Services
- `PaymentService.createPayment` etc. inside `@Transactional`: load history → run AccountState+AllocationEngine → persist Payment + PaymentAllocation rows + deposit. Update/Delete re-run full recompute atomically (spec §6.2, §8).
- `RtService` (delete only if no houses), `HouseService`, `UserService` (Argon2/bcrypt cost≥12), `ReportService`.

### Phase 4 — Web + Security
- JWT stateless: `JwtTokenService`, auth filter, `POST /api/v1/auth/login`.
- Method/route security: ADMINISTRATOR full; SUPERVISOR only payments + reports + arrears (spec §4.2).
- Controllers for all groups (spec §5.2–5.5), DTOs, bean validation.
- `@RestControllerAdvice` → RFC 7807 problem detail; 400 field errors.
- Seed default admin from env on startup.
- SpringDoc → `/swagger-ui.html`.

### Phase 5 — CSV import (spec §9)
- `ImportService` via OpenCSV. Auto-detect `,`/`;`, optional header, UTF-8.
- House import: upsert on `(rt_code+block_code+house_number)`, default active_date 2024-01-01, row-error collect+skip.
- Payment import: each row → allocation engine; result `{total_rows, success_count, error_count, errors[]}`.
- Endpoints `202`, admin-only.

### Phase 6 — Integration tests
- Testcontainers PostgreSQL: payment lifecycle, role enforcement (SUPERVISOR 403 on RT), CSV import happy+error paths, report shapes match spec §5.5 JSON.

## Critical files (new)
- `pom.xml`, `docker-compose.yml`, `src/main/resources/application.properties`
- `src/main/resources/db/migration/V1__init.sql`
- `src/main/kotlin/com/siwarga/calc/*` (engine — highest value, build + test first)
- `src/main/kotlin/com/siwarga/{domain,repository,service,web,security,config}/*`
- `src/test/kotlin/com/siwarga/...`

## Open spec ambiguities (resolve during impl, will surface)
1. Penalty: §2.4 reset-on-payment vs §6.3 gap-formula — using §6.3 as canonical.
2. Discount mechanics: how discount maps onto PaymentAllocation `discount_applied` per future period.
3. "Paid period" definition when a month is only partially covered by deposit.

## Verification
- `mvn test` — pure calc unit tests + Testcontainers integration tests all green.
- `docker compose up -d` then `mvn spring-boot:run`; hit `/swagger-ui.html`.
- Manual curl: login → create RT → create house → POST payment (e.g. 720000) → GET `/reports/arrears/{house_id}`; assert allocations/penalties/deposit match spec examples.
- Verify SUPERVISOR token gets 403 on `/rts`.

---

# Refinements from design grill (2026-06-01)

The initial build is complete and green (21 tests). A grilling session resolved the spec's
ambiguities into canonical rules. Six confirm current behavior; **five require code changes**.

## Confirmed as-is (no change)
- **Penalty = pure recomputed function** of (active_date, refMonth, payment-activity-months). No penalty ledger; the 12-month tier retroactively supersedes the 6-month within a gap. (`PenaltyCalculator`)
- **Minimum payment gate is on `gross` alone** (`gross < getRate(period)` → reject). Deposit folds in only *after* the gate, during allocation.
- **Discount valued per-period** — the free/half month uses *its own* `getDuesRate`, stamped on that period's `discountApplied`. Correct across the Feb-2026 boundary.
- **A period is due at month start**, refMonth = inclusive `YearMonth.now()`.
- **Penalty settles partial/lump** (`pay = min(remaining, outstandingPenalty)`); never strand cash as deposit while a penalty stands.
- **Allocations are fully recomputed from history on every mutation** — derived, not frozen snapshots. Breakdowns may change retroactively when an earlier payment is edited; that is intended.

## Required changes

1. **Discount eligibility = account clean *after* this payment clears arrears/penalties (Q3a).**
   Current `AllocationEngine` gates the forward-prepayment discount on "had zero arrears *before* this payment." Change to: clear arrears FIFO, then penalties; **if the account is now clean (no unpaid period ≤ payment month, zero outstanding penalty) and cash remains, the forward block gets the standard 6/12-month discount.** Arrears/penalties are always paid at full per-period rate — discount applies *only* to the post-clearing forward block.
   - File: `src/main/kotlin/com/siwarga/calc/AllocationEngine.kt` — replace the `eligibleForDiscount = !hadArrears && …` precondition with an after-clearing check; pass `discountEligible = true` to `allocateForward` whenever the account is clean and `remaining > 0`.
   - Tests: add an `AllocationEngineTest` case — single payment that clears N arrears *and* prepays 6/12 months ahead, asserting full-rate arrears + discounted forward block.

2. **Deterministic same-date ordering by `created_at`, then `id` (Q8).**
   - `repository/Repositories.kt`: `findByHouseIdOrderByPaymentDateAsc` → also order by `createdAt`, then `id`.
   - `calc` `PaymentInput`: carry a stable sort key (e.g. `createdAt: Instant`); `AllocationEngine.replay` sorts by `(paymentDate, createdAt, id)`.
   - `AccountService` maps `payment.createdAt` into `PaymentInput`.

3. **Houses are permanent — remove soft-delete; model ownership change (Q10).**
   - Continuous, house-anchored ledger: `active_date`, payments, arrears/penalty/deposit all stay attached to the house across owners. Owner change is a **field update** (`owner_name`/`email`/`phone`) via `PUT /houses/{id}`; **current snapshot only** (defer any `house_owner_history` table).
   - Drop the `deleted` column and `House.deleted` field; remove `DELETE /houses/{id}` and `HouseService.delete`; simplify repo queries (`findByDeletedFalse*` → plain finders); `AccountService.loadHouse` no longer needs a deleted filter.
   - New Flyway migration `V2__drop_house_soft_delete.sql` (`ALTER TABLE house DROP COLUMN deleted;`).
   - `RtService.delete`: block if *any* house is linked (no deleted distinction now).

4. **Per-house pessimistic lock during recompute (Q11).**
   - Add `@Lock(PESSIMISTIC_WRITE)` `findByIdForUpdate(id): House?` to `HouseRepository`.
   - `AccountService.recomputeAndPersist` acquires the house write-lock at the top, so all payment mutations for one house serialize — satisfying §6.4 "single transaction" / §8 atomic recompute.

5. **Monthly dues report reconciles discounts (Q12).**
   - `service/Dtos.kt` `MonthlyDuesRow`: add `discountIdr: Long`; add `totalDiscountIdr` to `MonthlyDuesReport`. Identity per period: `expected = collected_cash + discount + outstanding`.
   - `ReportService.monthlyDues`: accumulate `discountApplied` per period alongside cash `amount`.

## Verification for refinements
- `mvn test` — existing 21 plus new cases: discount-after-arrears-clearing; same-date ordering determinism; report discount reconciliation. Integration test: confirm `DELETE /houses` is gone (404/405) and owner change via `PUT` preserves the ledger.
- Manual: post a single payment that clears 3 arrears + prepays 6 months; assert 3 full-rate DUES + 6-month forward block with one 60k discount.
