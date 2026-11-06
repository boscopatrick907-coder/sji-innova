# Products and Sales interface corrections

6 November 2026. These changes address the actual desktop screenshots supplied by
Patrick, on top of main `387ec90`.

- Standalone products appear directly in Products, including reference, quantity
  and selling price. Families remain available as compact groups above the list.
- Category filtering shows matching individual variants directly, including those
  without a family. Opening a standalone item does not add a redundant family
  breadcrumb. Singular family counts are translated correctly.
- Sales has explicit product-selection and basket headings. Characteristics retain
  their names, rather than showing isolated values.
- A zero/unknown/unloaded stock selection disables Add to basket. Enter and
  double-click enforce the same rule. Receiving stock and refreshing enables it.
  The backend still validates the final sale against current stock atomically.
- Sidebar icons distinguish Products, Stock and Sales. Existing checkout recovery
  and confirmation behavior is retained.

Java 21 verification: 248 tests, no failures/errors/skips. Tests include immediate
standalone visibility/open/back, zero-stock button and keyboard rejection,
characteristic labels, and enablement after receiving stock. Existing checkout,
CSV/photo and persistence tests remain passing. Packaged headless startup passes.

Preview images are real Swing component renders using temporary sample data,
not HTML mockups or screenshots of a live shop. Generated at 1100x720 and 960x600;
this does not certify interactive Windows layout/file choosers or usability.

- [Products preview](images/products-clarity.png)
- [Sales with a basket](images/sales-clarity.png)
- [Sales with unavailable stock](images/sales-no-stock.png)

Product photos remain on saved-product details; creation-time photos and the
product-to-stock shortcut are not part of this change. Willy still owns durable
receipt recovery and Charles owns sales history. No database schema changes.
