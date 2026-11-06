# Desktop team: start here

This folder is a handoff for Charles (Claude Code), Willy (Antigravity), and
Pascal (Antigravity). The same instructions work with either tool. Each person
uses their own checkout and feature branch in the shared repository.

## Give your AI its instructions

Open the repository root in your tool, then paste the matching prompt.

**Charles**

> Read docs/team/CONTEXT.md and docs/team/CHARLES.md, then follow my current assignment.
> Inspect the actual repository before editing. Explain the steps
> in simple language and implement only my assigned milestone. Show what you
> tested and the remaining limitations. Do not push or merge without my request.

**Willy**

> Read docs/team/CONTEXT.md and docs/team/WILLY.md, then follow my current assignment.
> Inspect the actual repository before editing. Explain the steps
> in simple language and implement only my assigned milestone. Show what you
> tested and the remaining limitations. Do not push or merge without my request.

**Pascal**

> Read docs/team/CONTEXT.md and docs/team/PASCAL.md, then follow my current assignment.
> Inspect the actual repository before editing. Explain the steps
> in simple language and implement only my assigned milestone. Show what you
> tested and the remaining limitations. Do not push or merge without my request.

The AI must actually read the files; mentioning their names alone supplies no
context. If it cannot access the checkout, paste CONTEXT.md and your role file
into the conversation. Never paste passwords or tokens.

## Get the project on your computer

Install Git and a JDK 21, such as Eclipse Temurin 21. Check `java -version` and
`javac -version`: both must report 21. These are prerequisites, not installations
performed by these Markdown files. SQLite and a global Maven install are not
required. Maven downloads dependencies on the first build.

For a first checkout:

```sh
git clone https://github.com/boscopatrick907-coder/sji-innova.git
cd sji-innova
```

If you already have a checkout, preserve unfinished work before switching
branches. With a clean working tree:

```sh
git switch main
git pull --ff-only origin main
```

If these handoff files are still on a documentation branch, wait for that pull
request to merge before following the role's feature-branch instructions. Do not
build a feature branch on top of unrelated unmerged documentation work.

Follow README.md for build/run commands. On Windows PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
java -jar desktop/target/innova-desktop-0.1.0-SNAPSHOT.jar
```

On Linux/macOS use `./mvnw` instead. The README's `/workspace/.setup` paths belong
only to the prepared cloud machine; do not copy them onto a friend's PC. Use
normal GitHub sign-in when needed; do not put credentials in code or chat.

## Current assignments — 6 November 2026

| Person | Owns | Current brief |
| --- | --- | --- |
| Patrick | Products design and implementation | Chosen family catalog is merged in PR #24 |
| Willy | Receipt restart recovery, then corrections and thresholds | [WILLY.md](WILLY.md) |
| Charles | Read-only sales history and sale details | [CHARLES.md](CHARLES.md) |
| Pascal | French/English resources and cross-screen QA | [PASCAL.md](PASCAL.md) |
| Emmanuel | CSV and photo backends delivered; coordinate new work with Patrick | [Ordered backend briefs](EMMANUEL.md) |

The current briefs above replace earlier assignments. Products, CSV/photo controls,
Stock receipt/history and cash checkout are merged (through PR #30). Do not repeat
those implementations. docs/design/ contains visual references; current Java code
is the actual implementation.

## Coordination and merge order

1. Willy delivers durable receipt recovery using his approved injection contract.
2. Charles delivers read-only sales history and details, preserving the basket.
3. Willy adds stock corrections and thresholds in later small PRs after recovery.
4. Patrick coordinates shared files, combined testing, backups and pilot preparation.
   Review each PR before merging and test the combined application afterward.

Historical review evidence remains under docs/reviews/. It describes older commits,
not current assignments. Do not follow old correction checklists as new work
without reproducing the issue on current main.

## Share progress

Report branch/commit, working behavior, tests, screenshots, blockers and next step.
Separate automated/headless checks from actual Windows GUI tests. Follow README.md
for Java 21 build/run commands and preserve user work before switching branches.
