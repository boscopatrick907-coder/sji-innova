# Willy — primary desktop feature developer (Antigravity)

Read CONTEXT.md first. You own the Products form and list. Your workload is the
main desktop feature work; Pascal supports translations and QA.

## Start

With a clean checkout on up-to-date main:

```sh
git switch -c desktop/products-screen
```

Read Product, ProductService, ProductDetailsTest and existing desktop code.
Implement ProductPanel in the products package using the shared injected-service
constructor. Do not edit Charles's shell/entry-point classes. Ask Charles to wire
in the panel after it is ready. Coordinate labels with Pascal.

## Deliver one small pull request

1. Show saved products for the injected shop UUID using ProductService.list.
2. Add labelled inputs for name, model/reference, buying price, selling price,
   category and unit, plus add/remove rows for specifications such as color/size.
3. Required: name, selling price and unit. Model/category/specifications are
   optional. Blank buying price means unknown; accept zero and do not require
   buying price to be lower than selling price. Use integer FCFA with overflow
   checks. Unit values must be piece/kg/litre.
4. Validate before saving, display core validation failures clearly, and preserve
   user input when a save fails. Reject duplicate trimmed specification names.
5. Save with the real detailed ProductService.create overload on a SwingWorker.
   Prevent duplicate clicks while running. Refresh the list on success; clear
   inputs only after successful persistence. Keep UI changes on the EDT.
6. Include empty/loading/error states and a retry/refresh action. Prices must
   visibly distinguish buying from selling and unknown from zero.
7. Use Pascal's bundles for all user-visible text and messages. Translate unit
   labels but pass canonical codes to core. No SQL or migration edits.

## Acceptance

- Create a T-shirt: model Classic V2, buying 2000, selling 3000 FCFA, category
  Clothing, piece, color=blue, size=M. Save and see every entered detail.
- Restart after integration: the same product remains, without duplicate seeds.
- Red and blue entries get separate IDs; no parent-variant grouping is claimed.
- Blank buying price remains unknown. Negative, noninteger and overflow prices
  are rejected; failed saves retain input. Slow loading does not freeze the window.
- Add useful UI/service integration tests with temporary databases. Report what
  was tested headlessly and what was verified in the Windows window.

Do not add stock quantity editing: stock movements are a separate backend task.
No product edit/delete, cart or checkout in this first PR. Your next assignment
will be Sales UI after Emmanuel supplies the stock and sale service contracts.
