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

This builds both modules and runs one desktop startup smoke test. It does not
validate sales, persistence, synchronization, or other unimplemented features.

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

- `core`: shared business services and JDBC persistence; currently a placeholder.
- `desktop`: Swing + FlatLaf entry point; depends on `core`.
- `.github/workflows/build.yml`: Linux and Windows builds and packaged startup checks.

Next: implement versioned SQLite migrations and an atomic offline cash sale,
including rollback tests, before adding cloud sync. Spring Boot is the selected cloud framework; its module will be added later. No database, credentials, or external services are needed for
this foundation. Windows CI is configured; a real Windows GUI/installer test
remains a separate quality gate.
