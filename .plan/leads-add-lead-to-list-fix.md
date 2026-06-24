# Plan — Fix: "Add a new lead to the list" fails from both entry points

> Status: **IMPLEMENTED** — `./gradlew build` green. Bugfix, additive only. (Not committed.)
>
> Both add-lead paths now route through `LeadsController.persistNewLead(NewLead)`, which
> saves the lead and (when a real list is selected) attaches it via
> `CallListService.addLeads`. `LeadQuickAddBar` now takes a `Consumer<NewLead>` persist
> callback instead of `LeadService`. No test churn (FX seam; underlying service methods
> already covered).

## 1. Symptom (as reported)

> "adding a new lead to the list is not working with both of the options … both ways are failing"

On the Leads workbench, with a list selected in the **LISTS** rail, adding a new lead via
**either** add affordance does not make the lead appear in that list.

The two "options"/ways (both visible in the screenshot):

1. **Top-right "Add Lead"** button → modal `AddLeadDialog`.
2. **Inline quick-add bar** ("Phone / First / Last / Company / Add") pinned above the grid.

## 2. Root cause (confirmed by code read — deterministic, not flaky)

Both paths persist the lead **globally** and never attach it to the **currently-selected
list**. The grid is scoped to `filterState.listId()`, so a non-member never shows → the
user perceives "add to list didn't work" from both options.

| Path | Code | Problem |
|---|---|---|
| Add Lead dialog | `LeadsController.onAdd()` (~L198) | `CompletableFuture.runAsync(() -> leadService.save(newLead))` — **discards** the `Result<Lead>`; never calls `callListService.addLeads(...)`. |
| Quick-add bar | `LeadQuickAddBar.commit()` (~L126) | `CompletableFuture.runAsync(() -> leadService.save(draft))` — same; the bar holds only `LeadService` and has no notion of the selected list. |

Supporting facts (already in place — fix has a clean seam):

- `LeadService.save(NewLead)` **returns `Result<Lead>`** with the persisted id
  (`src/services/.../LeadService.java` ~L108). Both call sites currently ignore it.
- `LeadFilterState.listId()` → `Optional<CallListId>` is the selected list, **empty for
  "All Leads"** (`src/ui/.../support/LeadFilterState.java` ~L78).
- `CallListService.addLeads(CallListId, List<LeadId>)` already exists and is
  `INSERT OR IGNORE` at the repo (idempotent).
- `LeadsController.afterDataChanged()` already re-runs the filtered query **and**
  `rail.refresh()` (updates the badge count), so a post-attach refresh shows the member.

When **"All Leads"** is selected (`listId` empty) the lead correctly shows today because
there is no list filter — which is why the bug reads as "to **the list**" specifically.

## 3. Fix design (KISS — one shared, list-aware persist seam)

Make persistence list-aware in **one** place the controller owns, and route both add
paths through it. The quick-add bar stays a thin view helper (no new service deps).

### 3.1 `LeadsController` — add a shared helper

```java
/** Persist a new lead and, when a real list is selected, attach it to that list.
 *  Runs off the FX thread (callers wrap in CompletableFuture). */
private void persistNewLead(LeadService.NewLead draft) {
    Result<Lead> result = leadService.save(draft);
    if (result instanceof Result.Ok<Lead> ok && callListService != null) {
        filterState.listId().ifPresent(listId ->
                callListService.addLeads(listId, List.of(ok.value().id())));
    }
}
```

- `onAdd()` (dialog): replace `leadService.save(newLead)` with `persistNewLead(newLead)`.
- Quick-add wiring: construct the bar with a persist callback instead of the raw service
  (see §3.2) so its commit calls `persistNewLead`.

Both keep their existing `.thenRunAsync(afterDataChanged / onAdded, Platform::runLater)`
so the grid + rail refresh after save **and** attach complete.

### 3.2 `LeadQuickAddBar` — inject the persist step (decouple from list logic)

Change the constructor dependency from `LeadService` to a persist callback so the bar no
longer decides *what persist means* (controller owns save + list-attach):

- Before: `LeadQuickAddBar(LeadService leadService, Function<String,Optional<PhoneNumber>> parser)`
  and `commit()` does `runAsync(() -> leadService.save(draft))`.
- After: `LeadQuickAddBar(Consumer<LeadService.NewLead> onPersist, Function<...> parser)`
  and `commit()` does `runAsync(() -> onPersist.accept(draft))`.
- Keep all existing UX (live phone validation, clear-fields, `onAdded`, re-focus phone).
- Controller wires: `new LeadQuickAddBar(this::persistNewLead, LeadPhoneParser::parse)`.

This bar is documented "not unit-tested" → no test churn; only the controller references it.

## 4. Behaviour after fix

| Selected rail item | Add via dialog **or** quick-add | Result |
|---|---|---|
| A specific list | save → attach to that list → refresh | Lead appears in the list; badge +1 |
| "All Leads" (`listId` empty) | save only (no attach) | Lead appears globally (unchanged) |

## 5. Edge cases / deliberate carve-outs

- **Duplicate phone already in the list** → `addLeads` is `INSERT OR IGNORE`; no-op, no error. Fine.
- **Save fails** (`Result.Err`, e.g. invalid/duplicate at lead level) → no attach; existing
  behaviour preserved. (Optional: surface an alert — out of scope unless desired.)
- **`callListService == null`** → guard skips attach; lead still saved globally. (Rail only
  exists when the service is wired, so a list can't be selected without it — belt-and-suspenders.)
- **DNC / validation** unchanged — still enforced at the service/repo layer.
- **Out of scope (separate latent risk, noted not fixed):** all repos share **one**
  `java.sql.Connection` while every UI mutation runs on the `CompletableFuture` common pool,
  with no `busy_timeout` and `SQLException` swallowed (`catch (SQLException ignored) {}`).
  Not the cause of this deterministic bug; flag for a follow-up hardening task.

## 6. Test plan (TDD)

Headless, co-located. The controller/bar are FX view helpers (not unit-tested), so cover the
**behavioural seam** at the layer that has tests:

- **New** `CallListService` is already covered; assert `addLeads` idempotency holds (existing).
- **Add** a focused test for the attach decision. Since `persistNewLead` lives in the
  controller (FX), extract the decision is unnecessary — instead verify via the services:
  add a service-level test (if not present) that `save` returns the new id and `addLeads`
  makes the lead a member counted by `countLeads`. (Domain/service ≥90%.)
- Manual smoke (FX): select a real list → add a lead via the dialog → it appears in the list
  and the badge increments; repeat via quick-add; then select "All Leads" → add → appears
  globally and is **not** forced into any list.

## 7. Changes by file

| File | Change |
|---|---|
| `ui/.../controller/LeadsController.java` | Add `persistNewLead(NewLead)`; `onAdd()` uses it; construct `LeadQuickAddBar` with `this::persistNewLead`. Imports: `Result`, `Lead`, `List`. |
| `ui/.../controller/LeadQuickAddBar.java` | Constructor takes `Consumer<LeadService.NewLead>` persist callback; `commit()` calls it instead of `leadService.save`. Drop the `LeadService` field. |

## 8. Validation

- `./gradlew build` — zero errors.
- `./gradlew test` — all green (no test files change; new service test if added).
- Manual smoke per §6.
