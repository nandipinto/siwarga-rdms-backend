---
status: accepted
---

# Penalties key off on-time coverage, not payment activity

## Context

The spec (§2.4, §6.3) defines penalties over "consecutive months without payment"
and marks §6.3 as **canonical**, where a month carries weight if it has *payment
activity* — i.e. any payment row was recorded in that calendar month. The
implementation followed this: `AllocationEngine.replay` feeds a set of payment-months
(`activity`) into `PenaltyCalculator.compute`.

This is wrong in both directions:

- **Punishes prepayers.** A resident who pays all of 2026 in one January payment
  records no payment in Feb–Dec, so those months look like a non-payment gap and a
  Rp 60k penalty is assessed around month 6 — despite the account being fully covered.
  This directly contradicts §2.3, which *rewards* prepayment with a discount.
- **Lets drip-payers escape.** Any payment in a calendar month reset the counter, so a
  resident could pay a token amount each month and never trigger a penalty while arrears
  grew without bound.

## Decision

Penalties are assessed over **on-time coverage**, not payment activity. A month M is
covered *on time* iff a payment dated in month ≤ M extended dues coverage to reach M
(monthly granularity, no within-month grace). A month covered only retroactively by a
later-dated catch-up payment is **not** on-time and remains part of the delinquency gap.

Consequently a penalty, once triggered by a historical gap, **sticks** even after the
resident catches up — measured as-of-each-month, not as-of-now.

Mechanically: `AllocationEngine.replay` builds an `on-time-covered` set during its
existing chronological replay — when a payment dated in month `pp` advances the frontier
to `nextUnpaid`, mark `[pp .. nextUnpaid-1]`. That set replaces `activity` as the input
to `PenaltyCalculator.compute`. The penalty gap-finding algorithm itself is unchanged.

## Considered options

- **Payment-activity (the original spec rule).** Simpler input (just payment dates, no
  coupling to the coverage frontier) and naturally sticky (past calendar months can't
  gain activity retroactively). Rejected: punishes prepayers and lets drip-payers escape.
- **Coverage as-of-now.** A month is delinquent only if *still* uncovered today.
  Rejected: catching up on back-dues would erase already-triggered penalties, making the
  fine toothless — defer for years, settle with one lump sum, pay no penalty.

## Consequences

- No data migration. Penalties are a pure recomputed function and all reports/dashboards
  replay live, so the change takes effect on deploy. (System is also pre-production;
  existing data is disposable.)
- §2.4 and §6.3's "canonical" wording is superseded by this ADR. The spec text should be
  updated to describe on-time coverage rather than payment activity.
- No within-month due-day grace exists anywhere else in the system; this keeps it that way.
