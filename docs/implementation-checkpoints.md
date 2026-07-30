# MoNote implementation checkpoints

This file records verified development checkpoints for recovery, review, and
cross-device continuation.

- Canonical remote: `https://github.com/LuciferTGQ/MoNote.git`
- Integration branch: `codex/monote-mvp`
- Design spec: `docs/superpowers/specs/2026-07-26-monote-markdown-android-design.md`
  - SHA-256: `FF2E3219CA106A51C7D1E6F07C668759290CC07AB22A3C9C7445662A13AD200A`
- Implementation plan: `docs/superpowers/plans/2026-07-26-monote-markdown-android.md`
  - SHA-256: `345B70A769C08880ACE4994F0EB57DEDAF5ECAD9B22931AAD1684B1795CB577B`

## Verified checkpoints

| Task | Commit | Verification summary |
| --- | --- | --- |
| Product and architecture specification | `f11b75c` | 17-section design specification committed |
| Fifteen-task implementation plan | `c3b14c9` | Task-by-task TDD and review plan committed |
| Isolated worktree support | `831757f` | Worktree directory ignored |
| 1. Android build baseline | `9d26deb` | Reproducible JDK/SDK bootstrap, Gradle build, lint, and debug APK verified |
| 2. Safe public storage and atomic I/O | `832b7b2` | 26 JVM tests passed; 2 Windows symlink-permission tests skipped; lint and APK verified |
| 3. Transactional Markdown import | `8d8bb30` | 55 JVM tests passed; 2 Windows symlink-permission tests skipped; lint and APK verified |
| 4. Room catalog, FTS, and JSON recovery | `b88bd3c` | 69 JVM tests and 12 API 30 instrumentation tests passed; lint and both APKs verified |
| 5. Library operations, trash, and storage accounting | `c6f85d9` | 105 JVM tests and 12 API 30 Room regression tests passed; lint and both APKs verified |

Every completed implementation task is kept as an independent commit. A task is
pushed only after specification review, code-quality review, and fresh
verification succeed. In-progress task code is not a rollback checkpoint.

## Current work

- Task 6: offline CodeMirror and Markdown renderer — in progress, not yet
  committed at the time this checkpoint log was created.
