# Dialer UX/UI Fixes — Plan

Make the dialer's **country selector behave like a native HTML5 `<select>`** and the
**number input behave like a native HTML5 number input** (`<input>`), and close the
small UI/UX gaps found in an edge-case audit.

Status: **PLANNING — approved; implementing.**

> **Review folded in (2026-06-22):** Phases 1 & 3 merged — a half-migrated number
> field (native `TextField` + the old root key-filter still inserting digits) would
> double-input, so the physical-key path is *deleted*, and button-focus + initial-focus
> changes land together with the field swap. `isDialable` is **required** (not optional):
> once the dial-code prefix lives in the field, `isBlank` is never true, so Call would
> wrongly enable on a bare `+52`. Country selector uses **live-preview + Escape-restore**
> (JavaFX `ComboBox` selection *is* commit; a true "no commit until Enter" fights the
> control). `C4`/`C5` need internal-skin access (no public API) → best-effort.

Files in scope:
- [dialer-view.fxml](../src/ui/src/main/resources/fxml/dialer-view.fxml)
- [DialerController.java](../src/ui/src/main/java/com/elitale/coldbirds/coldcalling/ui/controller/DialerController.java)
- [cupertino-light.css](../src/ui/src/main/resources/css/cupertino-light.css) (dialer section ~744–970)
- New support class(es) + tests under `src/ui/.../ui/support/`

---

## 1. Root-cause summary

| # | Area | Root cause |
|---|---|---|
| R1 | Number input | `numberDisplay` is a **`Label`** + a fake blinking caret (`Region inputCaret`), bound read-only via `textProperty().bind(...)`. A Label cannot be focused, selected, copied, pasted, or edited mid-string. |
| R2 | Country selector | Editable `ComboBox` filters on `KEY_RELEASED` but **never handles Up/Down**, and Enter calls `commitFirstMatch()` which always picks `filteredCountries.get(0)` regardless of what's highlighted. |
| R3 | Country selector | Editor text is only reset when the **popup closes**; on plain focus-loss (Tab/click away without opening popup) a stale search query lingers and diverges from the model. |

---

## 2. Audit findings (what a user can hit)

### A. Number input (currently a Label)
| ID | Severity | Issue |
|----|----------|-------|
| N1 | High | Cannot click the field to focus it. |
| N2 | High | Cannot paste a number (Cmd/Ctrl+V) — blocker for "copy number from CRM → paste → call". |
| N3 | High | Cannot select text; cannot copy the typed number out. |
| N4 | Med | No cut, no right-click context menu (Cut/Copy/Paste). |
| N5 | Med | Caret is fake (a `Region`) pinned to the end; no real cursor, no mid-string edit. Backspace only deletes the last char. |
| N6 | Med | No max length — can type past the E.164 limit (15 digits). |
| N7 | Med | Pasting a formatted number `+1 (202) 555-0142` has nowhere to go / wouldn't sanitize. |
| N8 | Low | No way to enter `+` by mouse/touch (only the physical-keyboard path allows it; dial pad has `* 0 #` only). |
| N9 | Low | `Call` is enabled for any non-blank value, e.g. a single digit. |

### B. Country selector (editable ComboBox)
| ID | Severity | Issue |
|----|----------|-------|
| C1 | High | **Up/Down arrows don't move through the list** (the reported bug). |
| C2 | High | Enter commits the **first filtered match**, not the highlighted row — so even after navigating, Enter picks the wrong country. |
| C3 | Med | Focus-loss with a half-typed query leaves stale text in the closed control (diverges from the selected country). |
| C4 | Med | Filtering to zero results shows an **empty white popup** with no "No countries" placeholder. |
| C5 | Med | Opening the popup doesn't **scroll to / highlight the current selection** (native `<select>` does). |
| C6 | Low | Escape while searching should cancel and restore the selected country + editor text. |
| C7 | Low | Clicking the (editable) field doesn't select-all, so typing a fresh search appends to the old text. |

### C. Cross-cutting / focus
| ID | Severity | Issue |
|----|----------|-------|
| X1 | Med | `dialerRoot` is a root-level `KEY_PRESSED` **event filter**: typing a digit while the Recent Calls list has focus is hijacked into the number and consumed, breaking list type-ahead / and Backspace deletes a dialed digit instead of acting on the list. |
| X2 | Low | View entry always `requestFocus()` on root, so the country box can't be the initial focus target. |
| X3 | Low | Dial-pad buttons are `focusTraversable` and show a focus tint, competing with the number-input focus model. |

---

## 3. Design decisions

### Resolved questions
- **`*` / `#`:** accepted into the field (the dial-pad keys stay functional, tel-input
  leniency) but **stripped when building the E.164** (`toE164` keeps only a leading `+`
  and digits — `PhoneNumber` validates `\+[1-9]\d{1,14}`). Documented, not silent.
- **`isDialable` is required**, not optional — see D1.
- **Country selector** = live-preview + Escape-restore, not "commit only on Enter".

### D1 — Replace the number `Label` with a real `TextField` (fixes N1–N9, X1–X3)
This is now **one self-contained change** (old Phase 3 folded in — a partial migration
breaks input):
- Swap `numberDisplay` (Label) + `inputCaret` (Region) → a single `TextField`. **Fold the
  pill styling into the field** so the native `:focused` ring works; drop the
  `numberDisplayBox` wrapper, the `caretBlink` `Timeline`, and the `input-focused` toggle.
- **Delete the physical-key dialing path** (`onKeyPressed` digit/Backspace/Enter branches,
  `DIALPAD_KEYS`, `focusDialpad`). The `TextField` handles typing natively; a
  `TextFormatter` sanitizes. (Keeping the old root `KEY_PRESSED` event-filter would
  double-insert or desync — this is why old Phase 3 must merge here.)
- **Dial-pad buttons:** insert at the caret via `replaceSelection`, set the keys
  `focusTraversable="false"`, and re-focus the field after each press so the caret stays
  put. (folds X3)
- **Initial focus → the number field** on view entry, so "just start typing" still works
  without a root key-filter. (folds X2)
- **Source of truth:** the field holds the full visible string incl. prefix (matches the
  `+52` screenshot). Drop `composeDisplay()`.
- **Country → field coupling:** in the `selectedCountry` listener use `old`/`now`; if the
  field is empty or equals the *previous* dial code (± trailing space), replace with the
  new `dialCode + " "`; otherwise leave the user's number alone.
- **`TextFormatter`** filter = `DialNumberFormatter.sanitize` on the proposed full text
  (whole-content replace idiom); caps digits at 15. Sanitizes pastes for free.
- **Call enabled ⇔ `DialNumberFormatter.isDialable(text)`** (≥ 5 digits ⇒ more than a bare
  ≤4-digit dial code). **Backspace** enabled ⇔ `!text.isEmpty()`.
- **`toE164` / `prefillNumber`** delegate to `DialNumberFormatter`.

### D2 — Country selector: non-editable dropdown (no text input)
**Decision (2026-06-22):** the country selector takes **no keyboard text input**. An
editable combo let stray characters land in the field (e.g. `"nChad +235"`), so it is now a
plain, non-editable `ComboBox`:
- `setEditable(false)` — no editor `TextField`, hence no stray-character / focus-leak /
  sync bugs. Selection is by mouse-click or the platform's built-in type-ahead + Up/Down,
  which a non-editable `ComboBox` provides natively (no custom key handling needed).
- Closed control shows plain converter text via an explicit button `ListCell` (reusing
  `CountryCell` would paint a white box inside the grey pill).
- A selection drives `selectedCountry` → dial prefix + local time. Persistence is deferred
  to popup-close (or a closed-state selection change) via `committedCountry`, so arrowing
  the open list doesn't write settings on every step.
- **Removed:** the editable search (`CountrySearch`) and custom arrow-nav
  (`CountryNavigation`) — both dead; helpers + tests deleted.

### D3 — (merged into D1/Phase 1)
Key-handler ownership, dial-pad button focus, and initial focus are all handled in D1 —
there is no separate later phase, because they are prerequisites for the field swap to
work at all.

---

## 4. Extract testable logic (TDD-first)

Controllers aren't unit-tested here (pattern: extract pure logic into `ui/support/` + JUnit). New pure helpers:

1. **`DialNumberFormatter`** (`ui/support/`)
   - `sanitize(String raw) -> String` — keep a leading `+` and `0-9` (+ `*`/`#` for the
     dial-pad keys); drop separators/letters/emoji; cap at 15 digits.
   - `toE164(String visible, Country selected) -> String` — `+`-prefixed ⇒ `+` then digits
     only (strips `*`/`#`); else prepend the selected dial code.
   - `isDialable(String visible) -> boolean` — **≥ 5 digits** (a bare dial code is ≤ 4, so
     this prevents Call enabling on just `+52`). Lone `+`/empty ⇒ false.
   - Tests: `"+1 (202) 555-0142"` → `"+12025550142"`; `"2025550142"` + US → `"+12025550142"`;
     strips letters; caps at 15 digits; `"+52 "`/`"+"`/`""` not dialable; `"+5255123"` dialable.

2. **`CountryNavigation`** ~~(`ui/support/`) — pure index math~~ — **removed.** The country
   selector is a non-editable dropdown (no search/nav logic), so it needs no extracted
   helper; native `ComboBox` keyboard behavior covers it. (Helper + test deleted.)

`CountrySearch` ~~already exists and is tested — reuse as-is~~ — **removed** (no search box).

---

## 5. Implementation phases

- **Phase 1 — number input → real `TextField` (self-contained; old Phase 3 folded in)**
  1. `DialNumberFormatterTest` (red) → `DialNumberFormatter` (green).
  2. FXML: Label + `inputCaret` Region → one `TextField` (pill styling on the field).
  3. Controller: delete the physical-key path (`onKeyPressed` digit/BS/Enter, `DIALPAD_KEYS`,
     `focusDialpad`), `composeDisplay` binding, `configureCaret`/`caretBlink`; add the
     `TextFormatter`; dial-pad buttons `replaceSelection` + `focusTraversable=false` +
     re-focus field; initial focus → field; Call ⇔ `isDialable`, Backspace ⇔ `!isEmpty`;
     country-change prefix swap; `prefillNumber`/`toE164` delegate to the formatter.
  4. CSS: restyle `.dialer-number-text` → the `TextField`; drop `.dialer-caret`; `:focused`
     ring on the field.
  5. Verify: `:ui:test` + build; manual paste/caret/copy/cut/context-menu.

- **Phase 2 — country selector → non-editable dropdown**
  1. `setEditable(false)`; items = full country list; explicit text-only button cell.
  2. Selection drives the prefix; persist on popup-close via `committedCountry`.
  3. Delete the editable search + arrow-nav code and the now-dead `CountrySearch` /
     `CountryNavigation` helpers + tests.

- **Phase 3 — verify**
  - `./gradlew :ui:test :services:test` then `./gradlew build`.
  - Manual matrix (see §6). Note: the **controller wiring is not auto-tested** (no TestFX);
    only the pure helpers are. Acceptance for the wiring is the manual matrix.

---

## 6. Edge cases to verify
- Paste `+`-prefixed full E.164 over an existing local number → replaces correctly, country prefix not double-applied.
- Switch country after typing a full local number → does NOT clobber the typed number.
- Switch country when field holds only the old dial code → swaps to the new dial code.
- Paste with letters/emoji → sanitized out; caret stays sane.
- Max length: long paste truncated to 15 digits (plus `+`).
- Country box accepts no text input — only click / Up-Down / type-ahead selection.
- Enter in number field still triggers Call; the non-editable country box never accepts typed digits.
- Backspace in Recent Calls list no longer deletes a dialed digit.

## 7. Out of scope (YAGNI)
- Full libphonenumber-style as-you-type formatting/validation per country (big dep). Keep sanitize + E.164 prefix only.
- Long-press-0-for-`+` gesture; instead `+` is reachable via keyboard and via pasted `+`.
- Dark-theme CSS parity beyond what already exists (only the one stylesheet is present).

## 8. Acceptance
- Country box: Up/Down navigates + scrolls, Enter commits the highlighted row, Escape cancels, no stale text on blur, "No countries" on empty, opens scrolled to current.
- Number box: click-to-focus, real caret, select/copy/cut/paste/context-menu, sanitized paste, max length, mid-string edit.
- `./gradlew build` green; new support classes ≥ 90% covered; existing tests unaffected.
