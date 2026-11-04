# Desktop application shell (Charles)

The shell is the Cella main window, its navigation, and the local start-up
(data folder, development shop ID, database). Code: `cm.sji.innova.desktop.shell`.

## Data folder

| System | Default folder |
|---|---|
| Windows | `%APPDATA%\Cella` (for example `C:\Users\you\AppData\Roaming\Cella`) |
| Other | `~/.cella` |

Files inside: `cella.db` (SQLite database), `shop-id.txt` (the development shop ID)
and `logs/cella-0.log` (error log, see below).

**Override (for tests and developers)**, in this order of priority:

1. `--data-dir PATH` on the command line
2. environment variable `CELLA_DATA_DIR`
3. the default folder above

Example: `java -jar desktop/target/innova-desktop-0.1.0-SNAPSHOT.jar --data-dir C:\temp\cella-test --lang fr`

## Development shop identity

`shop-id.txt` holds one UUID. It is created at the first launch and reused at every
launch. It is a **temporary development identity**, not a login and not a shop
registration. A damaged file stops start-up with a clear error and is never replaced.
If `cella.db` exists but `shop-id.txt` is missing, start-up also stops with an error and
no new ID is created, because a new ID would hide the shop's existing products. To recover,
put the old `shop-id.txt` back in the data folder and click **Retry**.

## Start-up behaviour

1. The window opens on a loading page.
2. In a background thread (not the Swing event thread): create the folder, read or create
   the shop ID, run `SqliteDatabase.initialize()`, build `ProductService`.
3. Success: main page (navigation on the left, Products on the right).
4. Failure: error page with the data folder, the message, **Retry** and **Exit**.
   Nothing is deleted or replaced.

Normal launch never adds demo products. `--smoke-test` runs the same start-up on a
throw-away temporary folder. `--product-demo PATH` is unchanged.

## Error log

Start-up failures are logged with `java.util.logging` (no `printStackTrace`). Normal launch
writes them to the console and to `logs/cella-0.log` in the data folder (3 rotating files of
1 MB). A user who opened Cella with a double-click has no console, so ask for this file when
they report "Cella could not start". If the log file cannot be opened, Cella logs to the
console only and keeps running. `--smoke-test` and the unit tests write no log file in the
real data folder.

## Language

`--lang fr|en` or `CELLA_LANG`. Default: English (`StartupOptions.DEFAULT_LOCALE`).
Texts come from Pascal's bundle `cm.sji.innova.desktop.i18n.messages`. Until a key exists
there, `FallbackMessages` (English) is used, so the window never shows a missing key.

**Keys the shell uses (Pascal: please add them to `messages_en` and `messages_fr`):**
`app.title`, `shell.nav.accessibleName`, `shell.nav.products`, `shell.loading`,
`shell.error.title`, `shell.error.dataFolder` (`{0}` = folder), `shell.error.retry`,
`shell.error.exit`, `shell.devShop` (`{0}` = shop ID).
The Products panel uses the product/action/unit keys listed in docs/team/PASCAL.md.

## Products slot (for Willy)

`ProductsSlot.create(AppContext, Messages)` now returns the real
`ProductPanel(context.products(), context.shopId(), messages.bundle())`.
Normal launch displays the product form and saved-products list using the stable
shop context. No demo data is inserted. Saving uses the existing product service.

The panel exposes initialLoadCompletion(), and the shell exposes productsReady().
Obtain these stages on the EDT and await them off the EDT when checking startup.
The headless smoke test waits for the first product query to finish before
removing its disposable database; load failure fails the check.

A headless integration test fills the form through the shell, saves full product
details to temporary SQLite, reopens the same context, and verifies the same
product ID/details and displayed list. Actual OS-window usability still needs
human checking; a rendered preview is not a Windows GUI test.

## Limitations

- No login, no shop registration, no live language switching.
- Only one navigation entry (Products). Sales and Customers come later.
- The loading, error and main pages were checked as images without a screen; the real
  window, keyboard navigation and restart behaviour still need a manual check on Windows.