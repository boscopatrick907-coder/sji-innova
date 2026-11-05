# Family catalog desktop — chosen design implementation

Java Swing/FlatLaf now implements the chosen catalog direction. Shell opens
CatalogPanel through ProductsSlot, with the existing ProductService/StockService
and a ProductFamilyService on the initialized data file. AppContext's public
contract remains unchanged, so Charles's shared service wiring is preserved.

Navigation: families -> variants -> detail, with breadcrumbs. Existing ungrouped
products stay accessible. Search matches all tokens, ignoring case/accents, across
family, name, internal/supplier reference, category and custom attributes. Category
filtering and the search query survive detail/back navigation. Select a table row
and use Open/Enter or double-click. Refresh reloads catalog and stock in a worker.

The creation form keeps only name, selling price and unit required; a family is
optional and can be selected or entered. Category, buying price, supplier/model
reference and arbitrary information pairs are under Additional information.
Inputs remain after failed saves; repeat submits and navigation during save are
disabled. If a new family was saved before variant insertion fails, the form
retains its ID for retry rather than creating another family. As documented for
the catalog backend, uncertain post-commit create failures are not an idempotent
import API: reconcile the catalog before repeating a request with unknown outcome.

The details screen shows actual shop stock (including bags), a placeholder image,
characteristics and optional buying price. Stock query errors are distinguishable
from a real zero. Movement history comes from StockService, loaded off the EDT.
The sidebar contains only implemented navigation. No fake login, barcode, tax,
photo, sync or sales-screen functionality is presented.

Photos/CSV remain planned backend integration work. Existing ProductPanel is
retained for its regression coverage/standalone callers, but the shell now uses
the catalog form. Core/schema/services are unchanged by this desktop PR.

Validation: full Java 21 Maven clean verify, packaged headless startup, real SQLite
save/reopen through the shell, family search/back navigation, stock-error recovery
and failed-variant retry without duplicate family. Temporary shop only. Headless
Swing renders at 1280x800 and 960x600 are provided for visual review; they are not
a substitute for an actual Windows GUI check of fonts, scrolling and keyboard flow.
