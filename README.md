# Cella (SJI Innova)

Offline-first inventory and sales software for Cameroonian shops. The current
implementation is a Java project foundation, not a working sales application.
See [the project master file](SJI_INNOVA_PROJECT.md) for current decisions
and [the detailed v0.3 plan](SJI_INNOVA_PROJECT_v03.md) for the revised scope
and event-sync design. The master file confirms the Cella name and Spring Boot;
the versioned plans retain earlier undecided entries.

## Requirements

Install a **JDK 21** (not only a JRE), and make `java` and `javac` available.
Set `JAVA_HOME` to its installation directory if needed. A global Maven install
is unnecessary: the committed wrapper downloads Maven **3.9.9** and verifies its
SHA-256. First builds require HTTPS access to `repo.maven.apache.org` for Maven
and dependencies. The build rejects Java versions outside 21.

In the prepared Codex cloud environment, activate the installed JDK per shell:

```sh
export JAVA_HOME=/workspace/.setup/jdk/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_USER_HOME=/workspace/.cache/maven
export MAVEN_OPTS=-Dmaven.repo.local=/workspace/.cache/maven/repository
export XDG_CACHE_HOME=/workspace/.cache
```

## Build and test

Run from the repository root on Linux/macOS:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
```

On Windows PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

This builds both modules and runs eleven SQLite integration tests plus one desktop
startup test. Storage tests use temporary databases. Sales and synchronization
are not implemented.

The prepared cloud environment also supplies proxy settings outside the checkout.
Use this build command there:

```sh
./mvnw -s /workspace/.setup/maven/settings.xml --batch-mode --no-transfer-progress clean verify
```

## Run

After building, on a machine with a graphical desktop:

```sh
java -jar desktop/target/innova-desktop-0.1.0-SNAPSHOT.jar
```

For a cloud machine without a display, validate the same startup path and FlatLaf
initialization without opening a window:

```sh
java "-Djava.awt.headless=true" -jar desktop/target/innova-desktop-0.1.0-SNAPSHOT.jar --smoke-test
```

## Structure and next work

- `core`: product validation, creation and shop-scoped listing; SQLite JDBC persistence.
- `desktop`: Swing + FlatLaf entry point; depends on `core`.
- `.github/workflows/build.yml`: Linux and Windows builds and packaged startup checks.

Next: stock movements and an atomic offline cash sale, including rollback tests,
before adding cloud sync. Spring Boot is the selected cloud framework; its module will be added later. SQLite is embedded; no separate database installation, credentials, or external
services are needed. Windows CI is configured; a real Windows GUI/installer test
remains a separate quality gate.

## Try local product storage (no screen yet)

After building, run from the repository root:

```sh
java -jar desktop/target/innova-desktop-0.1.0-SNAPSHOT.jar --product-demo data/demo.db
```

Run the same command again. The first run creates a sample product costing
500 FCFA; the second loads that same product with the same UUID, demonstrating
persistence across separate application runs. This is an explicit demo with a
fixed demo shop ID, not production shop registration. Use a disposable demo file.
The new sample also includes a buying price of 300 FCFA, model Classic V2,
category Clothing, unit piece, color blue and size M. Existing demo products are
preserved rather than filled with invented details.
The demo prints `Loaded existing products; no sample added` on later runs.

SQLite JDBC is included in the packaged JAR, including native libraries for the
supported platforms. No separate SQLite program is required. The file's parent
directories are created automatically. Each connection is closed after use.
Schema version 2 adds product details and a specifications table. Version 1
databases upgrade automatically inside a transaction: existing IDs, names,
selling prices, timestamps and catalog versions are preserved. Unknown buying
prices remain NULL, specifications start empty, and the default unit is piece.
Repeated initialization preserves data, and newer schemas are rejected. WAL mode supports
local recovery; it does not replace backups. Do not copy a live `.db` file alone
for backup: SQLite may also have active `-wal` and `-shm` files.

Products store UUID, shop UUID, name, selling price (`unit_price`), optional buying
price (`cost_price`), optional model/reference and category, unit (`piece`, `kg`,
`litre`), timestamps and catalog version. Prices are whole FCFA; unknown buying
price differs from zero. Buying price may exceed selling price (e.g. clearance).
Specifications are flexible name/value pairs, such as color=blue and size=M,
stored in `product_specifications`. Each sellable variant is a separate product
ID; grouping variants under a shared parent is not implemented. Product and
specification inserts succeed or roll back together.

The demo is a local developer tool and prints buying prices. Those prices must
not be included in future customer-facing API/WhatsApp responses.
Shop registration/authentication, stock, tax fields, edits, UI forms and cloud
sync come later through additional migrations.
Shop-scoped queries are data filtering, not an authorization system. The service
validates input and the JDBC repository uses parameterized SQL for values.
Database files are ignored by Git; never commit a shop's live data.

## Desktop team handoff

Charles, Willy and Pascal: start with [the team guide](docs/team/README.md).
It contains AI prompts, local setup instructions, shared boundaries and individual
first tasks for Claude Code or Antigravity. These are implementation briefs;
the proposed desktop shell and Products screen are not built yet.
