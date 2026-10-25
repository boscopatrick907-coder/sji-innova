# Charles — desktop coordinator (Claude Code)

Read CONTEXT.md first. Your first task is the application shell and stable local
application context, not the Products form or business rules.

## Start

With a clean checkout on up-to-date main:

```sh
git switch -c desktop/app-shell
```

Inspect DesktopApplication, ProductStorageDemo, desktop/pom.xml, the startup test,
and core service constructors. Pascal owns bundles; Willy owns ProductPanel.
Coordinate contracts in CONTEXT.md before implementation.

## Deliver one small pull request

1. Create one main Cella window with a clear Products navigation entry and a main
   content area. Leave future modules out, or label placeholders honestly.
2. Prepare stable local app data: document the default directory and a supported
   override for testing; persist one development shop UUID and reuse it on restart.
   This is temporary development identity, not login or shop registration.
3. Initialize SQLite and construct ProductService off the EDT. Show a loading
   state; on failure offer a clear retry/exit action. Never delete or silently
   replace a failed database or identity file.
4. Load Pascal's French/English bundle, using a documented startup locale. Preserve
   --smoke-test and --product-demo PATH and avoid real user data in smoke tests.
5. Provide the Products slot. Integrate ProductPanel(ProductService, UUID,
   ResourceBundle) after Willy's work merges. Before it exists, use a compiling
   placeholder; do not import nonexistent classes or build a duplicate product form.
6. Keep the headless startup check useful after restructuring. Test stable context
   across restart with temporary files. Coordinate shared bootstrap changes only here.

## Acceptance

- Launch on Windows opens one Cella window with working navigation.
- Reopening with the same data directory reuses database path and shop UUID.
- Startup DB failures show an error rather than a frozen window or silent empty DB.
- Normal launch never inserts demo products. Headless checks never touch live data.
- Build/tests pass; GUI checks and any untested parts are reported honestly.

Future task after the pilot: integrate Sales and Customers panels when their
contracts exist. Do not implement authentication/backend services in this task.
