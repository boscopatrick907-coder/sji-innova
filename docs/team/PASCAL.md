# Pascal — translations and desktop QA (Antigravity)

Read CONTEXT.md first. Start with a smaller, self-contained deliverable:
French/English text bundles, followed by hands-on testing of the integrated UI.
Do not build a second Products screen or change database code.

## Start

With a clean checkout on up-to-date main:

```sh
git switch -c desktop/translations
```

## First pull request: language resources

Create the two resource files at the paths in CONTEXT.md. Agree on keys with
Charles and Willy before they code. Initial keys:

| Key | English | French |
|---|---|---|
| app.title | Cella | Cella |
| nav.products | Products | Produits |
| products.title | Products | Produits |
| product.name | Name | Nom |
| product.modelReference | Model / reference | Modèle / référence |
| product.buyingPrice | Buying price (FCFA) | Prix d’achat (FCFA) |
| product.sellingPrice | Selling price (FCFA) | Prix de vente (FCFA) |
| product.category | Category | Catégorie |
| product.unit | Unit | Unité |
| product.specifications | Specifications | Caractéristiques |
| specification.name | Characteristic | Caractéristique |
| specification.value | Value | Valeur |
| action.save | Save | Enregistrer |
| action.refresh | Refresh | Actualiser |
| action.retry | Retry | Réessayer |
| action.addSpecification | Add characteristic | Ajouter une caractéristique |
| action.removeSpecification | Remove characteristic | Retirer la caractéristique |
| state.loading | Loading… | Chargement… |
| state.saving | Saving… | Enregistrement… |
| state.emptyProducts | No products yet | Aucun produit pour le moment |
| state.saved | Product saved | Produit enregistré |
| price.unknown | Unknown | Inconnu |
| unit.piece | Piece | Pièce |
| unit.kg | Kilogram | Kilogramme |
| unit.litre | Litre | Litre |
| error.requiredName | Enter a product name | Saisissez un nom de produit |
| error.invalidPrice | Enter a nonnegative whole FCFA amount | Saisissez un montant entier positif ou nul en FCFA |
| error.invalidSpecifications | Enter unique, nonblank names and values | Saisissez des noms uniques et des valeurs non vides |
| error.loadProducts | Products could not be loaded | Impossible de charger les produits |
| error.saveProduct | Product could not be saved | Impossible d’enregistrer le produit |
| error.startup | Cella could not open its local data | Cella n’a pas pu ouvrir ses données locales |

Use UTF-8 Java resource bundles. Avoid runtime message-format placeholders unless
agreed with the screen owners. Add a test that loads both bundles, compares their
key sets and rejects blank values. Verify accented French labels render correctly
when the actual UI is available. No entry-point changes are needed for this PR.

## Second small task: record actual GUI checks

After Charles and Willy integrate, use a disposable data directory and record
results in a new docs/qa/desktop-pilot.md file: commit, OS/JDK, steps, expected
result, observed result, pass/fail/unrun. Do not pre-fill checks as passed.

Check French and English; keyboard navigation; long names and accented text;
empty product list; buying price blank versus zero; invalid/overflow prices;
duplicate specification names; double-click Save; and persistence after restart.
For failure behavior, coordinate a safe simulated failure with the developers;
never corrupt or delete someone's working database to create an error.

Report screenshots and reproducible bugs to Patrick/Charles. Begin code fixes in
a new small branch only when assigned. Later tasks can include customer forms or
receipt layout once their contracts exist; do not start those in this first PR.
