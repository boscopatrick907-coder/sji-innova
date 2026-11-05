# Emmanuel — current assignment: CSV product import

Updated 5 November 2026.

## Delivered: product photo backend

Your original commit `5a3c942` is preserved on `backend/product-photos`. Follow-up
commits correct save/cleanup coordination, uncertain commit handling and corrupt
image detection, with regression tests. See
[the photo service guide](../backend/PRODUCT_PHOTOS.md). The service still needs
a separate desktop integration; do not build a second Products screen.

## Next: CSV import

Use [your CSV import brief](EMMANUEL_01_CSV_IMPORT.md). Begin with the format and
service interface, then preview and durable confirmed import. This task remains
outstanding; photo delivery does not complete CSV import.

After the photo fixes merge, preserve unfinished work and create a new
`backend/csv-product-import` branch from updated main. Do not base this task on
the old sale-void branch or repeat the photo implementation. Patrick owns the
CSV button/preview UI; coordinate the API with him before implementation.

The [photo assignment](EMMANUEL_02_PRODUCT_PHOTOS.md) is now historical context,
not your next task. Keep future photo corrections small and independently reviewed.

## Prompt for your AI

> Read docs/team/EMMANUEL.md, docs/team/EMMANUEL_01_CSV_IMPORT.md and CONTEXT.md.
> Photo storage has been delivered; my next task is CSV product import. Inspect
> current main after the photo fixes merge. Agree the template and service API
> with Patrick, then implement preview, validation and safe confirmed imports.
> Preserve families, automatic references, stock and photo associations. Explain
> each step simply. Supply tests and a calling guide; do not build desktop UI or
> push/merge without my request.
