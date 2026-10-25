# Desktop team: start here

This folder is a handoff for Charles (Claude Code), Willy (Antigravity), and
Pascal (Antigravity). The same instructions work with either tool. Each person
uses their own checkout and feature branch in the shared repository.

## Give your AI its instructions

Open the repository root in your tool, then paste the matching prompt.

**Charles**

> Read docs/team/CONTEXT.md and docs/team/CHARLES.md, then follow them for my
> first task. Inspect the actual repository before editing. Explain the steps
> in simple language and implement only my assigned milestone. Show what you
> tested and the remaining limitations. Do not push or merge without my request.

**Willy**

> Read docs/team/CONTEXT.md and docs/team/WILLY.md, then follow them for my
> first task. Inspect the actual repository before editing. Explain the steps
> in simple language and implement only my assigned milestone. Show what you
> tested and the remaining limitations. Do not push or merge without my request.

**Pascal**

> Read docs/team/CONTEXT.md and docs/team/PASCAL.md, then follow them for my
> first task. Inspect the actual repository before editing. Explain the steps
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

## First milestone and merge order

Goal: open Cella, navigate to Products, save product details, restart, and see
those same details again.

1. Pascal supplies the agreed French/English translation files.
2. Charles supplies the shell, persistent application context and Products slot.
3. Willy integrates the Products panel using those shared contracts.
4. All three test the combined application on Windows; another person reviews
   each pull request. Patrick coordinates review/integration; Emmanuel reviews
   service usage and persistence boundaries.

They can develop in parallel: Willy develops his panel against injected services
and a ResourceBundle; Pascal works in resources/tests; Charles owns navigation.
The integration order avoids replacing each other's entry-point changes.

## Share progress

At the end of each session report: task, branch/commit, what works, tests run,
what is blocked, and the next small step. Include a screenshot of visible UI
changes when possible. A passing cloud startup check is not a Windows GUI test.
