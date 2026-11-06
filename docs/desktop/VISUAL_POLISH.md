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
