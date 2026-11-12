# Cella — two offline visual directions

Prepared 12 November 2026 by Patrick and Codex. These are interactive browser prototypes for choosing
our next Java design. They do not read the shop database, save real products, submit sales or implement
the dashboard/restore services. All names, stock and statistics are sample data; product artwork is
original SVG placeholder illustration, ready to be replaced by real product photos in production.

## Open and compare

Download `index.html` and open it in a modern browser. It is a single self-contained file: no build,
account, external library, image service, font download or network connection is required. GitHub's
file view shows source code; download the file to interact with it.

Use the top switches **A · Atelier** and **B · Comptoir**, then the sidebar:

- **Vue d'ensemble:** sample summary, period switch, sales trend and low-stock list. The period switch
  changes example totals; the chart explicitly remains a seven-day example.
- **Produits:** A uses families and photo-led cards; B uses direct variant rows. Search by name,
  reference or specifications, filter by family, open a family or variant, and try adding a sample
  with an optional locally previewed photo.
- **Stock:** variant quantities, references and labelled low-stock states. Delivery demonstrates a
  navigation/dialog concept, not a working stock operation.
- **Ventes:** sample product selection, quantity controls, basket total and a clearly labelled
  simulated confirmation. Import/history actions explain their intended connection to existing Java
  workflows; they do not implement those workflows in the browser.

Temporary sample additions and basket changes disappear on reload. No browser storage is used.

## Selected direction

Patrick approved Atelier's warmth with Comptoir's clear typography on 12 November 2026. The Java
implementation retains real families/variants, photos, cash checkout and recovery, and adds a real
read-only local overview. These browser figures remain illustrative. See
[the Java dashboard contract](../../desktop/DASHBOARD.md) for actual reporting definitions.

## How the directions differ

| | A · Atelier | B · Comptoir |
| --- | --- | --- |
| Character | Warm, calm, editorial | Clear, structured, practical |
| Typography | Serif page headings/brand with plain operational text | Sans-serif headings and brand with stronger weight |
| Surfaces | Warm off-white, pale sage, softer rounding | Cooler neutral canvas, firmer green accents, tighter geometry |
| Navigation | Green gradient, light inset selection | Deeper forest sidebar, pale solid selected item |
| Catalogue | Family cards first; choose a variant inside | Variants directly in a scannable list |
| Best tradeoff | More visual breathing room and family identity | Faster comparison of references, quantities and prices |

Patrick can combine parts: for example Atelier's sidebar/warm canvas with Comptoir's direct variant
list. Select the direction, catalogue navigation and preferred heading typography before implementing
shared Java tokens. The prototypes deliberately keep essential operations labelled; beauty is not a
reason to hide quantities, exact references or payment/recovery actions.

## Preview images

- [A · Atelier dashboard](previews/atelier-home.png), [products](previews/atelier-products.png), [sales](previews/atelier-sales.png).
- [B · Comptoir dashboard](previews/precision-home.png), [products](previews/precision-products.png), [sales](previews/precision-sales.png).
- [Organized product entry with optional photo](previews/product-entry.png).

## Checks performed

Local Chromium automated interaction and rendering, with network disabled after loading:

- Both directions, all four routes, widths 640, 960, 1100 and 1600: no document horizontal overflow.
- Reference search, product details, Escape to close, sample product creation including literal `<`/`>`
  text, optional photo preview, basket increment and simulated payment.
- No uncaught JavaScript errors and no external HTTP requests.
- Screenshots of Dashboard, Products and Sales at 1440px wide; responsive checks include 960×600.

This is not a native Swing usability test or a full accessibility audit. Real font metrics, keyboard,
contrast, scaling, long labels and pending/error states must be verified again in Java after approval.

## Team handoff

Takou owns restore OFF-03b, backup/settings behavior OFF-03c and acceptance OFF-03d. Patrick and Codex
own the shared redesign OFF-07/OFF-07A and the later real offline dashboard OFF-08. Coordinate
ShellPanel, common style helpers, AppContext and shared bundles before concurrent edits.

Next: Patrick chooses A, B or a precise combination. Then migrate the shared navigation and typography
in a small Java PR, retain the working stock/sale/recovery code, and adapt Takou's Settings UI through
those shared components. The real dashboard needs documented, tested local aggregation queries;
never replace current app data with these demo figures.
