# Cella desktop style — implementation guide

Approved by Patrick, updated 7 November 2026. Use this for new screens and changes
to current screens. [Visual examples and verification](VISUAL_POLISH.md) accompany
the code; older mockups/review screenshots are historical references.

## Reuse the existing components

The shared implementation is `desktop/catalog/CatalogStyle.java` and
`desktop/catalog/FeedbackStrip.java`. Other desktop packages already import them.
Keep the green palette and sidebar gradient. Do not create a second palette,
replace the sidebar or call UIManager from individual screens. Application startup
installs the shared defaults once.

| Need | Use |
| --- | --- |
| Page / section / body heading | `CatalogStyle.label(text, 28 / 18–20 / 14, bold)` |
| Main content card | `CatalogStyle.surface(layout)` |
| Transparent layout container | `CatalogStyle.panel(layout)` |
| Primary / secondary action | `CatalogStyle.button(text, true / false)` |
| Back / open navigation | `CatalogStyle.arrowButton(text, primary, back)` |
| Search with magnifier and clear control | `CatalogStyle.searchField(field, placeholder)` |
| Readable form container | `CatalogStyle.widthLimited(form, 720)` |
| Table with a real empty-state message | `CatalogStyle.tableScroll(table, emptyMessage)` or `tableCard(title, table, emptyMessage)` |
| Left text and right numeric columns | `CatalogStyle.alignColumns(table, numericColumnIndices...)` |
| Product-led name + secondary context | `CatalogStyle.twoLineRenderer(row -> caption)` |
| Stock status / rounded badge | `statusBadge(lowLabel)` for the Stock model; `badgeCell(...)` for other models |
| Progress / success / warning / error | `CatalogStyle.feedback(component, FeedbackStrip.Tone...)` |
| Long FCFA amount in a narrow panel | `CatalogStyle.amountLabel()`; set its exact formatted text and accessible name |
| Local display of saved timestamps | `CatalogStyle.localDateTime(instant, locale)` (pilot: Africa/Douala) |

`feedback(...)` hides when its bound label/text area is blank. `setTone(...)`
changes its meaning; do not duplicate this with local tint colors. It also accepts
a scroll pane containing a text component. Message text stays readable; icon and
color supplement the words.

Use INFO for loading/normal guidance, SUCCESS only for confirmed results, WARNING
for pending or uncertain outcomes, and ERROR for blocked/failed operations.
Changing a visual state must never clear an immutable pending request. Do not
replace an uncertain result with a success message to make the screen look tidy.

## Layout and controls

- One page title, optional short subtitle, relevant actions on the right. No tiny
  duplicate section bar. Subviews have a labelled back arrow and retain filters.
- Forms align left and stop at 720px. Keep Save/Cancel just beneath their fields;
  allow the form to scroll on short windows instead of stretching or clipping it.
- Body/control text is 14px, section titles 18–20px, page titles 28px. Reserve smaller
  type for secondary reference/category/date metadata. Use the installed sans-serif
  family; no downloaded font is required.
- Retain the approximately 60/40 Sales picker/basket layout. Total, cash confirmation,
  uncertain retry/recovery and Encaisser belong inside the basket. An empty basket
  stays compact. Read-only sales history can use the wider page area.
- Use normal buttons for secondary actions and green primary buttons for the main
  action. Disabled controls remain gray and readable. Keep focus rings visible.
- Names, quantities, units, references and reasons must be legible. Use tooltips for
  truncated secondary text, not as the only way to identify an action.

## Tables, empty states and messages

Create the table/card first, call `alignColumns`, then install specialized cell
renderers. `tableScroll`/`tableCard` apply shared defaults during construction.
Text/header alignment must match; quantities and prices align right. Shared tables
have no grid, zero intercell spacing and a continuous pale-green selected row.
Use 40px rows, or 56px for a genuine two-line product entry.

The empty-state message must describe the actual loaded state. Loading errors are
not zero stock or an empty shop. Do not draw a large blank box in place of an
explanation. Collapse blank diagnostics and unnecessary empty controls.

Stock badges use actual persisted minima: zero red, low amber, normal green,
unavailable gray. Keep text labels and units. Never hard-code the demo's threshold
of five or label every unit as pieces. `statusBadge(lowLabel)` assumes the current
Stock table's available quantity is column 2; other models should explicitly map
their state to `BadgeTone` and use `badgeCell` instead.

## Language, access and navigation

All visible wording goes through both French and English resource bundles. Write
short shop language: “Ajouter une photo”, “Nouvelle livraison”, “Réessayer cette
réception”. Required fields stay explicit. Label inputs with `setLabelFor` and an
accessible name. Provide keyboard selection, Enter and labelled actions; double
click is a shortcut, not the only way to open a row.

Preserve pending work, selection, filters and the basket when navigating. Never
create a replacement AppContext/database, rebuild Checkout, or resubmit a pending
operation as part of a styling change. All file, image and database work remains
in background workers; ignore superseded results.

## Evidence required for a PR

Run Java 21 wrapper clean verify and packaged startup as documented in README.
Use disposable data. Add tests for changed navigation, persistence or recovery
behavior; do not add tests just to repeat a color constant.

Show the actual Java screen at 960×600, 1100×720 and a wide window such as
1600×1000. Include loaded, empty, validation/error and pending/recovery states
relevant to your milestone. Test keyboard use and resizing in an actual desktop
when available. Label component renders/headless checks accurately and list
untested Windows/native-dialog checks. Do not claim interactive verification from
a render alone. Coordinate shared ShellPanel/CatalogStyle changes with Patrick.

## Current finishing-pass previews

These are actual Java component renders using disposable data, not browser mockups.

- [Product photo feedback](images/overall-photo-error.png)
- [Product movement history](images/overall-movement-history.png)
- [Expanded product form](images/overall-form-details.png)
- [CSV validation and progress](images/overall-csv-feedback.png)
- [Stock feedback](images/overall-stock-feedback.png)
- [Cash confirmation](images/overall-sales-confirmation.png)
- [Loading](images/overall-startup-loading.png)
- [Startup retry](images/overall-startup-error.png)
