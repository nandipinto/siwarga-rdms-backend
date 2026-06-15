# Resident Dues Management System — Web Application Specification

> **Document Status:** Draft v1.1
> **Prepared for:** AI Coding Agents / Frontend Developers
> **Effective Date:** 2026-06-13
> **Last Updated:** 2026-06-13
> **Companion Document:** [RDMS Technical Specification](./RDMS_Technical_Specification.md) (backend v1.4)
> **Repository:** Separate frontend repo (`siwarga-rdms-frontend` — proposed name)

---

## Revision History

| Version | Date | Changes |
|---|---|---|
| v1.0 | 2026-06-13 | Initial web application specification. Design decisions resolved via structured review: staff-only v1 (resident portal deferred), React + TypeScript + Vite, Indonesian UI, responsive balanced layout, full backend parity, sessionStorage JWT + idle timeout, separate-repo static deployment, print CSS for guarantee documents, prepayment calculator (advisory), role-based dashboard, house detail hub with tabs. |
| v1.1 | 2026-06-13 | Added §15.5 Single VPS Deployment — architecture, minimum/recommended hardware, Nginx reverse proxy, Docker Compose, security, backups. |

---

## Table of Contents

1. [Purpose & Scope](#1-purpose--scope)
2. [Design Decisions Summary](#2-design-decisions-summary)
3. [Users & Access Control](#3-users--access-control)
4. [Technical Stack](#4-technical-stack)
5. [Architecture](#5-architecture)
6. [Authentication & Session](#6-authentication--session)
7. [Locale & Formatting](#7-locale--formatting)
8. [Information Architecture](#8-information-architecture)
9. [Screen Specifications](#9-screen-specifications)
10. [Domain UX Rules](#10-domain-ux-rules)
11. [Print Templates](#11-print-templates)
12. [API Integration](#12-api-integration)
13. [Error Handling](#13-error-handling)
14. [Non-Functional Requirements](#14-non-functional-requirements)
15. [Deployment & Configuration](#15-deployment--configuration)
16. [Implementation Priorities](#16-implementation-priorities)
17. [Future Extensions (Out of Scope v1)](#17-future-extensions-out-of-scope-v1)
18. [Glossary](#18-glossary)

---

## 1. Purpose & Scope

This document defines the complete functional and technical requirements for the **RDMS Web Application** — a modern staff-facing SPA that consumes the existing RDMS backend REST API.

### 1.1 In Scope (v1)

- Internal web UI for **Administrator** and **Supervisor** roles only.
- Full functional parity with backend capabilities defined in the companion specification (v1.4), plus **RW management** exposed by the implemented backend (`/api/v1/rws`).
- Indonesian-language UI with `id-ID` formatting.
- Responsive layout optimised for both office desktop use and mobile field use (payment recording).

### 1.2 Out of Scope (v1)

- Resident / owner self-service portal (login, balance view, online payment).
- English UI / i18n framework.
- Client-side or server-side PDF generation (print uses browser Print CSS).
- Payment gateway integration.
- Offline / PWA / native mobile apps.
- Business-rule reimplementation in the frontend — **the backend calculation engine remains canonical** (companion spec §6).

### 1.3 Source of Truth

| Concern | Authoritative document |
|---|---|
| Business rules (dues, penalties, discounts, rental guarantee) | Backend spec §2, §6 |
| REST endpoints & payloads | Backend spec §5 + implemented OpenAPI at `/swagger-ui.html` |
| Role permissions | Backend spec §4.2 + `SecurityConfig` |
| UI behaviour, layout, navigation, advisory calculators | **This document** |

---

## 2. Design Decisions Summary

| # | Decision | Choice |
|---|---|---|
| 1 | Primary audience | Staff-only v1; resident portal deferred |
| 2 | Frontend stack | React 18+ · TypeScript · Vite · TanStack Query · React Router · Tailwind CSS · shadcn/ui |
| 3 | Language | Indonesian only (`id-ID`) |
| 4 | Responsive strategy | Balanced — sidebar desktop, drawer mobile; min width 360px |
| 5 | Feature scope | Full backend parity (all modules) |
| 6 | Auth storage | JWT in `sessionStorage` + configurable idle timeout |
| 7 | Repository | Separate repo; static hosting; `VITE_API_BASE_URL` env |
| 8 | Printable documents | Print CSS (`@media print`) for guarantee receipt & refund |
| 9 | Payment entry UX | Form + account summary + advisory prepayment calculator |
| 10 | Dashboard | Role-based widgets and quick actions |
| 11 | House detail | Single hub page `/houses/:id` with tabs |

---

## 3. Users & Access Control

### 3.1 Roles

Roles mirror the backend exactly. The frontend **must not** expose navigation or actions the user's role cannot perform — backend enforcement remains the final gate.

| Role | Indonesian label | Capabilities in UI |
|---|---|---|
| **ADMINISTRATOR** | Administrator | Full access — RW, RT, houses, users, imports, payments, guarantees, refunds, reports |
| **SUPERVISOR** | Supervisor | Payments, guarantee receipts/refunds, reports only |

### 3.2 Navigation Visibility Matrix

| Module | Route prefix | ADMINISTRATOR | SUPERVISOR |
|---|---|---|---|
| Dashboard | `/` | ✅ | ✅ (supervisor variant) |
| RW Management | `/rws` | ✅ | ❌ |
| RT Management | `/rts` | ✅ | ❌ |
| Houses | `/houses` | ✅ | ✅ (read-only list + detail) |
| Payments | `/payments` | ✅ | ✅ |
| Rental Guarantee | `/rental-guarantee` | ✅ | ✅ |
| Refunds | `/rental-guarantee/refunds` | ✅ | ✅ |
| Reports | `/reports` | ✅ | ✅ |
| Users | `/users` | ✅ | ❌ |
| CSV Import | `/import` | ✅ | ❌ |

> **NOTE:** Supervisors can **read** house list and detail (needed for payment context) but **cannot** create, update, or import houses. Hide edit/create/import controls; backend returns 403 if invoked.

### 3.3 Route Guards

- Unauthenticated → redirect to `/login`.
- Authenticated Supervisor visiting admin-only route → show **403 Halaman tidak tersedia** page with link back to dashboard.
- JWT expiry or 401 response → clear session, redirect to `/login` with toast *Sesi berakhir, silakan masuk kembali*.

---

## 4. Technical Stack

| Component | Requirement |
|---|---|
| **Runtime** | Node.js ≥ 20 LTS |
| **Framework** | React 18+ with TypeScript (strict mode) |
| **Build** | Vite 5+ |
| **Routing** | React Router 6+ |
| **Server state** | TanStack Query v5 |
| **HTTP client** | Axios or `fetch` wrapper with interceptors |
| **Forms** | React Hook Form + Zod validation |
| **Styling** | Tailwind CSS 3+ |
| **Component library** | shadcn/ui (Radix primitives) |
| **Icons** | Lucide React |
| **Date handling** | `date-fns` with `id` locale |
| **API types** | Generated from OpenAPI (openapi-typescript or orval) |
| **Testing** | Vitest + React Testing Library + MSW (Mock Service Worker) |
| **Linting** | ESLint + Prettier |

---

## 5. Architecture

### 5.1 Project Structure (proposed)

```
siwarga-rdms-frontend/
├── public/
├── src/
│   ├── api/              # Generated types + API client functions
│   ├── auth/             # AuthContext, sessionStorage helpers, idle timer
│   ├── components/       # Shared UI (DataTable, MoneyInput, PageHeader, …)
│   ├── features/         # Feature modules (houses, payments, …)
│   │   ├── dashboard/
│   │   ├── rws/
│   │   ├── rts/
│   │   ├── houses/
│   │   ├── payments/
│   │   ├── rental-guarantee/
│   │   ├── reports/
│   │   ├── users/
│   │   └── import/
│   ├── hooks/            # useAuth, useIdleTimeout, useFormatIdr, …
│   ├── layouts/          # AppShell (sidebar/drawer), AuthLayout
│   ├── lib/              # formatters, cn(), queryClient
│   ├── pages/            # Route entry components (thin wrappers)
│   ├── print/            # Print-only layouts (RG receipt, RF refund)
│   └── routes/           # Route definitions + guards
├── index.html
├── vite.config.ts
├── tailwind.config.ts
└── .env.example
```

### 5.2 Data Flow Principles

1. **Fetch via TanStack Query** — cache keys include entity id and filter params; invalidate on mutations.
2. **Mutate via API client** — on success, invalidate affected queries (`houses`, `payments`, `reports/arrears`, etc.).
3. **Never compute final allocations client-side** — display backend response after payment create/update.
4. **Advisory calculators** (prepayment suggestions) are explicitly labelled *perkiraan* and may diverge from backend when clearing arrears/penalties.

### 5.3 App Shell

| Breakpoint | Navigation |
|---|---|
| `≥ lg` (1024px) | Fixed left sidebar — logo, role badge, nav links, logout |
| `< lg` | Top bar with hamburger → slide-over drawer; bottom padding for thumb reach on primary actions |

---

## 6. Authentication & Session

### 6.1 Login Flow

**Screen:** `/login`

| Field | Validation |
|---|---|
| Username | Required |
| Password | Required |

**API:** `POST /api/v1/auth/login`

```json
{ "username": "admin", "password": "••••••" }
```

**Response:**

```json
{ "token": "<JWT>", "role": "ADMINISTRATOR", "username": "admin" }
```

**On success:**

1. Store in `sessionStorage`: `token`, `role`, `username`.
2. Redirect to `/` (dashboard).
3. Start idle-timeout watcher.

**On failure (401):** Inline error *Nama pengguna atau kata sandi salah*.

### 6.2 Session Persistence

| Mechanism | Behaviour |
|---|---|
| **Storage** | `sessionStorage` only — cleared when browser tab/window closes |
| **Idle timeout** | Default 30 minutes; reset on user interaction (click, keypress, scroll, touch) |
| **Env config** | `VITE_SESSION_IDLE_MINUTES=30` |
| **On timeout** | Clear session → redirect `/login` → toast *Sesi diakhiri karena tidak aktif* |

### 6.3 HTTP Interceptor

Every authenticated request:

```
Authorization: Bearer <token>
```

On `401` (except login): clear session, redirect to login.

### 6.4 Logout

- Sidebar/drawer **Keluar** button.
- Clears `sessionStorage`, cancels idle timer, redirects to `/login`.

---

## 7. Locale & Formatting

All UI copy is **Bahasa Indonesia**. Domain abbreviations may remain as-is (RT, RW, IDR).

### 7.1 Currency (IDR)

- Backend values are **integer IDR** (no decimals).
- Display: `Rp 120.000` — prefix `Rp`, dot as thousands separator, no decimal places.
- Input: accept digits only; strip formatting on submit.
- Helper: `formatIdr(120000) → "Rp 120.000"`.

### 7.2 Dates

| Context | Format | Example |
|---|---|---|
| Form inputs | `YYYY-MM-DD` (ISO, native date picker) | `2026-02-01` |
| Table/list display | `d MMMM yyyy` (Indonesian) | `1 Februari 2026` |
| Period labels | `MMMM yyyy` | `Februari 2026` |
| API payloads | ISO 8601 date strings per backend spec §5.1 | |

### 7.3 Known Rate Constants (display reference)

Mirrors backend `DuesRate` (companion spec §6.1):

| Period | Monthly dues |
|---|---|
| Before Feb 2026 | Rp 100.000 |
| Feb 2026 onward | Rp 120.000 |

Use these in advisory calculators and form hints only.

---

## 8. Information Architecture

### 8.1 Sitemap

```
/login
/                          Dashboard (role-based)
/rws                       RW list + CRUD
/rws/:id                   RW detail (optional; can be modal from list)
/rts                       RT list + CRUD
/rts/:id                   RT detail
/houses                    House list (filterable)
/houses/new                Create house (Admin)
/houses/:id                House hub (tabs)
/houses/:id/edit           Edit house (Admin)
/payments                  Payment list
/payments/new              Record payment
/payments/:id              Payment detail + allocations
/rental-guarantee          Guarantee receipt list
/rental-guarantee/new      Record guarantee payment
/rental-guarantee/:id      Receipt detail (+ print)
/rental-guarantee/refunds  Refund list
/rental-guarantee/refunds/:id  Refund detail (+ complete/cancel + print)
/reports/dues              Monthly dues report
/reports/arrears           Arrears lookup (by house)
/users                     User list + CRUD (Admin)
/import                    CSV import hub (Admin)
```

### 8.2 Primary Navigation (Administrator)

1. Beranda
2. RW
3. RT
4. Rumah
5. Pembayaran Iuran
6. Jaminan Sewa
7. Pengembalian Jaminan
8. Laporan
9. Pengguna
10. Impor Data

### 8.3 Primary Navigation (Supervisor)

1. Beranda
2. Rumah *(read-only)*
3. Pembayaran Iuran
4. Jaminan Sewa
5. Pengembalian Jaminan
6. Laporan

---

## 9. Screen Specifications

### 9.1 Dashboard — `/`

Role-specific landing page after login.

#### 9.1.1 Administrator Widgets

| Widget | Data source | Action |
|---|---|---|
| Iuran bulan berjalan | `GET /reports/dues/monthly` (current month) | Link → Laporan Iuran |
| Refund menunggu | `GET /rental-guarantee/refunds?status=PENDING` (count) | Link → Refund list filtered |
| Jaminan belum lunas | Derive from house list or guarantee list where status UNPAID | Link → Jaminan Sewa filtered |
| Shortcut cards | — | Tambah Rumah, Catat Pembayaran, Impor CSV |

#### 9.1.2 Supervisor Widgets

| Widget | Data source | Action |
|---|---|---|
| Refund menunggu | `GET /rental-guarantee/refunds?status=PENDING` | Link → Refund list |
| Jaminan belum lunas | Guarantee payments / house summaries | Link → Jaminan Sewa |
| **Primary CTA** | — | Large button **Catat Pembayaran Iuran** → `/payments/new` |

---

### 9.2 RW Management — `/rws` *(Administrator)*

> **NOTE:** RW (Rukun Warga) is the parent grouping of RT in the implemented backend. It is not in backend spec v1.4 §5 but exists at `/api/v1/rws`.

**List columns:** Kode RW · Deskripsi · Jumlah RT (computed client-side or from list) · Aksi

**Actions:** Tambah RW · Edit · Hapus (confirm dialog — blocked by backend if RTs linked)

**Form fields:** `rw_code` (required) · `description` (optional)

---

### 9.3 RT Management — `/rts` *(Administrator)*

**List columns:** RW · Kode RT · Deskripsi · Aksi

**Filters:** RW dropdown

**Form fields:** RW (select) · `rt_code` (required) · `description` (optional)

**Delete:** Confirm — only if no houses linked.

---

### 9.4 House List — `/houses`

**Accessible by:** Administrator (full), Supervisor (read-only)

**Columns:** RT · Blok · No. · Pemilik · Status (OWNED/RENTED badge) · Jaminan (UNPAID/PAID/n/a) · Aksi

**Filters:**

- RT (dropdown)
- Status occupancy (Semua / Milik Sendiri / Disewa)
- Search (block + house number, owner name — client-side filter on loaded page or server filter if added)

**Administrator actions:** Tambah Rumah · Impor CSV (shortcut)

**Row click:** → `/houses/:id`

---

### 9.5 House Create / Edit — `/houses/new`, `/houses/:id/edit` *(Administrator)*

Single form with conditional sections based on `status`.

#### 9.5.1 Always visible

| Field | API field | Notes |
|---|---|---|
| RT | `rt_id` | Select |
| Blok | `block_code` | e.g. `E` |
| Nomor rumah | `house_number` | e.g. `20` |
| Nama pemilik | `owner_name` | Dues contact |
| Email pemilik | `email` | |
| Telepon pemilik | `phone` | WhatsApp ok |
| Tanggal aktif | `active_date` | Default `2024-01-01` |

#### 9.5.2 Occupancy — `status`

| Value | Label |
|---|---|
| `OWNED` | Milik sendiri (pemilik tinggal) |
| `RENTED` | Disewa (penyewa tinggal) |

When `RENTED`, show tenant section:

| Field | Required |
|---|---|
| Nama penyewa | ✅ |
| Email penyewa | ✅ |
| Telepon penyewa | ✅ |
| Durasi sewa (bulan) | ✅ positive integer |
| Jaminan sewa (IDR) | Required when `lease_duration_months ≥ min-duration-months` (default 6); pre-fill Rp 300.000 |

When `OWNED`, tenant and guarantee fields hidden/cleared.

#### 9.5.3 Lease termination warning

When editing a rented house and changing status to OWNED, or changing tenant/lease/guarantee fields, show confirmation:

> *Perubahan ini akan mengakhiri sewa saat ini. Jika jaminan sewa sudah dibayar, sistem akan membuat pengembalian jaminan berstatus **Menunggu**.*

Reference: backend spec §2.7.

---

### 9.6 House Detail Hub — `/houses/:id`

**Header (persistent):** `{block_code} No. {house_number}` · RT · Status badge · Pemilik · *(if RENTED)* Penyewa

**Tabs:**

| Tab | Key | Contents |
|---|---|---|
| **Ringkasan** | `ringkasan` | Account summary card, rental guarantee card, quick actions |
| **Iuran & Pembayaran** | `iuran` | Payment history for house, record payment button |
| **Jaminan Sewa** | `jaminan` | Obligation status, receipt, linked refund |

Default tab: `ringkasan`.

Mobile: horizontally scrollable tab bar.

#### 9.6.1 Tab — Ringkasan

**Account summary** — `GET /reports/arrears/{house_id}`:

| Display | Source field |
|---|---|
| Total tunggakan | `total_arrears_idr` |
| Total denda | `total_penalties_idr` |
| Deposit | `deposit_idr` |
| Periode belum lunas | `unpaid_periods[]` — table |
| Rincian denda | `penalties[]` — table |

**Rental guarantee card** — from `GET /houses/{id}` → `rental_guarantee`:

| Status | UI |
|---|---|
| `required: false` | *Tidak ada kewajiban jaminan* |
| `UNPAID` | Amount + badge **Belum lunas** + button *Catat pembayaran jaminan* |
| `PAID` | Receipt number + link to receipt detail |

**Quick actions (role-dependent):**

- Catat pembayaran iuran
- Lihat laporan tunggakan (scroll to summary)
- Edit rumah *(Admin)*

#### 9.6.2 Tab — Iuran & Pembayaran

- Paginated payment list filtered by `house_id`.
- Columns: Tanggal · Nominal · Catatan · Aksi (lihat / edit / hapus per role).
- **Catat Pembayaran** → inline drawer or navigate to `/payments/new?houseId=:id`.

#### 9.6.3 Tab — Jaminan Sewa

- Current obligation: amount, status, obligation id (collapsed/technical).
- Receipt detail if PAID — link + print.
- Refund status if lease ended — link to refund record.
- Historical receipts for prior obligations: list from `GET /rental-guarantee/payments?house_id=:id`.

---

### 9.7 Record Payment — `/payments/new`, `/payments/:id` (edit)

**Accessible by:** Administrator, Supervisor

#### 9.7.1 Form

| Field | API field | Notes |
|---|---|---|
| Rumah | `house_id` | Searchable select; pre-filled from query param |
| Tanggal pembayaran | `payment_date` | Default today |
| Nominal diterima | `gross_amount` | Integer IDR; min hint shown dynamically |
| Catatan | `note` | Optional |

**Minimum payment hint:** When house selected, show *Minimum nominal: Rp X* where X = applicable rate for payment month (backend rule §2.2 — gross alone, deposit does not reduce floor).

#### 9.7.2 Account panel (before submit)

Load `GET /reports/arrears/{house_id}` when house selected:

- Tunggakan, denda, deposit (formatted IDR)
- Unpaid periods list (compact)
- Alert if penalties exist: *Denda akan diselesaikan terlebih dahulu sebelum prepayment*

#### 9.7.3 Advisory prepayment calculator

Collapsible panel **Kalkulator Prepayment** — *perkiraan saja*:

| Input | Behaviour |
|---|---|
| Bulan prepayment ke depan | Integer ≥ 0 |
| Suggested gross | Computed client-side for clean-account scenario |

**Clean-account formula (advisory only):**

At current rate R = 120.000 (or mixed if straddling Feb 2026 — show disclaimer):

- 12-month blocks: `floor(M/12) × R` discount (free months)
- 6-month remainder block: if `M mod 12 ≥ 6`, additional `R/2` discount
- Suggested = `M × R − total_discount`

Show preset chips: **6 bulan**, **12 bulan**, **18 bulan**, **24 bulan** with suggested amounts.

**Apply suggestion** button copies value into `gross_amount` field.

> **DISCLAIMER (always visible):** *Nominal final ditentukan oleh sistem setelah memperhitungkan tunggakan dan denda. Kalkulator assumes akun bersih setelah tunggakan/denda lunas.*

#### 9.7.4 After submit — allocation breakdown

Display response `allocations[]` grouped by type:

| Type | Label | Columns |
|---|---|---|
| `DUES` | Iuran | Periode · Nominal · Diskon |
| `PENALTY` | Denda | Nominal |
| `DEPOSIT` | Deposit | Nominal |

Also show updated `deposit_balance`, `next_period_covered` if returned.

---

### 9.8 Payment List — `/payments`

**Filters:** RT · Rumah · Rentang tanggal · Pagination

**Columns:** Tanggal · Rumah (Blok/No.) · Pemilik · Nominal · Catatan · Aksi

---

### 9.9 Rental Guarantee Receipt — `/rental-guarantee/*`

#### 9.9.1 List

**Filters:** RT · Rumah · Rentang tanggal · Pagination

**Columns:** No. Resi · Rumah · Penyewa · Tanggal · Nominal · Refund status · Aksi

#### 9.9.2 Record receipt — `/rental-guarantee/new`

| Field | Notes |
|---|---|
| Rumah | Must have active obligation UNPAID |
| Tanggal | `payment_date` |
| Nominal | Must equal house `rental_guarantee_amount_idr` exactly — pre-fill readonly |
| Catatan | Optional |

Show tenant snapshot preview (from house record).

#### 9.9.3 Receipt detail — `/rental-guarantee/:id`

- Full receipt fields including `receipt_number`, `paid_by_*` snapshots.
- Nested `refund` if present.
- **Cetak** button → print layout (§11.1).
- Edit (date, note only) · Delete (with confirm; blocked if refund exists).

---

### 9.10 Rental Guarantee Refund — `/rental-guarantee/refunds/*`

#### 9.10.1 List

**Filters:** Status (PENDING / COMPLETED) · RT · Rumah · Pagination

**Columns:** Rumah · Dikembalikan ke · Nominal · Status · Tanggal dibuat · Aksi

Highlight PENDING rows (badge **Menunggu**).

#### 9.10.2 Refund detail — `/rental-guarantee/refunds/:id`

| Status | Actions |
|---|---|
| `PENDING` | **Selesaikan pengembalian** (dialog: refund_date, note) → `POST .../complete` · **Batalkan** (confirm) → `DELETE` |
| `COMPLETED` | Read-only · **Cetak** (§11.2) · show `refund_number` |

Display linked receipt number, tenant snapshot (`refunded_to_*`).

---

### 9.11 Reports

#### 9.11.1 Monthly Dues — `/reports/dues`

**Filters:** Scope — Cluster / RT / Rumah (maps to query params)

**Table columns:** Periode · Diharapkan · Terkumpul · Diskon · Outstanding · Lunas (✓/✗)

**Footer totals:** `total_expected_idr`, `total_collected_idr`, `total_discount_idr`

Show reconciliation note: *Diharapkan = Terkumpul + Diskon + Outstanding*

#### 9.11.2 Arrears Lookup — `/reports/arrears`

House search → display same arrears detail as house summary tab.

Export/print optional v1.1 enhancement — not required v1.

---

### 9.12 User Management — `/users` *(Administrator)*

**List columns:** Username · Peran · Aktif · Aksi

**Create form:** username · password · role (Administrator / Supervisor)

**Edit form:** role · is_active · password (optional — leave blank to keep)

**Delete:** Confirm dialog.

---

### 9.13 CSV Import Hub — `/import` *(Administrator)*

Tabbed interface for three import types:

| Tab | Endpoint | Template download |
|---|---|---|
| Rumah | `POST /houses/import` | Link to sample CSV columns per backend spec §9.1 |
| Pembayaran Iuran | `POST /payments/import` | §9.2 |
| Jaminan Sewa | `POST /rental-guarantee/payments/import` | §9.3 |

**UX flow:**

1. Drag-and-drop or file picker (`.csv`, `.txt`).
2. Upload → show progress spinner.
3. Result panel: `total_rows`, `success_count`, `error_count`.
4. Errors table: row number · message (from API `errors[]`).
5. On partial success: toast *Impor selesai dengan {error_count} error*.

Do **not** abort UI on partial failure — mirror backend row-level error collection.

---

## 10. Domain UX Rules

Rules the UI must reflect (details in backend spec):

| Rule | UI behaviour |
|---|---|
| Minimum payment = 1 month gross (§2.2) | Validate hint + display backend 400 detail on violation |
| Deposit does not reduce minimum | Explain in payment form helper text |
| Prepayment discount after clean account (§2.3) | Calculator disclaimer; allocation shows `discount_applied` per period |
| Rate change Feb 2026 (§6.1) | Unpaid period rows show correct per-month amount |
| Penalties recomputed (§2.4) | Never show penalty as editable; display as read-only computed |
| Houses permanent (§3.2) | No delete button; ownership change via edit |
| OWNED vs RENTED exclusive (§2.5) | Status toggle with conditional fields |
| Guarantee exact amount (§2.6) | Readonly amount on receipt form |
| Lease end → auto PENDING refund (§2.7) | Confirmation on house edit; surface in dashboard |
| Pending refund does not block new tenant (§2.7) | No blocking modal — informational only |

---

## 11. Print Templates

Print via `@media print` dedicated routes or hidden print components triggered by `window.print()`.

### 11.1 Guarantee Receipt (RG)

**Route:** `/rental-guarantee/:id/print` (or modal print view)

**Content:**

- Header: cluster/app name + *Bukti Pembayaran Jaminan Sewa*
- `receipt_number` (prominent)
- House: Blok · No. · RT
- Pemilik property
- Penyewa (`paid_by_*` snapshots)
- Nominal · Tanggal pembayaran
- Catatan (if any)
- Footer: dicetak pada {timestamp}

**CSS:** Hide app chrome (sidebar, nav, buttons) in print media.

### 11.2 Guarantee Refund (RF)

**Route:** `/rental-guarantee/refunds/:id/print`

**Content:**

- *Bukti Pengembalian Jaminan Sewa*
- `refund_number` (only when COMPLETED)
- Linked `receipt_number`
- House · recipient (`refunded_to_*`)
- Nominal · Tanggal pengembalian
- Catatan

---

## 12. API Integration

### 12.1 Base Configuration

| Env variable | Example | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080/api/v1` | Backend root |
| `VITE_SESSION_IDLE_MINUTES` | `30` | Idle logout |

**Development:** Vite dev-server proxy `/api` → backend to avoid CORS during local dev.

**Production:** Backend CORS must allow frontend origin.

### 12.2 Endpoint Map

| Feature | Methods | Path |
|---|---|---|
| Auth | POST | `/auth/login` |
| RW | CRUD | `/rws`, `/rws/{id}` |
| RT | CRUD | `/rts`, `/rts/{id}` |
| Houses | CRUD + import | `/houses`, `/houses/{id}`, `/houses/import` |
| Payments | CRUD + import | `/payments`, `/payments/{id}`, `/payments/import` |
| Guarantee | CRUD + import | `/rental-guarantee/payments`, `…/{id}`, `…/import` |
| Refunds | List, get, complete, cancel | `/rental-guarantee/refunds`, `…/{id}`, `…/{id}/complete` |
| Reports | GET | `/reports/dues/monthly`, `/reports/arrears/{houseId}` |
| Users | CRUD | `/users`, `/users/{id}` |

Full request/response schemas: OpenAPI at `{API_BASE}/../swagger-ui.html` or `/v3/api-docs`.

### 12.3 TanStack Query Key Conventions

```
['rws']
['rts', { rwId? }]
['houses', { rtId?, page, size }]
['houses', houseId]
['houses', houseId, 'arrears']
['payments', filters]
['payments', paymentId]
['rental-guarantee', 'payments', filters]
['rental-guarantee', 'payments', id]
['rental-guarantee', 'refunds', filters]
['reports', 'dues-monthly', filters]
['users']
```

Invalidate cross-cutting keys after payment or house mutations.

---

## 13. Error Handling

### 13.1 API Errors (RFC 7807)

Backend returns Problem Detail JSON. Map to user-facing Indonesian messages:

| HTTP | UI treatment |
|---|---|
| 400 | Toast + inline field errors if validation detail present |
| 401 | Redirect login |
| 403 | Toast *Anda tidak memiliki akses* |
| 404 | Empty state *Data tidak ditemukan* |
| 409 / 422 | Toast with `detail` text |
| 500 | Toast *Terjadi kesalahan server. Coba lagi.* |

### 13.2 Loading & Empty States

Every list/detail must implement:

- **Skeleton** loading
- **Empty** state with contextual message and primary action (e.g. *Belum ada pembayaran — Catat pembayaran*)
- **Error** state with retry button

### 13.3 Destructive Actions

Confirm dialog before:

- Delete payment, receipt, refund (cancel)
- Delete RT, RW, user
- House lease termination edits

---

## 14. Non-Functional Requirements

| Area | Requirement |
|---|---|
| **Performance** | First contentful paint < 2s on 4G; route-level code splitting |
| **Accessibility** | WCAG 2.1 AA target — focus rings, labels, keyboard nav, colour contrast |
| **Security** | No JWT in URL; XSS-safe rendering; CSP headers from hosting provider |
| **Browser support** | Last 2 versions Chrome, Firefox, Safari, Edge; mobile Safari iOS 15+ |
| **Responsive** | Min viewport 360px; touch targets ≥ 44px on mobile CTAs |
| **Consistency** | All money as integer IDR end-to-end; no floating point |
| **Audit display** | Show `created_at` / `created_by` on detail screens where API provides |

---

## 15. Deployment & Configuration

### 15.1 Repository

Separate Git repository from backend. CI pipeline:

1. `npm ci`
2. `npm run lint`
3. `npm run test`
4. `npm run build`
5. Deploy `dist/` to static host

### 15.2 Hosting Options

| Option | Description |
|---|---|
| **Static host** | Cloudflare Pages, Netlify, S3+CloudFront, etc. Frontend only; backend hosted elsewhere. SPA fallback: all routes → `index.html`. |
| **Single VPS** | Frontend + backend + PostgreSQL on one server (see §15.5). Recommended for small clusters with low operational overhead. |

### 15.3 Environment Matrix

| Environment | `VITE_API_BASE_URL` |
|---|---|
| Local dev | `http://localhost:8080/api/v1` (via Vite proxy) |
| Staging | Staging backend URL |
| Production (split host) | Production backend URL |
| Production (single VPS) | `https://rdms.example.com/api/v1` (same origin — see §15.5) |

### 15.4 `.env.example`

```bash
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_SESSION_IDLE_MINUTES=30
VITE_APP_NAME=Siwarga RDMS
```

### 15.5 Single VPS Deployment

Both frontend and backend **may** run on a single VPS. This is the recommended deployment model for a residential cluster — staff-only access, low concurrency (typically 1–10 simultaneous users), and modest data volume.

Separate Git repositories (§15.1) still apply; only the **runtime** is co-located.

#### 15.5.1 Architecture

```
                    Internet
                        │
                        ▼
              ┌─────────────────┐
              │  Nginx / Caddy  │  :443 (HTTPS)
              │  reverse proxy  │
              └────────┬────────┘
                       │
         ┌─────────────┴─────────────┐
         │                           │
         ▼                           ▼
  /  → static files            /api/ → proxy
  /var/www/rdms/dist/               │
  (React SPA build)                 ▼
                           ┌─────────────────┐
                           │  Spring Boot    │  :8080 (localhost only)
                           │  rdms.jar       │
                           └────────┬────────┘
                                    │
                                    ▼
                           ┌─────────────────┐
                           │  PostgreSQL 15+ │  :5432 (localhost only)
                           └─────────────────┘
```

| Component | Runtime | Notes |
|---|---|---|
| **Frontend** | Static files only | No Node.js process at runtime; build locally or in CI |
| **Backend** | JVM 21 + Spring Boot JAR | Binds `127.0.0.1:8080` — not exposed to the internet |
| **Database** | PostgreSQL 15+ | Binds `127.0.0.1:5432` — not exposed to the internet |
| **Reverse proxy** | Nginx or Caddy | Terminates TLS; sole public entry point |

#### 15.5.2 VPS Hardware Requirements

RDMS is lightweight. Scale estimates assume ≤ 500 houses, ≤ 50 concurrent staff (typical cluster: 2–5 concurrent).

| Tier | vCPU | RAM | Storage | Use case |
|---|---|---|---|---|
| **Minimum** | 1 | 2 GB | 20 GB SSD | Dev/staging or very small cluster; tight on memory during imports/updates |
| **Recommended** | 2 | 4 GB | 40 GB SSD | **Production default** — comfortable headroom for JVM, PostgreSQL, and backups |
| **Over-provisioned** | 4 | 8 GB | 80 GB SSD | Only if running multiple environments or heavy CSV import batches on the same host |

**Approximate RAM budget (recommended tier):**

| Process | RAM |
|---|---|
| OS + buffers | ~400 MB |
| PostgreSQL | ~512 MB – 1 GB |
| Spring Boot (JVM heap) | ~512 MB – 1 GB (`-Xmx768m` typical) |
| Nginx | ~30 MB |
| **Headroom** | ~1 GB for spikes, `pg_dump`, OS updates |

> **RULE:** If the VPS swaps regularly, increase RAM before adding CPU.

#### 15.5.3 Frontend Build (same-origin)

Build the SPA with the production API URL matching the public domain:

```bash
VITE_API_BASE_URL=https://rdms.example.com/api/v1 \
VITE_SESSION_IDLE_MINUTES=30 \
VITE_APP_NAME="Siwarga RDMS" \
npm run build
```

Deploy output to the VPS:

```bash
rsync -avz dist/ user@rdms.example.com:/var/www/rdms/dist/
```

Same-origin deployment avoids CORS configuration on the backend.

#### 15.5.4 Nginx Configuration

Example site config at `/etc/nginx/sites-available/rdms`:

```nginx
server {
    listen 80;
    server_name rdms.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name rdms.example.com;

    ssl_certificate     /etc/letsencrypt/live/rdms.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/rdms.example.com/privkey.pem;

    root /var/www/rdms/dist;
    index index.html;

    # SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }

    # API proxy
    location /api/ {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        client_max_body_size 5m;   # matches backend multipart limit (§application.properties)
    }

    # Optional: block Swagger in production
    location /swagger-ui.html { return 404; }
    location /v3/api-docs     { return 404; }
}
```

Enable and reload:

```bash
sudo ln -s /etc/nginx/sites-available/rdms /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

**Caddy alternative:** Caddy auto-provisions Let's Encrypt certificates. Equivalent routing: `handle /api/*` → reverse proxy `localhost:8080`; `handle` → `file_server` with `try_files {path} /index.html`.

#### 15.5.5 Backend & Database (Docker Compose)

Extend the backend repo's `docker-compose.yml` on the VPS. PostgreSQL and the app run in Docker; Nginx runs on the host.

```yaml
# /opt/rdms/docker-compose.yml (on VPS)
services:
  db:
    image: postgres:16-alpine
    container_name: siwarga-db
    restart: unless-stopped
    environment:
      POSTGRES_DB: siwarga
      POSTGRES_USER: siwarga
      POSTGRES_PASSWORD: ${DB_PASS}
    volumes:
      - siwarga-data:/var/lib/postgresql/data
    ports:
      - "127.0.0.1:5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U siwarga -d siwarga"]
      interval: 10s
      timeout: 5s
      retries: 5

  app:
    image: siwarga-rdms:latest          # built from backend Dockerfile
    container_name: siwarga-app
    restart: unless-stopped
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/siwarga
      DB_USER: siwarga
      DB_PASS: ${DB_PASS}
      JWT_SECRET: ${JWT_SECRET}
      JWT_EXPIRY_MINUTES: ${JWT_EXPIRY_MINUTES:-120}
      ADMIN_USERNAME: ${ADMIN_USERNAME}
      ADMIN_PASSWORD: ${ADMIN_PASSWORD}
      JAVA_TOOL_OPTIONS: "-Xmx768m -Xms256m"
    depends_on:
      db:
        condition: service_healthy

volumes:
  siwarga-data:
```

Environment file `/opt/rdms/.env` (never commit):

```bash
DB_PASS=<strong-random-password>
JWT_SECRET=<min-256-bit-random-secret>
JWT_EXPIRY_MINUTES=120
ADMIN_USERNAME=admin
ADMIN_PASSWORD=<strong-initial-password>
```

Start:

```bash
cd /opt/rdms && docker compose up -d
```

#### 15.5.6 Backend systemd Alternative (no Docker for app)

If Docker is used for PostgreSQL only, run the JAR via systemd:

```ini
# /etc/systemd/system/rdms.service
[Unit]
Description=Siwarga RDMS Backend
After=network.target docker.service
Requires=docker.service

[Service]
User=rdms
EnvironmentFile=/opt/rdms/.env
ExecStart=/usr/bin/java -Xmx768m -jar /opt/rdms/rdms.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

#### 15.5.7 Firewall

Allow only public web ports. Block direct access to backend and database:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

Confirm PostgreSQL and Spring Boot bind to `127.0.0.1` only (see §15.5.5).

#### 15.5.8 Security Checklist

| Item | Requirement |
|---|---|
| HTTPS | Let's Encrypt via Certbot or Caddy; no plain-HTTP except redirect |
| Secrets | `JWT_SECRET`, `DB_PASS`, `ADMIN_PASSWORD` via env — never in Git |
| Swagger | Disable or block `/swagger-ui.html` and `/v3/api-docs` in production |
| Admin seed | Change default admin password immediately after first login |
| SSH | Key-based auth; disable password login |
| Updates | Unattended security updates for OS; periodic `docker compose pull` |

#### 15.5.9 Backups

Daily PostgreSQL dump (cron example):

```bash
# /etc/cron.d/rdms-backup
0 2 * * * rdms pg_dump -h 127.0.0.1 -U siwarga siwarga | gzip > /var/backups/rdms/rdms-$(date +\%Y\%m\%d).sql.gz
```

Retention: keep 7 daily + 4 weekly copies. Store off-server (S3, another VPS, or local NAS).

#### 15.5.10 Deployment Procedure

| Step | Action |
|---|---|
| 1 | Provision VPS (2 vCPU / 4 GB recommended); point DNS `rdms.example.com` → VPS IP |
| 2 | Install Docker, Docker Compose, Nginx; configure firewall (§15.5.7) |
| 3 | Deploy backend: build/push JAR image, `docker compose up -d`, verify container healthy (`docker compose ps`) |
| 4 | Build frontend with production `VITE_API_BASE_URL`; rsync `dist/` to `/var/www/rdms/dist/` |
| 5 | Configure Nginx (§15.5.4); obtain TLS certificate |
| 6 | Smoke test: login, list houses, record test payment, delete test payment |
| 7 | Enable backups (§15.5.9); change default admin password |

**Rolling frontend update:** rebuild SPA → rsync `dist/` → no backend restart required.

**Rolling backend update:** build new image → `docker compose up -d app` → Flyway migrations run automatically on startup.

#### 15.5.11 Cost Estimate

| Provider tier | Spec | Approx. monthly |
|---|---|---|
| Budget VPS (Hetzner, DigitalOcean, Vultr) | 2 vCPU / 4 GB | $6 – 12 USD |
| Minimum VPS | 1 vCPU / 2 GB | $4 – 6 USD |

Domain registration extra (~$10–15 USD/year).

---

## 16. Implementation Priorities

Full parity is required; build order minimises blocked workflows:

| Priority | Modules | Rationale |
|---|---|---|
| **P0** | Auth, App shell, House list/detail (summary tab), Payment record + list, Arrears display | Core daily operations |
| **P1** | Rental guarantee receipts, Refunds, Monthly dues report, Dashboard widgets | Complete financial ops |
| **P2** | RW/RT CRUD, User management, CSV import, Print templates, House create/edit | Admin setup & bulk tools |

---

## 17. Future Extensions (Out of Scope v1)

Document for planning only — **do not implement in v1**:

| Extension | Notes |
|---|---|
| **Resident portal** | Owner/tenant login, view balance, download receipts |
| **English i18n** | `react-i18next` or similar |
| **PDF generation** | Server-side or client PDF library |
| **Payment gateway** | Online transfer confirmation |
| **Notifications** | WhatsApp/email reminders for arrears |
| **Advanced analytics** | Charts beyond monthly dues table |
| **Export** | Excel/CSV export from reports |

---

## 18. Glossary

| Term | Indonesian UI label | Meaning |
|---|---|---|
| RDMS | — | Resident Dues Management System |
| RW | RW | Rukun Warga — parent neighbourhood grouping |
| RT | RT | Rukun Tetangga |
| Dues | Iuran | Monthly resident dues |
| Arrears | Tunggakan | Unpaid past periods |
| Penalty | Denda | Surcharge for consecutive non-payment |
| Deposit | Deposit | Partial prepayment credit |
| Rental Guarantee | Jaminan sewa | One-time tenant guarantee fee |
| OWNED | Milik sendiri | Owner resides in house |
| RENTED | Disewa | Tenant resides; owner elsewhere |
| FIFO | — | Oldest debt settled first (display in tooltips only) |

---

*— End of Specification —*
