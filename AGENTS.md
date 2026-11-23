# Morgana ORPG Guidelines & Standards

## Project Mission
Morgana ORPG — A cross-platform 2D game engine built on Kotlin and LibGDX (desktop, Android and browser from one client core). It is geared toward online RPGs in the spirit of Mirage Source and Player Worlds, and also supports other genres. The engine is genre-neutral and deterministic; a game is data (`game.json` plus JSON content) and sandboxed Lua scripts.

## Architectural Mandates
1. **Explicit Domain Invariants**: Core state transitions and business rules must be enforced in pure, deterministic domain modules rather than scattered across UI or transport layers.
2. **Strict Module Decoupling**: Keep core simulation/domain logic decoupled from I/O, persistence, network transport, and visualization layers.
3. **Deterministic Execution**: State updates, simulation ticks, and calculations must produce deterministic, reproducible results. Prefer seeded RNG when randomness is required.
4. **Phase-Driven Test Coverage**: Every roadmap phase and bug fix must be backed by unit and integration tests before moving to the next phase.
5. **Maintain Documentation Integrity**: Keep `docs/DESIGN.md` and `docs/ARCHITECTURE.md` synchronized as phases are completed. Preserve existing comments and docstrings unrelated to active edits.
6. **Data First, GUI Optional**: World content must be authorable as schema-versioned JSON files *and* via the `F1` GUI editors through the same validation pipeline. Never add content that only a GUI can create. See `docs/DESIGN.md` (Content Pipeline).
7. **Keep the Wiki Truthful**: `wiki/` is the project's long-term knowledge base (OKF bundle). Read it before non-trivial work; when a change alters behavior, structure, commands, formats, or decisions, update the affected pages, `wiki/log.md`, and run `wikiLint` (part of `check`). Record discovered bugs and doc/code mismatches under `wiki/issues/`. See `docs/WIKI_GUIDE.md`.
8. **Always Commit and Push**: the owner's standing instruction is that finished work is never left only in the working tree. After each completed, verified unit of work, commit it and push it to `origin` on the current branch (normally `main`) without waiting to be asked again. See *Git Workflow* below.
9. **No Overwriting Generators**: a game's `data/`, `assets/` and `game.json` are the source of truth and are edited directly (or through the `F1` editors). Never add or run a script that deletes and regenerates them; that drifts from hand edits and destroys work. A tool may create new content or write only files it owns, and must say so. The `build.py` generators of Trevana Realms and White River were deleted for this reason.
10. **Systems Reach the Kernel Only Through Seams**: the engine is Core, Systems, Templates and Plugins (ADR 0018). An optional gameplay feature is a `GameSystem` object in `core-domain/.../systems/` that implements the kernel seams it needs and is installed by `WorldSimulation`; nothing outside `systems/` imports it except `WorldSimulation`, and every `OptionalModule` is an installed system or on the pinned kernel-checked list (both tested). A genre is a tag on systems, never a layer. See `wiki/systems/system_seams.md` and the New-System Checklist in `wiki/server/optional_modules.md`.
11. **Game-Scoped Changes Are Logged With the Game**: `wiki/log.md` is for engine, tooling and docs changes only. Changes confined to one game (its `data/`, `assets/`, maps, quests, balance) are recorded in `games/<id>/CHANGELOG.md`. Private games such as `games/trevana-realms/` are gitignored, so a game-only change produces nothing to commit or push; do not make an engine commit or log entry just to record it.

## Git Workflow
- **When**: after each completed unit of work (a feature slice, a bug fix, a doc change), once `.\gradlew.bat check` passes. Do not batch several unrelated pieces into one commit, and do not leave verified work uncommitted at the end of a session.
- **What**: stage the files that belong to the work (`git add -A` is fine after checking `git status`, so nothing stray or generated is included). Never commit secrets, local settings, or build output; if `git status` shows something unexpected, find out why first.
- **Message**: a short imperative subject, then a body saying what changed and why. End it with the attribution trailers given for the session.
- **Push**: `git push origin <current branch>` straight after committing. Never force-push, rewrite published history, or skip hooks; if a mistake was already pushed, fix it with a new commit.
- **If it fails**: when `check` fails, fix the cause (or say clearly what is failing and why) instead of committing around it; when a push is rejected, pull/rebase onto the remote first and re-run the gate if anything merged.
- **Tests never write into `games/` or `server-data/`**: tests that exercise live edits or saves must work on a copy in a temp directory (a test once saved into a real game's content); build a throwaway game with `GameScaffolder` instead of copying one from `games/`.

## Build, Verification & Quality Commands
- **Full Verification Gate**: `.\gradlew.bat check`
- **Run Unit Tests**: `.\gradlew.bat test`
- **Validate Content Files**: `.\gradlew.bat :server-app:validateContent` (schemas: `:server-app:exportContentSchemas`; starter data: `:server-app:seedWorld`)
- **Game Wiki** (browsable, cross-linked wiki and balance report built from a game's data; also served at `/wiki/` on the web port): `.\gradlew.bat :server-app:gameWiki "-PcontentArgs=--game games/roguelike"` -> `build/game-wiki/<id>/`
- **Wiki**: `.\gradlew.bat :dev-tools:wikiImpact -PwikiArgs="--git HEAD~1"` (pages affected), `:dev-tools:wikiIndex` (regenerate index), `:dev-tools:wikiLint` (lint; also in `check`)
- **Browser Smoke Test** (needs Node and Chrome; screenshots in `build/smoke-test/`): `.\gradlew.bat :client-web:smokeTest`
- **Run Dedicated Server**: `.\gradlew.bat :server-app:run`
- **Run Desktop LibGDX Client**: `.\gradlew.bat :client-desktop:run`
- **Build Android Client APK**: `.\gradlew.bat :client-android:assembleDebug`
- **Build All Distributables** (SDK zip + Android APK -> `build/distributables/`): `.\gradlew.bat distributables`


## Environment & CLI Tooling on PATH
The development environment may include standard Unix, AST, and process utilities on `PATH` (e.g. `~/.local/bin`):
- **Code & AST Search**: `rg` (ripgrep), `fd`, `ast-grep` (`sg`), `jq`, `tree`
- **Process & Port Management**: `pkill`, `pgrep`, `killall`, `lsof` (e.g., `lsof -i :3000`, `lsof -ti :3000`), `fuser` (e.g., `fuser -k 3000/tcp`)
- **POSIX Text & File Utilities**: `grep`, `sed`, `awk`, `head`, `tail`, `wc`, `xargs`, `touch`, `which`, `patch`, `cut`, `tr`, `uniq`, `tee`, `realpath`, `sh`, `bash`
