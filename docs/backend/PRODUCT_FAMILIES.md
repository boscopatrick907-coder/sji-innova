# Product families, variants and references — schema 6

A family (Riz) organizes sellable Products (Basmati, sac de 25 kg). The Product
UUID remains the stock/sales identity. Family has no price, unit or stock balance.
Category is flexible descriptive text, not a family or mandatory classification.
Photos and CSV import remain separate assignments; this change implements neither.

## Services for desktop developers

Build these once from the same initialized database, off Swing's EDT:

```java
var products = new ProductService(new SqliteProductRepository(database));
var families = new ProductFamilyService(new SqliteProductFamilyRepository(database), products);
var rice = families.create(shopId, "Riz", "Alimentation");
var bag = families.createVariant(shopId, rice.id(), "Basmati — Sac de 25 kg",
        28_000, "SUP-25", null, null, "bag",
        Map.of("Type", "Basmati", "Conditionnement", "Sac", "Poids", "25 kg"));
var variants = families.variants(shopId, rice.id());
var matches = families.search(shopId, "riz basmati 25");
var categories = families.categories(shopId);
var suggestions = families.specificationSuggestions(shopId, rice.id());
```

Imports are `cm.sji.innova.core.product.*`, `cm.sji.innova.core.persistence.*`
and `java.util.Map`. `families.list(shopId)` gives families; variant counts can
be derived from a single `products.list(shopId)` grouped by familyId. Do not sum
unlike variants/units into a family balance. List all catalog data on a worker,
not by making many blocking queries while rendering cells.

`families.ungrouped(shopId)` returns existing standalone products.
`families.assign(shopId, productId, familyId)` groups/reparents them without changing
IDs, internal reference, prices, stock or past sale snapshots. It increments the
catalog version/timestamp only for an actual grouping change. Repeating the same
assignment is a no-op. Wrong-shop/missing family/product is rejected; the database
also enforces same-shop family links. Ungrouping/edit/delete APIs are not added.

Existing ProductService.create overloads continue to create standalone products;
no desktop constructor changes are required. Existing Product constructors remain
source-compatible. Persisted Product adds `familyId()` (nullable) and `reference()`.
ProductRepository.insertAndReturn returns the assigned reference from the same
write operation; non-SQL test repositories can retain their original insert method.

Family creation and variant creation are separate commits: an empty family is
valid. If the second call fails, keep the returned family ID and retry/add within
that family rather than creating another family. For a single form, reflect the
actual saved outcome. Product creation itself is not an idempotent request API;
don't retry uncertain creates blindly. Disable repeat submits and reconcile
uncertain results, as with the existing catalog. CSV import must implement its
own durable row operation mapping, as its assignment specifies.

## Finding items without a barcode

Every SQLite-created product has an automatic shop-unique reference (`P-0001`,
`P-0002`, ...). This is distinct from modelReference, which remains optional supplier
or model text and need not be unique. Counters are durable, serialized with inserts,
and roll back on failed inserts; reopening does not reset them. Committed references
are not reused by the service. They are local shop catalog references, not a globally
unique or multi-device offline numbering scheme. Future sync needs a separate
collision policy; UUID remains the globally unique product key.

Search covers family/category, variant name, internal reference, model/reference,
and specification names/values. All space-separated tokens must match somewhere;
case and accents are ignored. Results are actual Product variants, so a search
can open an exact variant without first browsing its family. Search is an in-memory
catalog scan suitable for the current pilot; pagination/indexed search is future work.

Category suggestions combine existing family/product values, ignoring case/accents
when suggesting; they do not restrict new categories or silently merge stored names.
Specification suggestions are family-scoped name/value lists; custom pairs remain
allowed. There is no automatic duplicate rejection: similar variants can be valid.
The UI can use search results for a review warning, never merge on name alone.

## Whole bags versus loose rice

Canonical units: `piece`, `bag`, `kg`, `litre`. Translate `bag` as “sac” in French.
One bag = 1000 internal quantity units, just like one piece. Bag/piece receipts,
adjustments, sales and minimum thresholds must be whole items. kg/litre permit
thousandths. A bag's 25 kg content is a characteristic, NOT the stock quantity:
4 bags = 4000 internal units, not 100000. Bag-to-loose conversion is not implemented.
The existing StockService and SaleService handle bags with the same atomic
sale/void and retry rules. Historical sale units/names remain saved snapshots.

## Database upgrade and team coordination

Schema 6 adds product_families, product reference sequences, product family links
and references, and extends the allowed units. The products table is rebuilt inside
the startup migration transaction following SQLite's parent-table rebuild procedure:
FK enforcement disabled on that connection outside the transaction; parent keys,
indexes/triggers restored; foreign_key_check before commit; enforcement re-enabled
before returning. Normal connections always enable foreign keys. Failure rolls
back the entire migration; the temporary connection then closes.

Existing products remain ungrouped: no family names or variants are guessed.
They receive stable references in created_at/UUID order per shop. Their original
IDs, version/timestamps, specifications, prices, stock/minimums, sales, audit logs
and history are preserved. Initialization is repeatable and newer schemas are
rejected. Back up the complete shop data before upgrading a real installation.
An old app cannot reopen schema 6; do not run different app versions on one folder.

Patrick: build family/variant navigation and keep standalone products discoverable.
Willy: existing stock Product IDs remain valid; translate bag and use whole items.
Charles: existing sales requests remain valid; include bag in quantity formatting.
Pascal: coordinate the new bag label and family/reference UI translations.
Emmanuel: CSV should continue using ProductService (automatic references); add
family mapping only in an explicit future import format. Photo associations must
use existing Product IDs; family photo fallback needs its own service contract.

Validation includes schema-5 migration with sale/void/audit preservation, schema-1/2
upgrades, migration rollback/retry, concurrent references, shop filtering and
bag sales/voids. Desktop screens are unchanged; no new family UI is claimed.
