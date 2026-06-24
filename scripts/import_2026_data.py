"""
Transform 2026 laporan iuran into RDMS payment import CSV.

Strategy (ADR-0002):
- Rp 550.000: one January payment (6-month Jan prepay).
- Rp 1.000.000 full-year for staff-eligible houses: one January payment (staff year).
- Rp 1.100.000 full-year: one January payment (year package).
- Full-year gross > Rp 1.100.000: one January payment (year package + deposit).
- Other rows: one payment per checked month at Jan Rp 100k / Feb+ Rp 120k.
- Surplus cash on the last paid month becomes deposit.
- House numbers zero-padded to match houses-sample.csv.

Outputs:
  bruno/csv-templates/payments-2026-import.csv
  scripts/laporan_iuran_2026_modified.csv
"""

from __future__ import annotations

from pathlib import Path

import pandas as pd

LEGACY_RATE = 100_000
CURRENT_RATE = 120_000
SIX_MONTH_GROSS = 550_000
STAFF_YEAR_GROSS = 1_000_000
YEAR_PACKAGE_GROSS = 1_100_000
FULL_YEAR = frozenset(range(1, 13))
SIX_MONTHS = frozenset(range(1, 7))

# Historical staff-eligible houses used only to generate the 2026 import CSV.
# Runtime staff eligibility is stored in house_staff_status.
STAFF_HOUSE_KEYS = frozenset(
    {
        "A/16",
        "B/05",
        "B/07",
        "D/16",
        "E/08",
    }
)

# (owner_name, blok, paid_months, total_amount_idr from laporan)
# Source: LAPORAN IURAN WARGA CLUSTER CARISSA RT 01 (2026)
#   Page 1 — rows 1–64  (through Sukiman D/11)
#   Page 2 — rows 65–103 (Isaac D/12 through Andika E/36)
# Houses with no checkmarks in the laporan are omitted.
raw_2026: list[tuple[str, str, frozenset[int], int]] = [
    ("Dinar/ Rheza", "A/02", frozenset(range(1, 7)), 660_000),
    ("Fajar", "A/06", FULL_YEAR, 1_100_000),
    ("Maya", "A/08", FULL_YEAR, 1_100_000),
    ("Alfen", "A/12", FULL_YEAR, 1_100_000),
    ("Tonny", "A/16", FULL_YEAR, 1_000_000),
    ("Nurman", "A/18", SIX_MONTHS, 550_000),
    ("Opa Kusnadi", "A/20", frozenset(range(1, 7)), 660_000),
    ("Endra", "A/22", FULL_YEAR, 1_320_000),
    ("Ardi", "A/26", frozenset(range(1, 8)), 650_000),
    ("Iskandar", "B/01", FULL_YEAR, 1_100_000),
    ("Andi Barata/Imam", "B/02", FULL_YEAR, 1_100_000),
    ("Agus Setiawan", "B/03", FULL_YEAR, 1_300_000),
    ("Firman", "B/05", FULL_YEAR, 1_000_000),
    ("Agus Santoso/Ade", "B/06", SIX_MONTHS, 550_000),
    ("Adi Martono", "B/07", FULL_YEAR, 1_000_000),
    ("Felix Lamuri", "B/08", frozenset({1, 2, 3}), 300_000),
    ("Rida Bindiar", "B/09", frozenset(range(1, 7)), 660_000),
    ("Nopiek", "B/10", frozenset(range(1, 6)), 540_000),
    ("Akbariyadi", "B/11", frozenset(range(1, 6)), 540_000),
    ("Akbariyadi", "B/15", frozenset(range(1, 6)), 540_000),
    ("Ryan Karsten", "B/18", FULL_YEAR, 1_260_000),
    ("Akbariyadi", "B/19", frozenset(range(1, 6)), 540_000),
    ("Eko Sugiyarto", "B/20", frozenset(range(1, 8)), 780_000),
    ("Wayan", "B/21", FULL_YEAR, 1_100_000),
    ("Richardo", "B/22", SIX_MONTHS, 550_000),
    ("Mega Herlina", "B/23", FULL_YEAR, 1_100_000),
    ("Azil Ady Permana", "B/25", FULL_YEAR, 1_100_000),
    ("Hermawan", "B/29", frozenset(range(1, 7)), 660_000),
    ("Felex", "B/31", FULL_YEAR, 1_100_000),
    ("Rini Y", "B/33", frozenset(range(1, 5)), 400_000),
    ("Eni/ Arhadi", "B/35", frozenset(range(1, 7)), 660_000),
    ("Abdul Rachman", "C/01", frozenset(range(1, 6)), 560_000),
    ("Akbar/Subur", "C/02", FULL_YEAR, 1_320_000),
    ("Burhan", "C/03", FULL_YEAR, 1_100_000),
    ("Bambang W", "C/05", frozenset(range(1, 7)), 660_000),
    ("Ibu Anjel", "C/06", frozenset({1, 2}), 200_000),
    ("Ade", "C/07", frozenset(range(1, 8)), 780_000),
    ("Wawan", "C/08", frozenset(range(1, 7)), 660_000),
    ("Basuki Rahmat", "C/09", FULL_YEAR, 1_100_000),
    ("Robin", "C/10", SIX_MONTHS, 550_000),
    ("Ika", "C/11", frozenset(range(1, 7)), 660_000),
    ("Dewi/Kris", "C/12", FULL_YEAR, 1_210_000),
    ("Iwan", "C/15", FULL_YEAR, 1_100_000),
    ("Irsyad", "C/16", frozenset(range(1, 6)), 540_000),
    ("Palito", "C/19", SIX_MONTHS, 550_000),
    ("Yudi", "C/22", frozenset(range(1, 7)), 660_000),
    ("M. Andri", "D/01", FULL_YEAR, 1_100_000),
    ("Linda Fanny", "D/02", FULL_YEAR, 1_100_000),
    ("Victor Manurung", "D/03", FULL_YEAR, 1_210_000),
    ("Bagus Suropratomo", "D/05", FULL_YEAR, 1_100_000),
    ("Ifur", "D/06", FULL_YEAR, 1_260_000),
    ("Rudiyanto Sulardi", "D/07", FULL_YEAR, 1_100_000),
    ("Zaenullah M", "D/08", FULL_YEAR, 1_100_000),
    ("Donny", "D/09", FULL_YEAR, 1_100_000),
    ("Cok Putra Tri Utama", "D/10", FULL_YEAR, 1_100_000),
    ("Sukiman", "D/11", frozenset(range(1, 10)), 960_000),  # page 1 row 64
    # --- page 2 (rows 65–103) ---
    ("Isaac", "D/12", frozenset(range(1, 6)), 540_000),
    ("Haris Susanto", "D/15", FULL_YEAR, 1_100_000),
    ("Suryanto", "D/16", FULL_YEAR, 1_000_000),
    ("Ronny", "D/17", SIX_MONTHS, 550_000),
    ("Agus Prasetyono", "D/18", FULL_YEAR, 1_100_000),
    ("Pandu Teguh", "D/19", FULL_YEAR, 1_100_000),
    ("Yunita/Donny", "D/19A", FULL_YEAR, 1_100_000),
    ("Ulin Niam Yusron", "D/20", FULL_YEAR, 1_100_000),
    # D/22 not in houses-sample.csv; included for laporan completeness.
    ("Kelvin Yerry Putra", "D/22", frozenset({1, 2, 3}), 300_000),
    ("Adhi Kirana", "D/26", SIX_MONTHS, 550_000),
    ("Yuki/Sofian/Bayu", "D/28", FULL_YEAR, 1_320_000),
    ("Dimas", "D/30", FULL_YEAR, 1_260_000),
    ("Mamat", "D/30A", FULL_YEAR, 1_200_000),
    ("Sudarmanta", "E/01", frozenset(range(1, 7)), 660_000),
    ("Ivan Faturahman", "E/02", FULL_YEAR, 1_260_000),
    ("Ammar Permady", "E/03", frozenset(range(1, 7)), 660_000),
    ("Fathan", "E/05", FULL_YEAR, 1_320_000),
    ("Bambang/Nanette", "E/06", frozenset(range(1, 7)), 660_000),
    ("Agusvian Marano", "E/07", frozenset({1, 2, 3}), 660_000),
    ("Rina", "E/08", FULL_YEAR, 1_000_000),
    ("Nerju", "E/09", FULL_YEAR, 1_100_000),
    ("Ichsanul Fachri/ Rein", "E/10", SIX_MONTHS, 550_000),
    ("Danny Andrian", "E/11", frozenset(range(1, 7)), 660_000),
    ("Joshua", "E/12", frozenset(range(1, 5)), 420_000),
    ("Wisa Arbi", "E/15", frozenset(range(1, 8)), 720_000),
    ("Iman K. Rahmanto", "E/16", frozenset(range(1, 10)), 960_000),
    ("Muh. Nasir", "E/17", FULL_YEAR, 1_100_000),
    ("Muhammad Ihsan", "E/18", FULL_YEAR, 1_100_000),
    ("Taufik Nandipinto", "E/20", frozenset(range(1, 7)), 660_000),
    ("Nugraha Dentista P", "E/22", FULL_YEAR, 1_100_000),
    ("Fajar Wahyu Dani", "E/23", FULL_YEAR, 1_100_000),
    ("Josep", "E/25", frozenset({1}), 100_000),
    ("Ariyadi Panigoro", "E/28", frozenset({1, 2, 3}), 660_000),
    ("Ervano/Azhari", "E/30", frozenset({1, 2, 3}), 600_000),
    ("Agus Tambiwijaya", "E/32", frozenset(range(1, 7)), 660_000),
    ("Andika Danar K/ Sari", "E/36", frozenset(range(1, 6)), 540_000),
]

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
RDMS_CSV = REPO_ROOT / "bruno" / "csv-templates" / "payments-2026-import.csv"
AUDIT_CSV = SCRIPT_DIR / "laporan_iuran_2026_modified.csv"
YEAR = 2026


def dues_rate(month: int) -> int:
    return LEGACY_RATE if month == 1 else CURRENT_RATE


def base_dues(paid_months: frozenset[int]) -> int:
    return sum(dues_rate(m) for m in paid_months)


def parse_blok(blok_full: str) -> tuple[str, str]:
    if "/" not in blok_full:
        raise ValueError(f"Invalid blok format: {blok_full!r}")
    block_code, house_number = blok_full.split("/", 1)
    return block_code.strip(), normalize_house_number(house_number.strip())


def normalize_house_number(value: str) -> str:
    if value.isdigit():
        return value.zfill(2)
    return value.upper()


def house_key(block_code: str, house_number: str) -> str:
    return f"{block_code}/{house_number}"


def payment_note(surplus: int) -> str:
    if surplus > 0:
        return f"Imported laporan {YEAR} (+{surplus} deposit)"
    return f"Imported laporan {YEAR}"


def january_promo_note(label: str) -> str:
    return f"Imported laporan {YEAR} ({label})"


def single_january_row(
    name: str,
    block_code: str,
    house_number: str,
    gross_amount: int,
    note: str,
) -> tuple[list[dict], list[dict]]:
    payment_date = f"{YEAR}-01-01"
    rdms_row = {
        "block_code": block_code,
        "house_number": house_number,
        "payment_date": payment_date,
        "gross_amount": gross_amount,
        "note": note,
    }
    audit_row = {
        "Nama": name,
        "Blok": block_code,
        "Nomor Rumah": house_number,
        "Tanggal Bayar": payment_date,
        "Jumlah Bayar": gross_amount,
        "Keterangan": note,
    }
    return [rdms_row], [audit_row]


def generate_payment_rows(
    name: str,
    block_code: str,
    house_number: str,
    paid_months: frozenset[int],
    total_amt: int,
) -> tuple[list[dict], list[dict]]:
    key = house_key(block_code, house_number)

    if total_amt == SIX_MONTH_GROSS:
        return single_january_row(
            name,
            block_code,
            house_number,
            SIX_MONTH_GROSS,
            january_promo_note("Jan 6-month prepay"),
        )

    if paid_months == FULL_YEAR and total_amt == STAFF_YEAR_GROSS and key in STAFF_HOUSE_KEYS:
        return single_january_row(
            name,
            block_code,
            house_number,
            STAFF_YEAR_GROSS,
            january_promo_note("Jan staff year"),
        )

    if paid_months == FULL_YEAR and total_amt == YEAR_PACKAGE_GROSS:
        return single_january_row(
            name,
            block_code,
            house_number,
            YEAR_PACKAGE_GROSS,
            january_promo_note("Jan year package"),
        )

    if paid_months == FULL_YEAR and total_amt > YEAR_PACKAGE_GROSS:
        deposit = total_amt - YEAR_PACKAGE_GROSS
        return single_january_row(
            name,
            block_code,
            house_number,
            total_amt,
            january_promo_note(f"Jan year package +{deposit} deposit"),
        )

    months = sorted(paid_months)
    if not months:
        return [], []

    expected = base_dues(paid_months)
    surplus = total_amt - expected

    rdms_rows: list[dict] = []
    audit_rows: list[dict] = []

    for i, month in enumerate(months):
        amount = dues_rate(month)
        note = payment_note(0)
        if i == len(months) - 1 and surplus > 0:
            amount += surplus
            note = payment_note(surplus)

        payment_date = f"{YEAR}-{month:02d}-01"
        rdms_rows.append(
            {
                "block_code": block_code,
                "house_number": house_number,
                "payment_date": payment_date,
                "gross_amount": amount,
                "note": note,
            }
        )
        audit_rows.append(
            {
                "Nama": name,
                "Blok": block_code,
                "Nomor Rumah": house_number,
                "Tanggal Bayar": payment_date,
                "Jumlah Bayar": amount,
                "Keterangan": note,
            }
        )

    return rdms_rows, audit_rows


def main() -> None:
    rdms_rows: list[dict] = []
    audit_rows: list[dict] = []

    for name, blok_full, paid_months, total_amt in raw_2026:
        block_code, house_number = parse_blok(blok_full)
        house_rdms, house_audit = generate_payment_rows(
            name, block_code, house_number, paid_months, total_amt
        )
        rdms_rows.extend(house_rdms)
        audit_rows.extend(house_audit)

    df_rdms = pd.DataFrame(rdms_rows)
    df_audit = pd.DataFrame(audit_rows)

    RDMS_CSV.parent.mkdir(parents=True, exist_ok=True)
    df_rdms.to_csv(RDMS_CSV, index=False)
    df_audit.to_csv(AUDIT_CSV, index=False)

    promo_rows = df_rdms[df_rdms["note"].str.contains("Jan ", na=False)]
    deposit_rows = df_rdms[df_rdms["note"].str.contains("deposit", na=False)]

    print(f"Wrote {len(df_rdms)} payment rows for {len(raw_2026)} houses")
    print(f"  RDMS import: {RDMS_CSV}")
    print(f"  Audit trail: {AUDIT_CSV}")
    print(f"  January promotional rows: {len(promo_rows)}")
    print(f"  Monthly-drip deposit rows: {len(deposit_rows)}")
    print("\nFajar A/06 (year package):")
    print(
        df_rdms[(df_rdms["block_code"] == "A") & (df_rdms["house_number"] == "06")][
            ["payment_date", "gross_amount", "note"]
        ].to_string(index=False)
    )
    print("\nEndra A/22 (year package + deposit):")
    print(
        df_rdms[(df_rdms["block_code"] == "A") & (df_rdms["house_number"] == "22")][
            ["payment_date", "gross_amount", "note"]
        ].to_string(index=False)
    )
    print("\nTonny A/16 (staff year):")
    print(
        df_rdms[(df_rdms["block_code"] == "A") & (df_rdms["house_number"] == "16")][
            ["payment_date", "gross_amount", "note"]
        ].to_string(index=False)
    )


if __name__ == "__main__":
    main()
