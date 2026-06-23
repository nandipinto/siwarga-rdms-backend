"""
Transform 2025 laporan iuran (wide-format report) into RDMS payment import CSV.

Strategy:
- One payment row per checked month (preserves on-time coverage in the allocation engine).
- Rp 100,000 per paid month (2025 dues rate).
- Any cash above (paid_months × 100k) is added to the last paid month's gross_amount as deposit.
- House numbers are zero-padded to match houses-sample.csv (e.g. 02, 19A).

Outputs:
  bruno/csv-templates/payments-2025-import.csv  — POST /api/v1/payments/import
  scripts/laporan_iuran_2025_modified.csv        — human-readable audit trail
"""

from __future__ import annotations

from pathlib import Path

import pandas as pd

MONTHLY_RATE = 100_000
FULL_YEAR = frozenset(range(1, 13))

# (owner_name, blok, paid_months, total_amount_idr from laporan)
# paid_months: calendar months 1–12 with a checkmark in the report.
#
# Source: LAPORAN IURAN WARGA CLUSTER CARISSA RT 01 (2025)
#   Page 1 — rows 1–67  (A, B, C, D/01–D/16)
#   Page 2 — rows 68–103 (D/17+, E block; row 73 blank in laporan)
# All 102 houses in houses-sample.csv are covered.
raw_2025: list[tuple[str, str, frozenset[int], int]] = [
    ("Dinar/ Rheza", "A/02", FULL_YEAR, 1_200_000),
    ("Fajar", "A/06", FULL_YEAR, 1_100_000),
    ("Maya", "A/08", FULL_YEAR, 1_100_000),
    ("Alfen", "A/12", FULL_YEAR, 1_200_000),
    ("Tonny", "A/16", FULL_YEAR, 1_000_000),
    ("Nurman", "A/18", FULL_YEAR, 1_100_000),
    ("Opa Kusnadi", "A/20", FULL_YEAR, 1_200_000),
    ("Endra", "A/22", FULL_YEAR, 1_200_000),
    ("Ardi", "A/26", FULL_YEAR, 1_100_000),
    ("Iskandar", "B/01", FULL_YEAR, 1_100_000),
    ("Andi Barata/Imam", "B/02", FULL_YEAR, 1_200_000),
    ("Agus Setiawan", "B/03", FULL_YEAR, 1_150_000),
    ("Firman", "B/05", FULL_YEAR, 1_000_000),
    ("Agus Santoso/Ade", "B/06", FULL_YEAR, 1_100_000),
    ("Adi Martono", "B/07", FULL_YEAR, 1_000_000),
    ("Felix Lamuri", "B/08", FULL_YEAR, 1_200_000),
    ("Harris Firmansyah", "B/09", FULL_YEAR, 1_100_000),
    ("Budiono", "B/10", FULL_YEAR, 1_200_000),
    ("Akbariyadi", "B/11", FULL_YEAR, 1_200_000),
    ("Yadi", "B/12", FULL_YEAR, 1_200_000),
    ("Akbariyadi", "B/15", FULL_YEAR, 1_200_000),
    ("Sisca", "B/16", FULL_YEAR, 1_100_000),
    ("Ryan Karsten", "B/18", FULL_YEAR, 1_100_000),
    ("Akbariyadi", "B/19", FULL_YEAR, 1_200_000),
    ("Eko Sugiyarto", "B/20", FULL_YEAR, 1_200_000),
    ("Wayan", "B/21", FULL_YEAR, 1_000_000),
    ("Richardo", "B/22", FULL_YEAR, 1_100_000),
    ("Mega Herlina", "B/23", FULL_YEAR, 1_100_000),
    ("Azil Ady Permana", "B/25", FULL_YEAR, 1_100_000),
    # November unchecked in the laporan; all other months paid.
    ("Kresna", "B/27", frozenset({1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12}), 1_100_000),
    ("Hermawan", "B/29", FULL_YEAR, 1_200_000),
    ("Felex", "B/31", FULL_YEAR, 1_100_000),
    ("Rini Y", "B/33", FULL_YEAR, 1_200_000),
    ("Eni/ Arhadi", "B/35", FULL_YEAR, 1_200_000),
    ("Sugeng Kuswantoro", "B/37", FULL_YEAR, 1_200_000),
    ("Abdul Rachman", "C/01", FULL_YEAR, 1_000_000),
    ("Akbar/Subur", "C/02", FULL_YEAR, 1_200_000),
    ("Burhan", "C/03", FULL_YEAR, 1_100_000),
    ("Bambang W", "C/05", FULL_YEAR, 1_200_000),
    ("Ibu Anjel", "C/06", FULL_YEAR, 1_100_000),
    ("Ade", "C/07", FULL_YEAR, 1_200_000),
    ("Wawan", "C/08", FULL_YEAR, 1_200_000),
    ("Basuki Rahmat", "C/09", FULL_YEAR, 1_150_000),
    ("Robin", "C/10", FULL_YEAR, 1_200_000),
    ("Ika", "C/11", FULL_YEAR, 1_200_000),
    ("Dewi/Kris", "C/12", FULL_YEAR, 1_000_000),
    ("Iwan", "C/15", FULL_YEAR, 1_100_000),
    ("Irsyad", "C/16", FULL_YEAR, 1_000_000),
    ("Daniel", "C/17", FULL_YEAR, 1_000_000),
    ("Ade", "C/18", FULL_YEAR, 1_250_000),
    ("Palito", "C/19", FULL_YEAR, 1_100_000),
    ("Niko", "C/20", FULL_YEAR, 1_200_000),
    ("Yudi", "C/22", FULL_YEAR, 1_000_000),
    ("Badru Jamal", "C/26", FULL_YEAR, 1_200_000),
    ("M. Andri", "D/01", FULL_YEAR, 1_100_000),
    ("Linda Fanny", "D/02", FULL_YEAR, 1_100_000),
    ("Victor Manurung", "D/03", FULL_YEAR, 1_200_000),
    ("Bagus Suropratomo", "D/05", FULL_YEAR, 1_150_000),
    ("Ifur", "D/06", FULL_YEAR, 1_100_000),
    ("Rudiyanto Sulardi", "D/07", FULL_YEAR, 1_000_000),
    ("Zaenullah M", "D/08", FULL_YEAR, 1_100_000),
    ("Donny", "D/09", FULL_YEAR, 1_200_000),
    ("Cok Putra Tri Utama", "D/10", FULL_YEAR, 1_100_000),
    ("Sukiman", "D/11", FULL_YEAR, 1_200_000),
    ("Isaac", "D/12", FULL_YEAR, 1_200_000),
    ("Haris Susanto", "D/15", FULL_YEAR, 1_100_000),
    ("Suryanto", "D/16", FULL_YEAR, 1_100_000),  # page 1 row 67
    # --- page 2 (rows 68–103) ---
    ("Ronny", "D/17", FULL_YEAR, 1_100_000),
    ("Agus Prasetyono", "D/18", FULL_YEAR, 1_200_000),
    ("Pandu Teguh", "D/19", FULL_YEAR, 1_200_000),
    ("Yunita/Donny", "D/19A", FULL_YEAR, 1_200_000),
    ("Ulin Niam Yusron", "D/20", FULL_YEAR, 1_150_000),
    ("Adhi Kirana", "D/26", FULL_YEAR, 1_200_000),
    ("Yuki/Sofian", "D/28", FULL_YEAR, 1_200_000),
    ("Dimas", "D/30", FULL_YEAR, 1_150_000),
    ("Mamat", "D/30A", FULL_YEAR, 1_200_000),
    ("Andy/Jason", "E/01", FULL_YEAR, 1_200_000),
    ("Ivan Faturahman", "E/02", FULL_YEAR, 1_100_000),
    ("Sudarmanta", "E/03", FULL_YEAR, 1_200_000),
    ("Fathan", "E/05", FULL_YEAR, 1_100_000),
    ("Bambang/Kusnadi", "E/06", FULL_YEAR, 1_200_000),
    ("Agusvian Marano", "E/07", FULL_YEAR, 1_200_000),
    ("Rina", "E/08", FULL_YEAR, 1_000_000),
    ("Nerju", "E/09", FULL_YEAR, 1_100_000),
    ("Ichsanul Fachri/ Rein", "E/10", FULL_YEAR, 1_050_000),
    ("Danny Andrian", "E/11", FULL_YEAR, 1_200_000),
    ("Joshua", "E/12", FULL_YEAR, 1_200_000),
    ("Wisa Arbi", "E/15", FULL_YEAR, 1_100_000),
    ("Iman K. Rahmanto", "E/16", FULL_YEAR, 1_200_000),
    ("Muh. Nasir", "E/17", FULL_YEAR, 1_200_000),
    ("Muhammad Ihsan", "E/18", FULL_YEAR, 1_100_000),
    # Jan–Aug only (page 2 row 93).
    ("Arroqy", "E/19", frozenset(range(1, 9)), 800_000),
    ("Taufik Nandipinto", "E/20", FULL_YEAR, 1_100_000),
    ("Hendra N. Purba", "E/21", FULL_YEAR, 1_000_000),
    ("Nugraha Dentista P", "E/22", FULL_YEAR, 1_100_000),
    ("Fajar Wahyu Dani", "E/23", FULL_YEAR, 1_100_000),
    ("Josep", "E/25", FULL_YEAR, 1_200_000),
    # Jan–Jul only (page 2 row 99).
    ("Arkan", "E/26", frozenset(range(1, 8)), 700_000),
    ("Ariyadi Panigoro", "E/28", FULL_YEAR, 1_100_000),
    ("Ervano/Azhari", "E/30", FULL_YEAR, 1_200_000),
    ("Agus Tambiwijaya", "E/32", FULL_YEAR, 1_200_000),
    ("Andika Danar K/ Sari", "E/36", FULL_YEAR, 1_200_000),  # page 2 row 103
]

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
RDMS_CSV = REPO_ROOT / "bruno" / "csv-templates" / "payments-2025-import.csv"
AUDIT_CSV = SCRIPT_DIR / "laporan_iuran_2025_modified.csv"


def parse_blok(blok_full: str) -> tuple[str, str]:
    if "/" not in blok_full:
        raise ValueError(f"Invalid blok format: {blok_full!r}")
    block_code, house_number = blok_full.split("/", 1)
    return block_code.strip(), normalize_house_number(house_number.strip())


def normalize_house_number(value: str) -> str:
    if value.isdigit():
        return value.zfill(2)
    return value.upper()


def payment_note(surplus: int) -> str:
    if surplus > 0:
        return f"Imported laporan 2025 (+{surplus} deposit)"
    return "Imported laporan 2025"


def generate_payment_rows(
    name: str,
    block_code: str,
    house_number: str,
    paid_months: frozenset[int],
    total_amt: int,
) -> tuple[list[dict], list[dict]]:
    """Return (rdms_rows, audit_rows)."""
    months = sorted(paid_months)
    if not months:
        return [], []

    base_dues = len(months) * MONTHLY_RATE
    surplus = total_amt - base_dues

    rdms_rows: list[dict] = []
    audit_rows: list[dict] = []

    for i, month in enumerate(months):
        amount = MONTHLY_RATE
        note = payment_note(0)
        if i == len(months) - 1 and surplus > 0:
            amount += surplus
            note = payment_note(surplus)

        payment_date = f"2025-{month:02d}-01"
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

    for name, blok_full, paid_months, total_amt in raw_2025:
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

    kresna = df_rdms[(df_rdms["block_code"] == "B") & (df_rdms["house_number"] == "27")]
    arroqy = df_rdms[(df_rdms["block_code"] == "E") & (df_rdms["house_number"] == "19")]
    arkan = df_rdms[(df_rdms["block_code"] == "E") & (df_rdms["house_number"] == "26")]
    deposit_rows = df_rdms[df_rdms["note"].str.contains("deposit", na=False)]

    print(f"Wrote {len(df_rdms)} payment rows for {len(raw_2025)} houses")
    print(f"  RDMS import: {RDMS_CSV}")
    print(f"  Audit trail: {AUDIT_CSV}")
    print("\nKresna B/27 (November should be absent):")
    print(kresna[["payment_date", "gross_amount"]].to_string(index=False))
    print("\nArroqy E/19 (Jan–Aug only):")
    print(arroqy[["payment_date", "gross_amount"]].to_string(index=False))
    print("\nArkan E/26 (Jan–Jul only):")
    print(arkan[["payment_date", "gross_amount"]].to_string(index=False))
    print(f"\nDeposit rows: {len(deposit_rows)}")
    if not deposit_rows.empty:
        print(deposit_rows[["block_code", "house_number", "payment_date", "gross_amount"]].head(3).to_string(index=False))


if __name__ == "__main__":
    main()
