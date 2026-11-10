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

## CSV and photo controls

Products now includes Import CSV. Choose a UTF-8 CSV (comma/semicolon/automatic),
review row diagnostics and select valid rows. Invalid rows cannot be selected;
duplicate candidates are unselected by default and require explicit consent to
create distinct products. Save template exports the packaged CSV format. Imports
create ungrouped sellable products; family mapping and direct Excel .xlsx are not
included. Results remain on screen before returning to the refreshed catalog.

Before submission, pending-csv.properties stores the immutable request and reviewed
snapshot using atomic replacement. The same operation is restored on the next
Import CSV opening after restart; it never runs automatically. Partial/uncertain
results keep the request frozen for safe retry. A successful confirmation clears
pending state. A damaged/wrong-shop file is preserved and import is blocked until
the user chooses Set the file aside: it is renamed pending-csv-set-aside-<id>.properties
in the same folder, never deleted, and a readable request is never set aside. Another
window with an import open is reported as such, not as a damaged file.
A local .csv-import.lock prevents two import flows in the same folder.
Do not remove an active lock file. The store is local-filesystem only; it is not
multi-device synchronization. Input is limited to 10 MiB and persisted snapshots
to 16 MiB; oversized snapshots block submission rather than run untracked.

Choose/replace/remove photo controls live on each saved product's detail page.
The photo backend validates and copies selected JPEG/PNG files into managed storage.
Original source files can then be moved/deleted. Failed replacement leaves the
previous managed image; missing/corrupt images show a placeholder with recovery
text. Images retain their aspect ratio. Add the photo after saving the product:
the creation form does not yet contain a combined product/photo transaction.
Family-level photo fallback is not implemented.

All parsing, import, photo decoding/resizing and database operations are background
work. English/French controls are provided; row diagnostic messages currently come
from the backend in English. Pending import and photo files belong in full data
folder backups. UI tests cover preview selection, lost-response restart recovery,
corrupt/conflicting pending state and photo persistence/replacement/removal.
