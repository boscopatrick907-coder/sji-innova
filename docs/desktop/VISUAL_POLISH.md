# App-wide desktop visual polish — 6 November 2026

The existing green palette and sidebar gradient remain. Products, product details,
the add form, Stock, CSV import, Sales and startup/recovery pages now use clearer typography, softly rounded buttons, white
content surfaces and consistent table styling. No downloaded fonts are required.

- Product/family/detail navigation has labelled back arrows and forward arrows.
  Back from a grouped product returns to its family; a standalone product returns
  directly to Products. Search/filter state is retained. Pending photo writes
  continue to disable navigation; form cancellation remains disabled during saving.
- Product details explicitly label the photo area, show JPEG/PNG and size guidance,
  and give Choose photo a prominent green button. Existing replacement/removal and
  storage behavior is unchanged. Photos still attach after saving the product.
- Product creation has a distinct form surface, a clearer optional-information
  control, a back arrow on Cancel, and a family example in the editable input.
- Family entries are compact, labelled forward controls. Individual products remain
  immediately visible; tables use restrained green selection and quiet headers.
- Sales groups product selection and basket in white cards. At small heights the
  cards scroll instead of collapsing the product table. Payment actions remain in
  the fixed footer. Existing keyboard shortcuts and checkout protections remain.

## App-wide extension

Stock now has the same receipt card, headings, tables and green primary action.
Available quantities and movement history scroll without collapsing their rows.
The CSV file chooser controls, preview, diagnostics, import result/retry area and
Back action use the same surfaces and fonts. At small heights a loaded preview
moves toward the review table; file controls remain reachable by scrolling up.
CSV consent, recovery, validation and persistence rules are unchanged.

Checkout confirmation/recovery messages have a white action surface; startup loading
and failure/retry use the same typography, rounded controls and green accents.
Product movement history uses a titled card. Shared table and scrolling helpers
keep the current screens consistent. Stock receipt durability, Sales history and
creation-time photos remain separate functional assignments.

## Actual Java previews

Sample data only, rendered from Swing components without an interactive display:

- [Products](images/products-polished.png)
- [Product details and photo action](images/product-photo-polished.png)
- [Add product](images/add-product-polished.png)
- [Sales](images/sales-polished.png)
- [Sales at 960 × 600](images/sales-polished-960.png)
- [Stock](images/stock-polished.png)
- [Stock at 960 × 600](images/stock-polished-960.png)
- [Movement history at 960 × 600](images/stock-history-polished-960.png)
- [CSV preview](images/csv-polished.png)
- [CSV at 960 × 600](images/csv-polished-960.png)
- [Cash confirmation](images/checkout-polished.png)
- [Loading](images/startup-loading-polished.png)
- [Startup failure and retry](images/startup-error-polished.png)

Java 21 verification: 248 tests passed, no failures/errors/skips; packaged headless
startup passed. The existing standalone-product navigation test now clicks the
actual back button and checks the returned product list. UI layout was inspected
in renders at 1100 × 720 and 960 × 600. Actual Windows GUI interaction, OS file
choosers and accessibility still need hands-on checking.

## Friendlier layout and wording

Products now reads as a product list, with names, saved photos, price and stock
rather than spreadsheet headers. References/categories remain beneath the name;
missing or unreadable photos use a neutral initial. Families, filters, references,
keyboard selection, Enter/double-click and the explicit View product action remain.
The main list avoids the repeated breadcrumb row to leave more room on small screens.
French/English labels across product forms, photos, deliveries, CSV review and cash
payment now use simpler shop language. Errors and cash confirmation stay explicit.

Thumbnails are read from the managed photo metadata in background workers: at most
four concurrent requests, a 128-entry cache, bounded encoded size and dimensions.
Large original images are not decoded just to paint a list row. Repaints do no disk
or SQL work. Refresh and photo changes invalidate the cache. A missing thumbnail
never removes the product or changes its stock/price. No schema or service writes
were added to browsing.

[Products preview](images/products-friendly.png) ·
[Small window preview](images/products-friendly-960.png)

Verification: 249 tests passed, zero failures/errors/skips, plus packaged headless
startup. A real temporary-SQLite photo test checks asynchronous loading, replacement
and cache invalidation, missing thumbnail fallback and removal. Existing catalog,
stock, CSV and checkout tests pass; translation assertions reflect the new labels.
Previews use actual Java components with disposable data and show initials where
sample products have no photos. Interactive desktop and Windows checks remain manual.

## Page layout review — 7 November 2026

Applied Patrick's seven-point UI review on top of main `9ca358e`:

- Product and receipt forms stay left aligned within 720px. Product Save/Cancel
  sit directly beneath the form instead of at the bottom of a wide window.
- Tables use left-aligned text, right-aligned quantities/prices and matching
  headers, 14px body text, padded cells, no grid and zero cell spacing. Selection
  fills the row. Stock status has green/amber text badges; labels remain readable
  without color. Two-line product rows carry reference/specification or unit-price
  context, with full text available in tooltips when narrow columns truncate it.
- Empty cart, history and import tables display a centered line icon and concise
  message. The empty cart and import preview use compact cards. Blank diagnostics
  are hidden; they reappear when there is an explanation to read.
- Sales uses approximately 60/40 side-by-side product selection and basket. The
  basket shows product, editable quantity and line total; saved unit-price context
  appears under the product name. Total, cash confirmation, retry/recovery and
  Encaisser stay inside the basket. No empty footer card remains beneath the page.
- Removed the redundant tiny section bar. Products, Stock, Sales, import and form
  views have their own title and relevant actions. The sidebar gradient is intact.
- The slightly darker warm page background distinguishes white content cards;
  green headers, selections and focus accents connect them to the sidebar.
- Stock-unavailable messages wrap in an amber alert strip with an icon; normal
  guidance stays quiet. Disabled buttons use readable gray styling. Product
  double-click/Enter navigation and the explicit View product action remain.

Cash-sale state transitions, pending-file ownership/recovery and service contracts
are unchanged. Reference/unit/detail columns remain in the table models for the
existing integration contract; compact Sales presentation moves that context into
product rows rather than removing it from the data.

### Updated Java previews

- [Sales, 960 × 600](images/sales-layout-960.png)
- [Sales, 1600 × 1000](images/sales-layout-wide.png)
- [Empty basket and stock warning](images/sales-empty-layout.png)
- [Stock alignment and badges](images/stock-layout.png)
- [Product form, wide window](images/product-form-layout-wide.png)
- [Empty import preview](images/csv-empty-layout.png)
- [Cash confirmation in the basket](images/checkout-layout.png)

### Verification and its limits

Java 21 clean verification: **251 tests passed, no failures/errors/skips**, plus
packaged headless startup. New layout contracts check side-by-side panels at 960
and 1600px, payment and confirmation fitting 960 × 600, continuous selection color,
right-aligned numbers, and actual form containers capped at 720px. Existing cash
sales, stock retries, photo, CSV recovery and persistence tests remain passing.
Previews are actual Swing component renders with disposable shop data.

The cloud-desktop/runtime skills were checked. Managed environment
`ccarenv_b64_Y2NhcmVudl81Yjc2YWI3M2Y5ZWM4MTkxODg5YjIwMDBhNmZhOWIwZQ`
reported `capabilities: []`; no desktop JavaScript/computer-use or browser tool was
exposed, and no Xvfb executable was installed. An interactive Linux or Windows
window was therefore not verified. Native file choosers, OS window decoration and
hands-on keyboard/accessibility checks still require a local desktop.

## Visual details adapted from the supplied VentesPanel demo

The supplied standalone example is used as a visual reference. The actual Cella
SalesPanel, service calls, immutable checkout request, file ownership protections,
confirmation and restart recovery remain in place.

- Products and Sales searches have a leading magnifier and a clear-text control.
  Existing accent-insensitive, multi-token matching and basket state are retained.
- Shared cards have a subtle lower shadow in the approved palette.
- Stock counts and statuses use rounded badges: zero red, configured low stock
  amber, normal green, unavailable gray. Thresholds come from persisted StockLevel,
  not the example's fixed five-item rule. Units and fractional quantities remain.
- Total has its own caption and a larger amount. Long amounts scale to the basket
  width for display; the underlying amount, exact FCFA arithmetic and accessible
  text remain unchanged. Encaisser is slightly more prominent.
- The demo's immediate basket clearing, integer-only quantities, hard-coded piece
  labels and minimum 400px basket width were not adopted.

[Before](images/sales-layout-960.png) ·
[Updated Sales](images/sales-demo-details.png) ·
[Search control and unavailable stock](images/sales-search-details.png) ·
[Stock badges](images/stock-rounded-badges.png) ·
[Product search](images/products-search-details.png)

Verification: 251 tests passed, zero failures/errors/skips, plus packaged startup.
Existing tests still cover failed/uncertain checkout, retry ownership, fractional
quantities and rounding. Updated total-label assertions verify the same displayed
amounts with the new separate caption. Layout tests retain the 960px/1600px guards
and check badge/header alignment and full-row selection. Previews use actual Java
components with temporary data; the amber rice badge uses a saved minimum of four
bags in that fixture only. The interactive desktop limitation recorded above remains.

## App-wide finishing pass and team handoff — 7 November 2026

The remaining live screens now follow [the implementation guide](STYLE_GUIDE.md):

- Shared FeedbackStrip provides INFO/SUCCESS/WARNING/ERROR messages, matching
  icons, rounded tints and automatic collapse when bound text is blank. Sales
  continues to preserve pending checkout state and explicit cash confirmation.
- Product-form validation and saving use the shared feedback; optional details
  have distinct collapsed/expanded arrows. Fields/actions remain capped at 720px.
- Photo details have a matching card and clipped, aspect-preserving image view.
  Loading, saved, removed, damaged and failed states use shared feedback. A failed
  photo attempt restores the previous removal-button availability and keeps the
  existing photo/metadata. File operations and image reads remain off the EDT.
- CSV progress, import outcomes and errors use typed feedback. Valid/warning/error
  rows have labelled rounded badges. Blank diagnostics/status consume no space.
  Duplicate consent and durable import/retry requests are unchanged.
- Stock feedback uses the shared component. Refresh does not erase confirmed receipt
  messages or unresolved-receipt warnings. Quantities, history and retry IDs remain
  unchanged. Stock and product history display local Africa/Douala times without
  modifying stored instants or movement ordering.
- Product movement history is a read-only aligned table with signed quantities,
  translated types and a compact empty state. Its back arrow returns to the product.
- Startup has a branded loading card and thin progress indicator. Failed startup
  uses a compact 720px retry card with a clear error area and the existing retry/
  quit behavior.

WILLY.md and CHARLES.md are still the only current Markdown briefs named for those
developers. Their functional milestones remain intact; both now require the shared
style and explain how to merge current main into existing work without rewriting
commits. The team index links the single shared style guide.

Verification: **252 tests passed, zero failures/errors/skips**, plus packaged
headless startup. Added a real temporary-SQLite product-history navigation check
covering signed movements, a UTC-to-Douala date boundary, return navigation and
unchanged stock/history. Existing photo tests now verify visible success/error
feedback while preserving the saved photo. Stock tests verify that refresh retains
confirmed and pending receipt messages. Desktop layout and cash-recovery checks
remain passing. Native desktop control is still unavailable in the managed
environment described above; renders do not certify interactive Windows dialogs.
