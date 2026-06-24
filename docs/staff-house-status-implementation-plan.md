# Staff House Status Implementation Patch Notes

This document contains the implementation snippets needed to replace hard-coded January 2026 staff house keys with effective-dated staff status history.

## Summary

This implementation adds:

- house_staff_status database table
- HouseStaffStatus JPA entity
- HouseStaffStatusRepository
- StaffStatusPeriod calculation input
- HouseDiscountContext calculation input
- Staff-status-aware January 2026 staff promo
- Staff-status-aware account replay
- Updated dashboard replay path
- Updated allocation tests
- Documentation updates

---

## 1. Add Flyway Migration

Create this file:

    src/main/resources/db.migration/V9__house_staff_status.sql

Content:

    CREATE EXTENSION IF NOT EXISTS btree_gist;

    CREATE TABLE house_staff_status (
        id UUID PRIMARY KEY,
        house_id UUID NOT NULL REFERENCES house(id),
        effective_from DATE NOT NULL,
        effective_to DATE NULL,
        staff_house BOOLEAN NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        version BIGINT NOT NULL DEFAULT 0,
        CONSTRAINT chk_house_staff_status_range
            CHECK (effective_to IS NULL OR effective_to > effective_from)
    );

    ALTER TABLE house_staff_status
    ADD CONSTRAINT excl_house_staff_status_no_overlap
    EXCLUDE USING gist (
        house_id WITH =,
        daterange(effective_from, effective_to, '[)') WITH &&
    );

    CREATE INDEX idx_house_staff_status_house_from
        ON house_staff_status (house_id, effective_from);

    CREATE INDEX idx_house_staff_status_house_range
        ON house_staff_status (house_id, effective_from, effective_to);

    INSERT INTO house_staff_status (
        id,
        house_id,
        effective_from,
        effective_to,
        staff_house,
        created_at,
        updated_at,
        version
    )
    SELECT
        gen_random_uuid(),
        h.id,
        DATE '2026-01-01',
        NULL,
        TRUE,
        now(),
        now(),
        0
    FROM house h
    WHERE (h.block_code, h.house_number) IN (
        ('A', '16'),
        ('B', '05'),
        ('B', '07'),
        ('D', '16'),
        ('E', '08')
    );

Notes:

- effective_to is exclusive.
- Active range rule: effective_from <= date < effective_to
- NULL effective_to means open-ended.
- The exclusion constraint prevents overlapping staff-status periods for the same house.

---

## 2. Update Allocation.kt

File:

    src/main/kotlin/com/siwarga/rdms/calc/Allocation.kt

Add the following after PaymentResult and before AccountState:

    data class StaffStatusPeriod(
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val staffHouse: Boolean,
    ) {
        fun appliesOn(date: LocalDate): Boolean =
            !date.isBefore(effectiveFrom) &&
                (effectiveTo == null || date.isBefore(effectiveTo))
    }

    data class HouseDiscountContext(
        val staffStatusPeriods: List<StaffStatusPeriod> = emptyList(),
    ) {
        fun isStaffHouseOn(date: LocalDate): Boolean =
            staffStatusPeriods.any { it.staffHouse && it.appliesOn(date) }
    }

The relevant section should become:

    data class PaymentResult(
        val payment: PaymentInput,
        val lines: List<AllocationLine>,
        val depositAfter: Long,
        val coveredPeriods: List<YearMonth>,
    )

    data class StaffStatusPeriod(
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate?,
        val staffHouse: Boolean,
    ) {
        fun appliesOn(date: LocalDate): Boolean =
            !date.isBefore(effectiveFrom) &&
                (effectiveTo == null || date.isBefore(effectiveTo))
    }

    data class HouseDiscountContext(
        val staffStatusPeriods: List<StaffStatusPeriod> = emptyList(),
    ) {
        fun isStaffHouseOn(date: LocalDate): Boolean =
            staffStatusPeriods.any { it.staffHouse && it.appliesOn(date) }
    }

    /** Final derived account state for a house at a reference month (spec §6.2). */
    data class AccountState(
        val activeDate: YearMonth,
        val refMonth: YearMonth,
        val paidPeriods: List<YearMonth>,
        val unpaidPeriods: List<YearMonth>,
        val arrearsTotal: Long,
        val penaltyTotal: Long,
        val depositBalance: Long,
        val penalties: List<PenaltyItem>,
        val coveredThrough: YearMonth?,
    )

---

## 3. Update JanuaryRateLock.kt

File:

    src/main/kotlin/com/siwarga/rdms/calc/JanuaryRateLock.kt

Remove this block:

    /** `(block_code, house_number)` keys — extensible without schema changes. */
    val STAFF_HOUSE_KEYS: Set<String> =
        setOf(
            "A/16",
            "B/05",
            "B/07",
            "D/16",
            "E/08",
        )

Remove this function:

    fun promotionalHouseKey(
        blockCode: String,
        houseNumber: String,
    ): String = "$blockCode/${normalizeHouseNumber(houseNumber)}"

Remove this function:

    private fun normalizeHouseNumber(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() }) {
            return trimmed.padStart(2, '0')
        }
        return trimmed.uppercase()
    }

Change tryAllocate from:

    fun tryAllocate(
        payment: PaymentInput,
        nextUnpaid: YearMonth,
        outstandingPenaltyBefore: Long,
        deposit: Long,
        houseKey: String?,
    ): Result? {
        if (YearMonth.from(payment.paymentDate) != PAYMENT_MONTH) return null
        if (nextUnpaid != COVERAGE_START) return null
        if (outstandingPenaltyBefore > 0) return null
        if (deposit > 0) return null

        val profile = resolveProfile(payment.grossAmount, houseKey) ?: return null
        return when (profile) {
            Profile.SIX_MONTH -> allocateSixMonth()
            Profile.STAFF_YEAR -> allocateStaffYear()
            Profile.YEAR_PACKAGE -> allocateYearPackage(depositAfter = 0)
            Profile.YEAR_WITH_DEPOSIT -> allocateYearPackage(depositAfter = payment.grossAmount - YEAR_PACKAGE_GROSS)
        }
    }

to:

    fun tryAllocate(
        payment: PaymentInput,
        nextUnpaid: YearMonth,
        outstandingPenaltyBefore: Long,
        deposit: Long,
        isStaffHouse: Boolean,
    ): Result? {
        if (YearMonth.from(payment.paymentDate) != PAYMENT_MONTH) return null
        if (nextUnpaid != COVERAGE_START) return null
        if (outstandingPenaltyBefore > 0) return null
        if (deposit > 0) return null

        val profile = resolveProfile(payment.grossAmount, isStaffHouse) ?: return null
        return when (profile) {
            Profile.SIX_MONTH -> allocateSixMonth()
            Profile.STAFF_YEAR -> allocateStaffYear()
            Profile.YEAR_PACKAGE -> allocateYearPackage(depositAfter = 0)
            Profile.YEAR_WITH_DEPOSIT -> allocateYearPackage(depositAfter = payment.grossAmount - YEAR_PACKAGE_GROSS)
        }
    }

Change resolveProfile from:

    private fun resolveProfile(
        gross: Long,
        houseKey: String?,
    ): Profile? =
        when {
            gross == SIX_MONTH_GROSS -> Profile.SIX_MONTH
            gross == STAFF_YEAR_GROSS && houseKey != null && houseKey in STAFF_HOUSE_KEYS -> Profile.STAFF_YEAR
            gross == YEAR_PACKAGE_GROSS -> Profile.YEAR_PACKAGE
            gross > YEAR_PACKAGE_GROSS -> Profile.YEAR_WITH_DEPOSIT
            else -> null
        }

to:

    private fun resolveProfile(
        gross: Long,
        isStaffHouse: Boolean,
    ): Profile? =
        when {
            gross == SIX_MONTH_GROSS -> Profile.SIX_MONTH
            gross == STAFF_YEAR_GROSS && isStaffHouse -> Profile.STAFF_YEAR
            gross == YEAR_PACKAGE_GROSS -> Profile.YEAR_PACKAGE
            gross > YEAR_PACKAGE_GROSS -> Profile.YEAR_WITH_DEPOSIT
            else -> null
        }

---

## 4. Update AllocationEngine.kt

File:

    src/main/kotlin/com/siwarga/rdms/calc/AllocationEngine.kt

Change replay signature from:

    fun replay(
        activeDate: YearMonth,
        payments: List<PaymentInput>,
        refMonth: YearMonth,
        promotionalHouseKey: String? = null,
    ): ReplayResult {

to:

    fun replay(
        activeDate: YearMonth,
        payments: List<PaymentInput>,
        refMonth: YearMonth,
        discountContext: HouseDiscountContext = HouseDiscountContext(),
    ): ReplayResult {

Change January promo call from:

    val promo =
        JanuaryRateLock.tryAllocate(
            payment = p,
            nextUnpaid = nextUnpaid,
            outstandingPenaltyBefore = outstandingPenaltyBefore,
            deposit = deposit,
            houseKey = promotionalHouseKey,
        )

to:

    val promo =
        JanuaryRateLock.tryAllocate(
            payment = p,
            nextUnpaid = nextUnpaid,
            outstandingPenaltyBefore = outstandingPenaltyBefore,
            deposit = deposit,
            isStaffHouse = discountContext.isStaffHouseOn(p.paymentDate),
        )

---

## 5. Update Entities.kt

File:

    src/main/kotlin/com/siwarga/rdms/domain/Entities.kt

Add the following entity after House and before AppUser:

    @Entity
    @Table(name = "house_staff_status")
    class HouseStaffStatus(
        @Id
        var id: UUID = UUID.randomUUID(),
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "house_id", nullable = false)
        var house: House,
        @Column(name = "effective_from", nullable = false)
        var effectiveFrom: LocalDate,
        @Column(name = "effective_to")
        var effectiveTo: LocalDate? = null,
        @Column(name = "staff_house", nullable = false)
        var staffHouse: Boolean,
        @Column(name = "created_at", nullable = false)
        var createdAt: Instant = Instant.now(),
        @Column(name = "updated_at", nullable = false)
        var updatedAt: Instant = Instant.now(),
        @Version
        @Column(name = "version", nullable = false)
        var version: Long = 0,
    ) {
        @PrePersist fun onCreate() {
            val now = Instant.now()
            createdAt = now
            updatedAt = now
        }

        @PreUpdate fun onUpdate() {
            updatedAt = Instant.now()
        }
    }

Existing imports should already cover this entity.

---

## 6. Update Repositories.kt

File:

    src/main/kotlin/com/siwarga/rdms/repository/Repositories.kt

Add import:

    import com.siwarga.rdms.domain.HouseStaffStatus

Add repository after HouseRepository:

    interface HouseStaffStatusRepository : JpaRepository<HouseStaffStatus, UUID> {
        fun findByHouseIdOrderByEffectiveFromAsc(houseId: UUID): List<HouseStaffStatus>
    }

Relevant section should look like:

    interface HouseRepository : JpaRepository<House, UUID> {
        // existing methods
    }

    interface HouseStaffStatusRepository : JpaRepository<HouseStaffStatus, UUID> {
        fun findByHouseIdOrderByEffectiveFromAsc(houseId: UUID): List<HouseStaffStatus>
    }

    interface AppUserRepository : JpaRepository<AppUser, UUID> {
        // existing methods
    }

---

## 7. Update AccountService.kt

File:

    src/main/kotlin/com/siwarga/rdms/service/AccountService.kt

Remove import:

    import com.siwarga.rdms.calc.JanuaryRateLock

Add imports:

    import com.siwarga.rdms.calc.HouseDiscountContext
    import com.siwarga.rdms.calc.StaffStatusPeriod
    import com.siwarga.rdms.repository.HouseStaffStatusRepository

Change constructor from:

    class AccountService(
        private val houseRepository: HouseRepository,
        private val paymentRepository: PaymentRepository,
        private val allocationRepository: PaymentAllocationRepository,
    ) {

to:

    class AccountService(
        private val houseRepository: HouseRepository,
        private val paymentRepository: PaymentRepository,
        private val allocationRepository: PaymentAllocationRepository,
        private val staffStatusRepository: HouseStaffStatusRepository,
    ) {

Change replay from:

    fun replay(
        house: House,
        refMonth: YearMonth = YearMonth.now(),
    ): ReplayResult {
        val payments = paymentRepository.findByHouseIdOrderByPaymentDateAscCreatedAtAscIdAsc(house.id)
        val inputs = payments.map { PaymentInput(it.paymentDate, it.grossAmount, it.id, it.createdAt) }
        return AllocationEngine.replay(
            YearMonth.from(house.activeDate),
            inputs,
            refMonth,
            JanuaryRateLock.promotionalHouseKey(house.blockCode, house.houseNumber),
        )
    }

to:

    fun replay(
        house: House,
        refMonth: YearMonth = YearMonth.now(),
    ): ReplayResult {
        val payments = paymentRepository.findByHouseIdOrderByPaymentDateAscCreatedAtAscIdAsc(house.id)
        return replayPartial(house, payments, refMonth)
    }

    fun replayPartial(
        house: House,
        payments: List<Payment>,
        refMonth: YearMonth,
    ): ReplayResult =
        AllocationEngine.replay(
            YearMonth.from(house.activeDate),
            payments.map { PaymentInput(it.paymentDate, it.grossAmount, it.id, it.createdAt) },
            refMonth,
            discountContextFor(house),
        )

Change recompute replay from:

    val result =
        AllocationEngine.replay(
            YearMonth.from(house.activeDate),
            payments.map { PaymentInput(it.paymentDate, it.grossAmount, it.id, it.createdAt) },
            refMonth,
            JanuaryRateLock.promotionalHouseKey(house.blockCode, house.houseNumber),
        )

to:

    val result =
        AllocationEngine.replay(
            YearMonth.from(house.activeDate),
            payments.map { PaymentInput(it.paymentDate, it.grossAmount, it.id, it.createdAt) },
            refMonth,
            discountContextFor(house),
        )

Add helper before toStoredView:

    private fun discountContextFor(house: House): HouseDiscountContext =
        HouseDiscountContext(
            staffStatusPeriods =
                staffStatusRepository.findByHouseIdOrderByEffectiveFromAsc(house.id).map {
                    StaffStatusPeriod(
                        effectiveFrom = it.effectiveFrom,
                        effectiveTo = it.effectiveTo,
                        staffHouse = it.staffHouse,
                    )
                },
        )

---

## 8. Update DashboardService.kt

File:

    src/main/kotlin/com/siwarga/rdms/service/DashboardService.kt

Remove imports:

    import com.siwarga.rdms.calc.AllocationEngine
    import com.siwarga.rdms.calc.JanuaryRateLock
    import com.siwarga.rdms.calc.PaymentInput

Keep:

    import com.siwarga.rdms.calc.AllocationType
    import com.siwarga.rdms.calc.DuesRate

Change housePaidUpAfterPayment from:

    private fun housePaidUpAfterPayment(
        house: House,
        target: Payment,
        refMonth: YearMonth,
    ): Boolean {
        val payments = paymentRepository.findByHouseIdOrderByPaymentDateAscCreatedAtAscIdAsc(house.id)
        val index = payments.indexOfFirst { it.id == target.id }
        if (index < 0) return false
        val slice = payments.subList(0, index + 1)
        val inputs = slice.map { PaymentInput(it.paymentDate, it.grossAmount, it.id, it.createdAt) }
        val result =
            AllocationEngine.replay(
                YearMonth.from(house.activeDate),
                inputs,
                refMonth,
                JanuaryRateLock.promotionalHouseKey(house.blockCode, house.houseNumber),
            )
        return result.account.arrearsTotal == 0L && result.account.penaltyTotal == 0L
    }

to:

    private fun housePaidUpAfterPayment(
        house: House,
        target: Payment,
        refMonth: YearMonth,
    ): Boolean {
        val payments = paymentRepository.findByHouseIdOrderByPaymentDateAscCreatedAtAscIdAsc(house.id)
        val index = payments.indexOfFirst { it.id == target.id }
        if (index < 0) return false
        val slice = payments.subList(0, index + 1)
        val result = accountService.replayPartial(house, slice, refMonth)
        return result.account.arrearsTotal == 0L && result.account.penaltyTotal == 0L
    }

---

## 9. Update AllocationEngineTest.kt

File:

    src/test/kotlin/com/siwarga/rdms/calc/AllocationEngineTest.kt

Add helper after duesLines:

    private fun staffContext(
        effectiveFrom: String = "2026-01-01",
        effectiveTo: String? = null,
    ) = HouseDiscountContext(
        staffStatusPeriods =
            listOf(
                StaffStatusPeriod(
                    effectiveFrom = LocalDate.parse(effectiveFrom),
                    effectiveTo = effectiveTo?.let { LocalDate.parse(it) },
                    staffHouse = true,
                ),
            ),
    )

Replace staff allowlist test with:

    @Test
    fun `January 2026 staff year package waives June and December for staff house`() {
        val res =
            AllocationEngine.replay(
                ym(2026, 1),
                listOf(pay("2026-01-01", 1_000_000)),
                ym(2026, 12),
                discountContext = staffContext(),
            )
        val lines = duesLines(res.perPayment.single())
        assertEquals(12, lines.size)
        assertEquals(0, lines.single { it.period == ym(2026, 6) }.amount)
        assertEquals(0, lines.single { it.period == ym(2026, 12) }.amount)
        assertEquals(10, lines.count { it.amount == 100_000L })
        assertEquals(ym(2026, 12), res.account.coveredThrough)
    }

Add test:

    @Test
    fun `January 2026 staff package requires staff status on payment date`() {
        val res =
            AllocationEngine.replay(
                ym(2026, 1),
                listOf(pay("2026-01-01", 1_000_000)),
                ym(2026, 12),
                discountContext = staffContext(effectiveFrom = "2026-02-01"),
            )

        assertTrue(duesLines(res.perPayment.single()).size < 12)
        assertTrue(res.account.coveredThrough == null || res.account.coveredThrough!! < ym(2026, 12))
    }

Add test:

    @Test
    fun `January 2026 staff package does not apply after staff status ended`() {
        val res =
            AllocationEngine.replay(
                ym(2026, 1),
                listOf(pay("2026-01-01", 1_000_000)),
                ym(2026, 12),
                discountContext = staffContext(effectiveFrom = "2025-01-01", effectiveTo = "2026-01-01"),
            )

        assertTrue(duesLines(res.perPayment.single()).size < 12)
        assertTrue(res.account.coveredThrough == null || res.account.coveredThrough!! < ym(2026, 12))
    }

Rename test:

    fun `1M January payment without staff allowlist uses normal allocation`()

to:

    fun `1M January payment without staff status uses normal allocation`()

No body change needed.

---

## 10. Update Technical Specification

File:

    docs/RDMS_Technical_Specification.md

Update section 2.3.1 opening paragraph to:

    One-time promotional lump sums **dated January 2026** on a clean account (`nextUnpaid == Jan 2026`, no outstanding penalty, no deposit) are **not** routed through §2.3 `allocateForward`. They use fixed profiles keyed by `gross_amount`. The staff profile additionally requires the house to be staff-eligible on the payment date according to the effective-dated `house_staff_status` history. Cash is priced at the **locked tariff** (Rp 100,000/full month, Rp 50,000/half-month, Rp 0/waived month) while report `expected_idr` remains `getDuesRate(period)`. Each covered dues row satisfies `amount + discount_applied = getDuesRate(period)`.

Change staff row from:

    | **Staff year** | 1,000,000 | Jan–Dec 2026 (allowlisted houses only) | 10 × 100k; **June & December waived** |

to:

    | **Staff year** | 1,000,000 | Jan–Dec 2026 (staff-eligible houses only) | 10 × 100k; **June & December waived** |

Add after House section:

    ### 3.2.1 Entity: HouseStaffStatus

    Effective-dated staff eligibility history for a house. This supports scheduled staff rotations and ad hoc changes without changing historical replay semantics.

    | Field | Type | Constraints | Description |
    |---|---|---|---|
    | `id` | UUID | PK | Surrogate primary key |
    | `house_id` | FK → House.id | NOT NULL | House whose staff status is recorded |
    | `effective_from` | DATE | NOT NULL | First date the status applies |
    | `effective_to` | DATE | NULLABLE | Exclusive end date; `NULL` means open-ended |
    | `staff_house` | BOOLEAN | NOT NULL | Whether the house is staff-eligible during the effective period |
    | `created_at` | TIMESTAMPTZ | NOT NULL | Record creation timestamp |
    | `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |
    | `version` | BIGINT | NOT NULL | Optimistic locking version |

    **Range rule:** `effective_from <= date < effective_to`. A `NULL effective_to` means the status remains active indefinitely.

    **No-overlap rule:** A house MUST NOT have overlapping staff-status periods.

Update section 6.4 step 0a to:

    0a. **January 2026 promotional profile** (§2.3.1): when `payment_date` is January 2026, `nextUnpaid == Jan 2026`, no outstanding penalty, and no deposit, try a matching promotional profile by `gross_amount`. The staff profile also requires staff eligibility on the payment date from `house_staff_status`. On match, allocate the profile's dues rows (`amount + discount_applied = getDuesRate(period)` per month) plus any deposit remainder, then skip steps 1–4 for this payment.

---

## 11. Optional ADR Update

File:

    docs/adr/0002-january-2026-promotional-profiles.md

If this ADR mentions hard-coded staff allowlists, update it to say:

    The staff year profile is gated by effective-dated staff eligibility from `house_staff_status`, evaluated on the payment date. Historical staff eligibility is not inferred from the current house row.

---

## 12. Optional Import Script Documentation Update

File:

    scripts/import_2026_data.py

The script can remain as-is for historical CSV generation.

Update docstring line from:

    - Rp 1.000.000 full-year on staff allowlist: one January payment (staff year).

to:

    - Rp 1.000.000 full-year for staff-eligible houses: one January payment (staff year).

Add comment above STAFF_HOUSE_KEYS:

    # Historical staff-eligible houses used only to generate the 2026 import CSV.
    # Runtime staff eligibility is stored in house_staff_status.

---

## 13. Run Checks

Run:

    ./mvnw test

or:

    mvn test

Search for stale references:

    promotionalHouseKey
    STAFF_HOUSE_KEYS
    houseKey =

Expected after implementation:

- promotionalHouseKey should no longer exist in Kotlin runtime code.
- STAFF_HOUSE_KEYS should no longer exist in Kotlin runtime code.
- houseKey = should no longer be passed to JanuaryRateLock.tryAllocate.
- STAFF_HOUSE_KEYS may remain in scripts/import_2026_data.py only as a historical CSV-generation helper.

Verify:

- Existing allocation tests pass.
- January 2026 staff promo still waives June and December for staff-eligible houses.
- January 2026 1_000_000 payment without staff eligibility uses normal allocation.
- Staff status starting after payment date does not qualify.
- Staff status ending on payment date does not qualify because effective_to is exclusive.
- Application starts successfully with Flyway migration.
- Existing payment replay paths no longer reference hard-coded staff house keys.

---
