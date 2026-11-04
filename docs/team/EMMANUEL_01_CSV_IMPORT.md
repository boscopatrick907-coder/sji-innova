# Emmanuel — Step 1: CSV product import

Priority: FIRST. Owner: Emmanuel. Desktop integration: Patrick.
This is a new backend assignment, not a claim that CSV import exists today.
Branch suggestion: `backend/csv-product-import`.

## Goal in plain language

A shopkeeper exports an Excel catalog as CSV. Cella checks it without writing,
shows which rows are valid, then imports explicitly selected valid rows after
confirmation. Existing products and stock must remain safe. Direct .xlsx support
and automatic column guessing are later tasks.

## Milestone A — agree the format and interface first

Inspect ProductService/ProductRepository and existing validation/transactions.
Propose a small service interface for preview and confirmed import, with structured
row errors/warnings and an immutable reviewed request. Agree it with Patrick
before implementing persistence, so he can build the preview screen independently.
Use a maintained CSV parser, not split(","); document the dependency choice.

Supply a UTF-8 sample/template under docs/import/ with this header:

```csv
name,model_reference,category,unit,buying_price,selling_price
T-shirt classique,TS-001,Vêtements,piece,2000,3000
Riz parfumé,RIZ-001,Alimentation,kg,600,800
Câble USB-C,USB-02,Accessoires,piece,,2500
```

Required values: name, canonical unit (`piece`, `kg`, `litre`) and selling price.
Other listed values may be blank; blank buying price means unknown, not zero.
Zero is valid; buying price may exceed selling price. Prices must be nonnegative
whole FCFA within long range. Reuse core product validation rather than inventing
conflicting rules. Specify header handling, whitespace and row-number conventions.
Support quoted delimiters/quotes/newlines, French accents, CRLF and a UTF-8 BOM.
Offer an explicit comma/semicolon delimiter option for Excel exports; never
silently misinterpret an entire row. Reject unsupported encoding with clear help.
Reject unsupported quantity/photo columns explicitly rather than silently dropping
them. Specifications and arbitrary column mapping can follow later.

## Milestone B — preview, then confirmed import

- Preview MUST write nothing. Return source record/line references, normalized
  values, errors and duplicate warnings for Patrick's UI.
- Flag possible duplicates within the file and current shop. Product names and
  model references are not guaranteed unique: never merge/update on name alone.
  The default skips candidates; an explicitly confirmed distinct product is allowed.
- Import only selected reviewed rows in the current shop. Do not re-read a changed
  source file and silently import different content; bind confirmation to the
  reviewed content/options and revalidate against current data before writing.
- Preserve the existing product service's business rules and atomic product/spec
  inserts. Do not access SQLite from the desktop button handler.
- Choose and document a precise transaction policy. A practical first version is
  per-row commits with explicit imported/skipped/failed results. Do not claim the
  whole batch rolls back if previous rows actually committed.
- Make same-request retries safe after timeout, partial failure and process restart.
  Persist an import operation/row mapping transactionally with each created product
  (or propose an equivalent tested design). A memory-only list or file hash alone
  is insufficient. Same operation with changed content/selection must be rejected.
  Detect previously imported content as a warning even under a fresh operation ID;
  require explicit confirmation for a deliberate repeat.
- Report counts and row results, including persisted product IDs. Do not expose
  raw SQL stack traces as the user message. No automatic import on app startup.

## Scope boundaries

No stock quantities, stock movements, sales, images, existing-product updates or
UI. Every imported product starts with the existing no-movement stock state.
Opening stock will be a separate stock operation with its own audit history.
No direct .xlsx upload; explain Excel's “Save As / CSV UTF-8” procedure in the guide.

## Acceptance tests

1. Preview a valid accented/quoted CSV: zero products created.
2. Confirm three valid rows: three products persist after reopen; blank buying
   price remains null and IDs/shop scope are correct.
3. Invalid unit/name, negative/fractional/overflow price, malformed quoting,
   duplicate headers and delimiter errors have useful row/file diagnostics.
4. Duplicate candidates require explicit treatment and do not overwrite products.
5. Simulate a mid-import failure; results match actual committed rows. Retry after
   reopen creates no duplicates, including a retry after success before UI response.
6. Changed request under the same operation ID is rejected. Stock/history unchanged.
7. Existing product/stock/sales/void tests and migrations still pass.

## Working and delivery rules

Read README.md, CONTEXT.md, the current code and applicable AGENTS.md. Preserve
unfinished work; start a small feature branch from updated main after this brief
merges. Keep Java 21 and the existing stack. No UI, sync or authentication in this
task. Do not alter stock/sale behavior or rebuild another developer's screen.
Coordinate core/schema changes with Patrick and preserve existing databases.
All desktop service calls will run off Swing's EDT. Errors must be actionable,
not silently swallowed. No real shop files or personal photos in Git/test fixtures.

Run the full wrapper clean verify and packaged smoke test from README.md. Use
meaningful temporary-file/database tests, including reopen and failure cases.
Report actual commands/results, limitations, migration behavior and a small Java
calling example for Patrick. Deliver one reviewed PR per milestone. Do not push,
merge, force-push or modify repository settings without your human's request.
Explain each step simply; do not claim a proposed interface already exists.

## Prompt to paste into your AI

> Read docs/team/EMMANUEL_01_CSV_IMPORT.md and CONTEXT.md. Inspect current core
> code. My current task is CSV import, not the completed sale-void task. Explain
> the format/interface first, then implement preview and safe confirmed import
> in reviewable milestones. Preserve stock and existing products. Supply template,
> tests and a calling guide for Patrick. Do not push or merge without my request.
