# Plan — Leads: Lists, Search & Filtering (port from `sequence`)

> Status: **Phase 1 DONE** (2026-06-24, full build + test green). Phases 2–5 not started. Locked product decisions live here. Build bottom-up, TDD.
> Layer order per AGENTS.md: domain → storage/providers → services → ui → app.
> Goal: bring `sequence`-grade lead management (lists, search, filtering, CSV import, custom
> fields) into coldCalling's **Leads** screen, adapted from email outreach to cold calling.

---

## 0. Reference — how `sequence` does it (the model we're porting)

Studied at `/Users/soni/work/elitale/coldbirds/sequence`. Relevant pieces:

**Data model** (`prisma/schema.prisma`)
- `Contact` — `email` (natural key, `@@unique([userId, email])`), `firstName`, `lastName`,
  `company`, `title`, `customFields Json?`, `status ContactStatus` (deliverability enum),
  email-validation/provider fields, `deletedAt` soft delete. Indexed on
  `(userId, status, deletedAt)`.
- `ContactList` — `id`, `userId`, `name`, soft delete. **Static** lists.
- `ContactListMembership` — join table `(listId, contactId)`, `@@unique([listId, contactId])`.
- `ContactImportJob` — CSV/XLSX import bookkeeping.

**Search / filter engine** (`src/repositories/contact.repository.ts`)
- **Search**: case-insensitive `contains` OR across `email | firstName | lastName | company`.
- **Per-column filters** (`ContactColumnFilters`): `email, firstName, lastName, company, title`
  (substring), `status` (enum **prefix** match → `IN (...)`), `emailProvider` (substring),
  and **custom** = `key → substring` matched against `customFields->>key` (JSON path).
- **Pagination**: two modes —
  - offset `listContacts` (`page`/`pageSize`, `count` for total);
  - **compound keyset cursor** `listContactsByCursor` — cursor is `${createdAtMs}_${id}`;
    `WHERE (createdAt, id) < (cursor)` ordered `createdAt DESC, id DESC`. The `id`
    tiebreaker is **required** because bulk imports create thousands of rows sharing one
    `createdAt`; cursoring on `createdAt` alone skips the whole batch.
- **Dynamic columns**: `getCustomFieldKeysForList` → `SELECT DISTINCT jsonb_object_keys(...)`
  union of custom-field keys across a list, used to render dynamic table columns.
- **Bulk ops**: `upsertContactsBulk` (raw `INSERT … ON CONFLICT (userId,email) DO UPDATE …
  COALESCE`, chunked), `bulkSoftDeleteContacts`, `attachContactsToList` (`createMany …
  skipDuplicates`), `removeContactFromList`.

**UI** (`src/components/contacts/*`, routes `/contacts`, `/contacts/lists/[id]`, `/contacts/import`)
- Contacts table with bulk checkbox select + bulk delete, list **tabs**, **create-list**
  dialog, **import wizard** (CSV/XLSX, column mapping), add-column dialog, editable cells,
  status/provider cells, **persisted column prefs** (`use-persisted-prefs.ts`).

**"Dynamic lists"** (`src/services/dynamic-list-sync.service.ts`) — NOT filter-defined smart
lists. It is **campaign rolling enrollment**: auto-enroll VALID members of auto-sync lists
into active campaigns. Calling analog (auto-pull new leads into a power-dialer session) is
**out of scope** here (see §13).

---

## 1. Goal

Make the Leads screen a real lead workbench:

1. **First-class lead lists** — create / rename / delete; browse leads *within* a list;
   add/remove leads; live per-list counts. (Today lists exist only as power-dialer input.)
2. **Search + faceted filtering** — substring search, per-column filters, lead-status,
   tags, DNC, and list membership.
3. **Keyset pagination** — handle 10k+ imported rows without freezing the FX TableView.
4. **Calling-grade CSV import** — drag a raw Apollo/ZoomInfo/Sales-Nav export → auto-mapped
   columns → libphonenumber normalization with a per-import default country → pick the
   dialer's **primary** number while keeping the rest → DNC/duplicate/invalid flagged with
   trustworthy counts → dialing in <60s. **Phone is required; email is optional metadata.**
   (Validated by the `buyer` + `va` agents — see §2.1 and §7.)
5. **Inline keyboard-first grid** — add a row and add a custom-field column directly in the
   table (no modal): Tab/Enter to fly, paste rows from the clipboard, live in-cell phone
   validation. The desktop differentiator vs clunky enterprise loaders. (See §8.)
6. **Mid-call quick-add** — a global hotkey drops "call my colleague" leads into the list
   being dialed without leaving the call or losing dialer position. (See §9.)
7. **Custom fields + dynamic columns** — arbitrary per-lead key/values (Timezone,
   Best-time-to-call, Lead source…) surfaced as optional, reorderable, persisted columns.

---

## 2. Current coldCalling state — audit & gaps

| Capability | `sequence` | coldCalling today | Gap |
|---|---|---|---|
| Lead entity | email-keyed, customFields, status | phone-keyed `Lead` (name/phone/company/title/email/tags/notes/dnc) — [Lead.java](src/domain/src/main/java/com/elitale/coldbirds/coldcalling/domain/model/Lead.java) | + customFields, + leadStatus |
| Lists | `ContactList` + membership, first-class | `call_lists` + `call_list_leads` (ordered, dial status) — power-dialer only | + service + UI; reuse tables |
| Search | OR contains (4 cols) | `search(query)` name/company/phone — [LeadRepository.java](src/storage/src/main/java/com/elitale/coldbirds/coldcalling/storage/repository/LeadRepository.java) | + email, + parameterised |
| Per-column filter | yes (+ custom JSON) | none | build `LeadFilter` |
| Status filter | `ContactStatus` | none | add `LeadStatus` |
| Tag filter | n/a (uses status) | tags stored, not filterable | filter via `json_each` |
| Pagination | offset + keyset cursor | none (`findAll`) | keyset paging |
| Dynamic columns | `jsonb_object_keys` | none | SQLite `json_each` |
| CSV import | wizard + bulk upsert | **none** | new import service + wizard |
| Bulk select/delete | yes | single-row delete — [LeadsController.java](src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/LeadsController.java) | bulk select |
| Multi-tenancy | `userId` everywhere | single-user local SQLite | **drop `userId`** (see §13) |
| Phone hygiene | n/a (email tool) | strict E.164 only, no normalizer | libphonenumber (§7.2) |
| Inline add row/column | yes | none | keyboard-first grid (§8) |

Current UI: one reactive search box + 4-column table (name/phone/company/dnc), add-lead
dialog, single delete, double-click to dial. No lists, filters, paging, or import.

### 2.1 Buyer + operator validation (why import is *calling-grade*, not email-grade)

Stress-tested with the `buyer` agent (6 cold-calling buyer personas) and the `va` agent
(5 daily operators). Unanimous verdict: **porting the email importer 1:1 fails.** Email lists
have one email per row; calling lists have **3–5 phone columns** (mobile/direct/corporate/
home/other), extensions, and garbage formatting from Apollo/ZoomInfo/Sales Nav. The hard
requirements they will not pilot/buy without:

- **Multiple phone columns** → map all, pick one **primary** (dialer default), keep the rest.
  Discarding numbers = "not built for calling" (#1 make-or-break).
- **libphonenumber normalization + a per-import default country** (operators never type E.164).
- **Non-destructive dedupe against the existing DB** — re-import must update, never duplicate,
  and **never wipe** human-entered notes/dispositions on a blank file cell.
- **Never dedupe on a corporate/HQ phone** — it collapses every lead sharing a switchboard.
- **DNC scrub at import** — a TCPA compliance control, not a feature (enterprise dealbreaker).
- **Skip bad rows, never fail the file**; reconciling summary (file = created+updated+skipped+
  errors) + downloadable error CSV.
- **Inline keyboard-first add-row/add-column** + a **mid-call quick-add** that never loses the
  dialer's place — the retention / word-of-mouth levers for daily operators.

These are folded into §3 locks and detailed in §7–§10.

---

## 3. Locked Product Decisions

- **Reuse `call_lists` / `call_list_leads`. Do NOT rename `call_lists` to `lead_lists`.** A
  lead list *is* the thing the power dialer consumes — adding a lead to a list makes it
  dialable. One concept, zero migration churn, no power-dialer breakage. (The lead-rename
  migration already repointed the join table to `call_list_leads` with a `lead_id` FK;
  `call_lists` itself stays. "Call list" = "lead list" in UI copy.)
- **Phone (E.164) is the natural unique key.** Add a **partial unique index** on
  `phone WHERE deleted_at IS NULL` (one *live* lead per number). CSV import upserts on
  phone. Existing duplicate live rows are deduped (soft-delete older) in the migration first.
- **Add `custom_fields TEXT` (JSON object)** to `leads` (additive). Drives CSV import of
  unmapped columns + dynamic columns. Mirrors `sequence.customFields`.
- **Add `lead_status TEXT NOT NULL DEFAULT 'new'`** — calling analog of `ContactStatus`:
  `NEW | CONTACTED | INTERESTED | CALLBACK | NOT_INTERESTED | BAD_NUMBER | DNC`. Lifecycle
  facet for filtering. **`dnc` boolean stays** and remains the *authoritative* pre-dial
  block (service-enforced); `lead_status=DNC` is just a visible label.
- **Server-side (repository) filtering + keyset pagination** by `(created_at DESC, id DESC)`
  with compound cursor `"{createdAtMs}_{id}"` — identical strategy to `sequence`. Handles
  large imports; keeps the FX thread free (load pages off-thread, `Platform.runLater`).
- **Search** = case-insensitive substring OR across `first_name, last_name, company, phone,
  email` (calling adds phone + email vs sequence).
- **Filters AND together** in a single `LeadFilter` value object; repository builds a
  **parameterised** query (bind vars only — no string concatenation → SQLi-safe).
- **CSV first (v1). XLSX deferred** to a later phase. Parser: `org.apache.commons:commons-csv`
  in the `services` module (local file orchestration is a service concern).
- **Dynamic columns** = union of `custom_fields` keys via SQLite `json_each`; visible-column
  selection persisted in the `settings` table (key `leads.visibleColumns`).
- **No `userId` columns.** coldCalling is a single-user local app; sequence's multi-tenant
  dimension is dropped everywhere.

— *Import & data-entry locks (from the `buyer` + `va` evaluations):* —

- **Calling-grade importer, not email-grade.** The importer's job: multi-phone, normalize,
  dedupe-safely against the DB, scrub DNC, skip bad rows, reconcile honestly. (§7)
- **libphonenumber for ALL phone parsing/normalization.** Add
  `com.googlecode.libphonenumber:libphonenumber` to `services`. No hand-rolled regex (it
  silently corrupts extensions/intl). `PhoneNormalizer` lives in `services`; `domain` stays
  dependency-free and its `PhoneNumber` value keeps the strict E.164 invariant (produced
  *from* the normalizer's output).
- **Per-import default country.** One dropdown; bare local numbers normalized against it and
  **flagged as assumed**. With no default set, an ambiguous number goes to the review tray —
  **never guess a country from nothing.**
- **Primary phone = the dedupe key + dialer default; secondary phones preserved as custom
  fields (v1).** Map multiple phone columns, designate one primary; keep the rest as custom
  fields so nothing is discarded. **Never dedupe on a corporate/HQ phone.** A proper
  multi-number table (dial-any, dedupe-on-any) is deferred (§13).
- **Non-destructive upsert against the DB.** `COALESCE` fills empties / updates changed;
  **blank-in-file never wipes** existing notes/disposition/call history. Default
  update-non-destructive; `skip` / `merge` toggles offered.
- **DNC scrub at import (compliance).** Flag matches against existing DNC + any mapped `DNC`
  column; exclude from dialable; count as "skipped: on DNC" in the summary.
- **Skip-bad-rows, never fail-the-file.** Bad rows → a "Needs review" tray with reasons +
  inline fix; the summary reconciles `rows = created + updated + skipped + errors`; error CSV
  is downloadable.
- **Saved mapping templates** (`import_mappings`) auto-applied on header-signature match →
  one-click re-import of the daily refreshed list.
- **Undo last import** via `import_batch_id` (removes leads *created* by the batch +
  detaches their list links). Locked limitation: **updates to pre-existing leads are not
  reverted** in v1 (no before-images) — the summary says so.
- **Inline keyboard-first grid is the default fast path** (add-row pinned at top; Tab/Enter/
  Esc; clipboard row paste; live in-cell phone validation; inline add-column with presets).
  A modal is a secondary path only.
- **Mid-call global quick-add** (`Cmd/Ctrl+Shift+A`) never leaves the call or resets dialer
  position; pre-checks "Add to [current list]".
- **Column layout persisted per user** (order / width / visibility / pinned; phone + name
  frozen on the left).

---

## 4. Architecture & New Components (bottom-up)

### 4.1 Domain (`domain/`)
- `LeadStatus` **enum** — the 7 values above. (Closed set → enum, switched exhaustively.
  Carve-out vs a sealed interface: no per-state data, so enum is the KISS choice.)
- `LeadFilter` **record** — `Optional<String> search`, per-column substrings
  (`firstName, lastName, company, title, phone, email`), `Set<LeadStatus> statuses`,
  `Set<String> tags`, `DncFilter dnc` (`ANY|ONLY|EXCLUDE`), `Optional<CallListId> listId`,
  `Map<String,String> customField` (key→substring), `int limit`, `Optional<Cursor> cursor`.
  Compact ctor copies collections unmodifiable, clamps `limit` (1..200).
- `Cursor` **record** — `(long createdAtMillis, long id)` with `format()` →
  `"{ms}_{id}"` and `static Optional<Cursor> parse(String)` (rejects malformed). Round-trips.
- `Page<T>` **record** — `(List<T> rows, Optional<Cursor> nextCursor, int total)`.
- Extend `Lead` — add `Map<String,String> customFields` and `LeadStatus leadStatus`
  (additive: update compact ctor, all call sites, and tests/builders).
- Secondary phone numbers + extension are stored inside `customFields` in v1 (keys e.g.
  `Mobile`, `Direct`, `Home`, `Other`, `ext`) — preserved + visible, never discarded.
- **Phone normalization is NOT in `domain`** (libphonenumber dependency) — see §4.3. `domain`
  keeps only the strict-E.164 `PhoneNumber` value object.

### 4.2 Storage (`storage/`)
- **Migration ordering:** the lead-rename migration currently ships as a *second* `V2__`
  file (`V2__rename_contacts_to_leads.sql`) alongside `V2__add_calls_status.sql` — Flyway
  rejects duplicate versions and won't start. Renumber the rename to
  **`V3__rename_contacts_to_leads.sql`** first; the two new migrations below are therefore
  **V4** and **V5**.
- **Migration `V4__leads_custom_fields.sql`** (additive — never edit V1–V3):
  add `custom_fields TEXT`, `lead_status TEXT NOT NULL DEFAULT 'new'`; dedupe live phones;
  `CREATE UNIQUE INDEX idx_leads_phone_live ON leads(phone) WHERE deleted_at IS NULL`;
  `CREATE INDEX idx_leads_lead_status ON leads(lead_status)`;
  `CREATE INDEX idx_leads_keyset ON leads(created_at DESC, id DESC)`.
- **`LeadRepository`** — add:
  - `Page<Lead> findPage(LeadFilter filter)` — keyset + filters + total.
  - `List<String> customFieldKeys(Optional<CallListId> listId)`.
  - `UpsertResult bulkUpsert(List<NewLead> rows)` (`created` / `updated`).
  - `int bulkSoftDelete(List<LeadId> ids)`.
  - `List<String> distinctTags()` (facet source).
  - `NewLead` gains `customFields` + `leadStatus`.
  Implement in `SqliteLeadRepository` with parameterised SQL (§5).
- **`CallListRepository`** — add `int countLeads(CallListId)`,
  `int addLeads(CallListId, List<LeadId>)` (`INSERT OR IGNORE`, append at tail
  positions), `boolean removeLead(CallListId, LeadId)`, `Result<CallList> rename(...)`.
- **Migration `V5__import_infrastructure.sql`** (additive; Phase 3): add
  `leads.import_batch_id TEXT`; create `import_batches` (id, file_name, default_country,
  created/updated/skipped/error counts, created_at) and `import_mappings` (id, name,
  header_signature, mapping_json, default_country, target_list_id, timestamps). Index
  `leads(import_batch_id)`.
- **`ImportBatchRepository`** (NEW) — `create(...)`, `undo(batchId)` (soft-delete leads
  created by the batch + detach list links), `recentBatches()`.
- **`ImportMappingRepository`** (NEW) — CRUD + `findByHeaderSignature(sig)`.
- DNC check: `findDncPhones(Set<String> phones)` for the import scrub.

### 4.3 Services (`services/`)
- **`PhoneNormalizer`** (NEW, libphonenumber) — `Outcome normalize(String raw,
  Optional<String> defaultRegion)` returning a sealed `Outcome`:
  `Normalized(PhoneNumber e164, Optional<String> ext, boolean assumedCountry)` |
  `NeedsReview(String reason)` | `Empty`. Three tiers (silent-safe / flagged-assumption /
  never-silent → review). Unit-tested against a messy-input fixture table (§11). **Reused by
  both** the import pipeline and the inline grid's live cell validation.
- **`LeadImportService`** (NEW, SRP) — two phases:
  - **`preview(file, mapping, defaultCountry)`** → parse (commons-csv, header detect),
    auto-detect/apply `ColumnMapping`, normalize phones, classify each row
    (`VALID | NEEDS_REVIEW | DUPLICATE | DNC`), return `ImportPreview` (reconciling counts +
    sample rows + per-row issues) — **no writes**.
  - **`commit(preview, targetList, dedupe)`** → chunked **non-destructive** `bulkUpsert`
    stamped with an `importBatchId`, secondary numbers → `customFields`, optional
    `attachToList`; returns `ImportResult` (created / updated / skipped-dupe / skipped-invalid
    / skipped-DNC / errors) + the error rows for CSV export. Off the FX thread, progress +
    cancel.
- **`ImportMappingService`** (NEW) — save / load / auto-match named mapping templates
  (header-signature match) so a known source re-imports in one click.
- **`LeadService`** — add `findPage(LeadFilter)`, `customFieldKeys(...)`, `addToList`,
  `removeFromList`, `bulkDelete`, `distinctTags`, plus grid ops: `addInline(NewLead)`
  (returns the normalized result), `setCustomField(id, key, value)`,
  `addCustomColumn(key, type)`, `bulkSetStatus/Dnc/List(...)`. DNC dial-enforcement unchanged.
- **`CallListService`** (NEW) — `list()` w/ counts, `create`, `rename`, `delete`,
  `addLeads`, `removeLead`, plus `createFromName(String)` for the wizard's inline
  "type a new list name" field. (The power dialer keeps using the repo directly.)

### 4.4 UI (`ui/`) — detailed UX in §7–§10
- Rework `leads-view.fxml` + `LeadsController` (keep ≤250 lines → **extract** the grid,
  wizard, popover and prefs into their own classes):
  - **Lists rail** (left): "All Leads" + each list with live count; create / rename /
    delete; drop-target for "add selected to list".
  - **Toolbar**: debounced search, **Filters** popover (status multiselect, tags, DNC
    tri-state, custom-field contains), active-filter **chips**, Add Lead, **Import CSV**,
    Add-to-List, Delete (bulk).
  - **Editable virtualized grid** (`LeadsGrid`): bulk-select column, dynamic columns
    (name, phone, company, title, **status**, tags + chosen custom-field columns); **inline
    add-row** pinned at top; **inline add-column** "+"; live in-cell phone validation;
    clipboard row paste; column reorder/resize/pin/persist; context menu (Call, Add/Remove
    list, Toggle DNC, Delete). Detail in §8.
  - **Infinite scroll**: fetch next keyset page on scroll-to-bottom; append via
    `CompletableFuture` → `Platform.runLater`. Never jank at 50k rows.
- **`ImportWizardController`** — 4 steps (Drop → Map → Review → Summary), default-country
  picker, primary-phone picker, saved-mapping auto-apply, **review tray** with inline fix,
  reconciling summary + error-CSV + **Undo**. Detail in §7.
- **`QuickAddPopover`** — mid-call global quick-add over the active call screen. Detail in §9.
- **`LeadFiltersPopover`**, **`LeadColumnPrefs`** (persist via `SettingsService`).
- **Keyboard shortcuts** wired per §10.

### 4.5 App wiring (`app/`)
- Construct `PhoneNormalizer`, `CallListService`, `LeadImportService`,
  `ImportMappingService`, `ImportBatchRepository`, `ImportMappingRepository` in
  `ColdCallingApp`; inject into `LeadsController` alongside `LeadService`.
- Register the **global `Cmd/Ctrl+Shift+A` accelerator** on the primary `Scene` so mid-call
  quick-add works from any screen, including the active call (§9).

---

## 5. SQL detail (SQLite + JSON1; all values bound, never concatenated)

**Keyset page** (filters AND-ed; cursor optional):
```sql
SELECT * FROM leads c
WHERE c.deleted_at IS NULL
  -- search (OR), each column filter (AND), status IN (...), dnc, list EXISTS,
  -- tag EXISTS (json_each), custom-field (json_extract) … all parameterised …
  AND ( ? IS NULL                                   -- no cursor
        OR c.created_at < ?                          -- cursorCreatedAt
        OR (c.created_at = ? AND c.id < ?) )         -- tiebreak on id
ORDER BY c.created_at DESC, c.id DESC
LIMIT ?;
-- total: same WHERE minus the cursor clause, SELECT COUNT(*)
```
- search: `(LOWER(first_name) LIKE '%'||LOWER(?)||'%' OR … phone … OR email …)`
- tag: `EXISTS (SELECT 1 FROM json_each(c.tags) t WHERE t.value = ?)`
- custom: `LOWER(json_extract(c.custom_fields, '$.'||?)) LIKE '%'||LOWER(?)||'%'`
- list: `EXISTS (SELECT 1 FROM call_list_leads m WHERE m.lead_id=c.id AND m.list_id=?)`

**Upsert (dedupe on phone)** — target the partial unique index:
```sql
INSERT INTO leads
  (first_name,last_name,phone,company,title,email,tags,notes,custom_fields,lead_status,dnc,created_at,updated_at)
VALUES (?,?,?,?,?,?,?,?,?,?,0,?,?)
ON CONFLICT(phone) WHERE deleted_at IS NULL DO UPDATE SET
  first_name   = COALESCE(excluded.first_name,   leads.first_name),
  last_name    = COALESCE(excluded.last_name,    leads.last_name),
  company      = COALESCE(excluded.company,      leads.company),
  title        = COALESCE(excluded.title,        leads.title),
  email        = COALESCE(excluded.email,        leads.email),
  custom_fields= COALESCE(excluded.custom_fields,leads.custom_fields),
  deleted_at   = NULL,                 -- re-import revives a soft-deleted lead
  updated_at   = excluded.updated_at;
  -- NOTE: notes / disposition / call history are NEVER in the SET — import must not touch
  -- human-entered data. COALESCE(excluded.x, leads.x) keeps the existing value when the
  -- file cell is blank (importer binds NULL for blanks, never empty-string). Conflict
  -- target is the chosen PRIMARY phone only — never a corporate/HQ column.
```

**Custom-field keys** (dynamic columns; guard NULL/non-object):
```sql
SELECT DISTINCT je.key
FROM leads c, json_each(c.custom_fields) je
WHERE c.deleted_at IS NULL AND c.custom_fields IS NOT NULL
  -- optional: AND EXISTS (… call_list_leads m … m.list_id = ?)
ORDER BY je.key;
```

**Migration dedupe before the unique index**:
```sql
UPDATE leads SET deleted_at = CAST(strftime('%s','now') AS INTEGER)*1000
WHERE deleted_at IS NULL
  AND id NOT IN (SELECT MAX(id) FROM leads WHERE deleted_at IS NULL GROUP BY phone);
```

**DNC scrub at import** (flag, don't dial):
```sql
SELECT phone FROM leads WHERE dnc = 1 AND phone IN (/* normalized primaries */);
```

**Import infrastructure (V5)** — `import_batches` records each commit (for the summary +
undo); `import_mappings` stores named templates keyed by a header signature; leads carry
`import_batch_id`. Undo:
```sql
UPDATE leads SET deleted_at = ?, updated_at = ?
WHERE import_batch_id = ? AND deleted_at IS NULL;   -- removes only batch-CREATED rows
-- (+ delete their call_list_leads rows; pre-existing updates are not reverted in v1)
```

---

## 6. Filtering & search semantics (locked)

- Search OR across `first_name, last_name, company, phone, email`, case-insensitive.
- Column filters AND together; each a case-insensitive substring.
- `statuses` → `lead_status IN (...)` (empty = no constraint).
- `tags` → lead has **any** of the tags (`json_each`).
- `dnc` tri-state: `ANY` (no clause) / `ONLY` (`dnc=1`) / `EXCLUDE` (`dnc=0`).
- `listId` → membership `EXISTS`.
- `customField` map → each `json_extract` substring, AND-ed.
- Keyset order fixed `created_at DESC, id DESC`; `total` = filtered COUNT ignoring cursor.

---

## 7. Import wizard — calling-grade spec

> Validated by the `buyer` agent (6 buyer personas) + `va` agent (5 daily operators).
> **The wedge / "aha":** drag a raw Apollo/ZoomInfo export with 5 phone columns and messy
> `(415) 555-1234` formatting → the wizard auto-detects columns, normalizes every number,
> lets you pick the dialer's **primary** number while keeping the rest, flags DNC/duplicate/
> invalid rows with counts you can trust — and you're dialing the first lead in **under 60s
> without mapping a single column by hand.** That moment = "built by people who make calls."

### 7.1 Flow — 4 steps, drag-first

1. **Drop** — drag-drop zone **and** a file button. On drop: sniff delimiter/encoding, show
   the **row count** immediately ("4,973 rows detected"), preview the first ~50 rows, warn on
   suspicious delimiter/encoding.
2. **Map** — **auto-detect** columns (header fuzzy-match + content sniffing: a column that is
   90% `+1…` IS the phone even if the header says "Mobile #"); pre-fill every guess.
   Controls: a **default-country** dropdown (set once); a **primary-phone picker** when >1
   phone column; unmapped columns → **custom fields** automatically (no decision forced). A
   **live preview** shows the *normalized* phone so a wrong default country is caught before
   committing 4,000 rows. **Saved mapping templates** auto-apply when the header signature
   matches ("Using saved mapping: Apollo").
3. **Review** — live classification counters: "4,970 valid · 30 need attention · 88 duplicates
   · 4 DNC". Pick the target list (existing **or type a new name inline**). Choose dedupe
   behavior (**update-non-destructive** [default] / skip / merge-numbers). The **Import**
   button is always reachable (no scroll to find it).
4. **Summary** — the **reconciling equation** `rows in file = created + updated + skipped +
   errors`, broken out: created / updated / skipped-duplicate / skipped-invalid / skipped-DNC
   / row-errors. **Download error report (CSV)** (original row + reason). **Undo this import**
   (batch revert).

> Click budget (operator-set): a **known-source re-import ≤ 3 clicks**; a brand-new file ≤ ~6.

### 7.2 Phone normalization — the make-or-break (libphonenumber, never regex)

`PhoneNormalizer` (in `services`, backed by Google `libphonenumber`) classifies every value
into three tiers. Regex is banned — it silently corrupts extensions/intl and destroys trust
the first time it mis-parses.

| Tier | Inputs | Behavior |
|---|---|---|
| **Silent (identity-preserving)** | `(415) 555-1234`, `555.1234`, `tel:+1…`, non-breaking spaces, leading `00`, full-intl missing `+` | strip formatting; `00`→`+`; add `+`; trim invisible chars. |
| **Flagged assumption** | bare 10-digit **with a default country set**; 11-digit `1…` vs 10-digit local | normalize to E.164 and **mark the row "assumed +1"** for spot-check. |
| **Never silent → review tray** | bare local **with no default country**; extensions `x204`; too-short/garbage; `N/A`/blank | extensions **preserved** in a separate `ext` field (never jammed into the dial string, never dropped); ambiguous country → review (**never guessed**); `N/A`/blank → `Empty` (skip row); never pad/truncate; never auto-merge two numbers. |

### 7.3 Multiple phone columns (Apollo ships 5)

Real exports: Apollo → `Corporate Phone, Mobile Phone, Work Direct Phone, Home Phone, Other
Phone`; ZoomInfo → `Direct Phone, Mobile phone, Company HQ Phone`. Map **all** phone columns;
designate **one primary** = the dialer's number and the **dedupe key**. Secondary numbers are
**preserved as custom fields** (`Mobile`, `Direct`, …) so nothing is discarded. Primary
default preference **Mobile > Direct > Work**, **never Corporate/HQ**. (Sales Nav exports
often have **no** phone — tolerate phone-from-another-source, but a row still needs a phone to
become dialable.)

### 7.4 Dedupe — non-destructive, against the DB

Upsert on the normalized **primary** phone, **against the existing database** (not just
within-file). `COALESCE` fills empties + updates changed; a **blank file cell never deletes**
an existing value — **notes / disposition / call history are sacred** and never in the upsert
SET. Default **update-non-destructive**; toggles **skip** (leave existing) / **merge** (attach
new numbers/fields). **Never dedupe on a corporate/HQ phone** — it would collapse every
lead sharing a switchboard into one. Re-importing a refreshed morning list is the *common*
case: "412 new · 88 updated · 4,473 unchanged," not "4,973 imported."

### 7.5 DNC scrub at import (TCPA compliance, not a feature)

Every normalized primary is checked against existing DNC (`dnc=1`) and any mapped `DNC`
column. Matches are **flagged and excluded from dialable**, counted as "skipped: on DNC."
Missing this = enterprise will not pilot; it is a legal control. (DNC is also enforced
pre-dial already, service-side.)

### 7.6 Bad-row tray — never block the file

Validation runs in **preview**, before commit. Good rows import **immediately**; bad rows go
to a **"Needs review" tray** (a filtered grid view) with a per-row reason ("missing country
code", "only 4 digits", "letters in phone"). **Inline fix** in the tray re-validates live and
moves the row out — **no re-import**. A 4,000-row file with 30 bad phones imports 3,970 and
reports 30. **Fail-the-file = instant rage-quit.** Reconciliation must always sum.

### 7.7 Undo last import

Each commit stamps an `import_batch_id`. Undo soft-deletes the leads **created** by the
batch and detaches their list memberships. **Locked limitation (KISS):** updates to
pre-existing leads are **not** rolled back (no before-images in v1); the summary states
this. Covers the "wrong file" panic without building a full audit trail.

### 7.8 Mapping templates

A named mapping (header signature → field map + default country + target list) is saved to
`import_mappings`. On a new file whose header signature matches, it auto-applies — turning a
90-second manual map into a one-click re-import. Operators with fixed client formats (~20
imports/day) save ~25 min/day.

---

## 8. Inline grid — add row / add column (the desktop differentiator)

Operator- and solo-validated as the edge over clunky enterprise loaders. Spreadsheet-style,
keyboard-first; a modal is the *secondary* path only.

### 8.1 Add row (inline, keyboard-first)
- A persistent **"+ new row" pinned at the TOP** (never scroll 10k rows to add one).
- **Tab / Shift+Tab** field→field; **Enter** commits + opens the next blank row (rapid
  sequential entry, no mouse); **Esc** cancels a half-typed row.
- **Clipboard paste**: one line → fills cells L→R in current column order; **multiple lines →
  multiple rows**.
- **Phone validates live in-cell** via `PhoneNormalizer` (red cell + tooltip on failure).
  **Only phone is required** — a phone-only lead is valid (calling reality).
- DB write off the FX thread; `Platform.runLater` the row refresh. No table lock at scale.

### 8.2 Add column (inline custom field)
- A **"+" at the end of the header row** → name it → pick a type (**text** default, **date**
  for best-time/callback, **single-select** for lead-source/priority). One flow — no Settings
  round-trip. Immediately editable in every row; the column **definition** persists once,
  values lazy-fill (no full re-render).
- **Preset one-click columns** (the "built by callers" signal): **Timezone**, **Best time to
  call**, **Callback date/time**, **Lead source / campaign**, **Industry**, **Priority
  (hot/warm/cold)**, **Last outcome/disposition**, **Owner / assigned rep**.

### 8.3 Column layout persistence
- **Reorder, resize, show/hide, pin**; **freeze phone + name on the left**. Persist order/
  width/visibility/pinned per local user (`SettingsService`, key `leads.columns`). Layout
  survives restarts — a 6-hour operator keeps their grid.

### 8.4 Performance
- `TableView` **virtualized**; adding a column or editing a cell must not block the FX thread
  or re-render 50k rows. Honors AGENTS threading rules.

---

## 9. Mid-call quick-add — "call my colleague" (operator make-or-break)

The single highest-value micro-flow per the `va` agent. On a live call a prospect says "call
my colleague Sarah, 415-555-0199" — adding her must **not** leave the call screen or lose the
power-dialer position, or the operator stops doing it and the lead is lost.

**Flow (target < 8s, never leave the call):**
1. **Global accelerator `Cmd/Ctrl+Shift+A`** (works from ANY screen, incl. active call) opens
   a small **quick-add popover** over the call screen — no navigation, dialer position
   untouched.
2. Two fields, focus order: **Phone** (required, live-normalized) → **Name** (optional);
   everything else inherits context.
3. **"Add to [Current List]"** checkbox **pre-checked** → the lead lands in the queue being
   dialed (optional "dial next").
4. **Enter** commits + closes; the operator is back on the call.

Locked OUT (anti-patterns): a full modal, navigating to Leads, no attach-to-active-list,
or losing the dialer position.

---

## 10. Keyboard shortcuts (Leads workbench)

Wire in `LeadsController` (+ an app-level global for quick-add). Extends AGENTS §10.6.

| Action | macOS | Win/Linux |
|---|---|---|
| New inline row (focus top) | `Cmd+N` | `Ctrl+N` |
| Commit row + open next blank | `Enter` | `Enter` |
| Move between fields | `Tab` / `Shift+Tab` | same |
| Cancel current edit | `Esc` | `Esc` |
| Paste row(s) from clipboard | `Cmd+V` | `Ctrl+V` |
| Focus search | `Cmd+F` | `Ctrl+F` |
| Import wizard | `Cmd+I` | `Ctrl+I` |
| Add custom column | `Cmd+Shift+C` | `Ctrl+Shift+C` |
| Mid-call global quick-add | `Cmd+Shift+A` | `Ctrl+Shift+A` |
| Add selected to list | `Cmd+L` | `Ctrl+L` |
| Delete selected (confirm) | `Delete` | `Delete` |
| Toggle DNC on selected | `Cmd+Shift+D` | `Ctrl+Shift+D` |

> `Cmd+D` is already **Open dialer** (AGENTS §10.6) → DNC toggle uses `Cmd+Shift+D` to avoid
> the clash. `Tab` advances the power dialer elsewhere, but inside an active grid-row edit it
> moves between cells — scope the accelerator to the editing control.

---

## 11. Test plan (TDD; AGENTS coverage targets)

- **Domain ≥95%**: `LeadFilter` validation/limit-clamp/unmodifiable; `Cursor`
  parse/format round-trip + malformed rejection; `Page` shape; `LeadStatus` exhaustive
  switch; `Lead` with customFields/leadStatus.
- **`PhoneNormalizer` (highest-value test)**: a **fixture table** of messy inputs →
  expected outcome — `(415) 555-1234`, `555.1234`, `tel:+1…`, `00 44 20…`, bare 10-digit with
  + without a default country, `+1…x204` (extension preserved), UK leading-zero, `N/A`/blank
  (`Empty`), truncated (`NeedsReview`). Asserts the three tiers (silent / assumed-flag /
  review), that extensions are never dropped and countries never guessed.
- **Storage ≥85%** (temp/in-memory SQLite): keyset paging incl. **same-timestamp tiebreak**;
  every filter facet (search, each column, status IN, tag, dnc tri-state, list, custom);
  upsert created/updated/**revive**; `customFieldKeys`; add/remove list membership; bulk
  soft delete; V4 dedupe + partial-unique; **import_batch tag + undo (creates-only)**;
  `import_mappings` CRUD + signature match; DNC-phone lookup.
- **Services ≥90%**: `LeadService` filter pass-through + grid ops; `CallListService` CRUD +
  counts; `ImportMappingService` auto-match; `LeadImportService` — auto-detect Apollo/
  ZoomInfo headers, multi-phone **primary + secondaries→customFields**, **non-destructive**
  upsert (blank file cell does NOT wipe an existing note), **dedupe against DB**, DNC flag,
  bad-row classification + **reconciliation sum**, undo removes only created rows.
- **UI ≥60%**: headless-testable filter/cursor/append; grid add-row commit/cancel/**paste-row
  parse**; add-column definition; column-prefs persistence; quick-add **attach-to-current-
  list** logic; review-tray inline-fix re-validation.

---

## 12. Phasing (ship in slices, each green via `./gradlew test`)

- **Phase 1 — Filter + paging core** ✅ **DONE (2026-06-24)**: domain (`LeadFilter`, `Cursor`, `Page`,
  `LeadStatus`, `LeadColumn`, `Lead` extension) + migration V4 + `findPage`/`customFieldKeys`/`distinctTags` +
  `LeadService.findPage` + Leads UI search/filter/infinite-scroll/dynamic columns. UI carved into
  `LeadsPager`/`LeadFilterState`/`LeadStatusLabel` (headless, tested) +
  `LeadsPageLoader`/`LeadsTableColumns`/`LeadFiltersPopover`/`AddLeadDialog` (view helpers) to keep
  `LeadsController` ≤250 lines.
- **Phase 2 — Lists first-class + inline grid** ✅ **DONE**: `CallListService` + lists rail (create/
  rename/delete, counts) + add/remove + bulk select + **editable grid** (inline add-row,
  inline add-column + presets, column reorder/pin/persist, clipboard paste). Inline editing
  is now core (operator-validated), not deferred.
- **Phase 3 — Calling-grade CSV import** ✅ **DONE** (templates UI deferred): `PhoneNormalizer` (libphonenumber) + 4-step wizard
  (Drop→Map→Review→Summary) + default-country + **multi-phone primary** + **non-destructive
  dedupe against DB** + **DNC scrub** + **bad-row review tray** + reconciling summary + error
  CSV + **saved mapping templates** (V5 infra; service+repo+tests ready, wizard auto-apply UI deferred).
- **Phase 3.5 — Undo last import** ✅ **DONE** (`import_batch_id` revert of created rows; surfaced on wizard Summary).
- **Phase 4 — Mid-call quick-add** ✅ **DONE**: popover + global `Cmd/Ctrl+Shift+A` accelerator
  (the operator-named retention lever).
- **Phase 5 (optional)**: XLSX; proper **multi-number table** (dial secondaries, dedupe-on-
  any-number); smart/dynamic lists (saved filter → membership); auto-update `lead_status`
  from latest call disposition.

---

## 13. Deliberately deferred / not applicable (YAGNI)

- **Multi-tenancy (`userId`)** — single-user local app; dropped entirely.
- **Email validation / provider detection** (No2Bounce, MX, `ContactStatus` deliverability)
  — an email-deliverability concept, irrelevant to voice. Skipped.
- **Proper multi-number-per-lead table** (dial any secondary, dedupe-on-any-number) —
  v1 keeps the **primary** as the key and preserves secondaries as custom fields so nothing
  is lost; the relational table lands in Phase 5 when one-click-dial-secondary is requested.
- **Reverting UPDATEs on undo** — v1 undo removes only batch-*created* leads (no
  before-images). Full audit-trail revert is out of scope.
- **Dynamic auto-sync lists** (sequence's campaign rolling enrollment) — defer until a
  power-dialer "auto-pull new leads" requirement is explicit.
- **Real-time enrichment / data-append from a provider, AI column suggestions, field types
  beyond text/date/single-select** — not v1.
- **Deriving `lead_status` from call dispositions** — deliberately *not* in v1 (join/aggregate
  complexity). Store an explicit column now; wire auto-updates in Phase 5.
- **XLSX import** — CSV covers v1; XLSX in Phase 5.
