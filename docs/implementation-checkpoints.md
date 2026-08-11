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
| 6. Offline CodeMirror and Markdown renderer | `b8894a9` | Clean offline install of 235 locked packages; 70 Vitest tests passed; TypeScript and Vite production build verified; security and quality reviews approved |
| 7. Trusted Android document surface | `ba5bd32` | 72 Vitest tests, 106 JVM tests, and 2 API 30 WebView integration tests passed; 4 Windows-only permission cases skipped; lint and debug APK verified |
| 8. Document sessions, recovery, and conflicts | `f00f404` | 131 JVM tests passed; 5 Windows-only symbolic-link permission cases skipped; lint reported 0 errors; debug APK and targeted code review verified |
| 9. Application shell, permission onboarding, navigation, and brand | `c2077bc` | 131 JVM tests and 6 API 30 Compose tests passed; 5 Windows-only symbolic-link permission cases skipped; lint reported 0 errors; forbidden manifest permissions absent; debug APK and two-stage review verified |
| 10. Library, folder import, search, metadata, trash, and storage UI | `1398557` | 72 renderer tests, 173 JVM tests, 12 API 30 library Compose tests, and the trusted WebView round trip passed; 5 environment-specific JVM cases skipped; lint reported 0 errors; forbidden permissions absent; renderer-complete debug APK verified |
| 11. Receive Markdown from WeChat and other Android apps | `a75b6c6` | 14 API 30 incoming-Intent tests and the trusted WebView round trip passed; 173 JVM regressions passed with 5 environment skips; lint reported 0 errors; four precise external actions, non-exported debug provider, forbidden-permission absence, and renderer-complete APK verified (`ED7E1CB2…A392D`) |
| 12. Responsive editor, toolbar, orientation locks, and safe exit | `195575b` | 75 renderer tests and production build passed; 177 JVM tests passed with 5 environment skips; 3 API 30 responsive Compose tests and the final trusted WebView round trip passed; lint reported 0 errors; final renderer-complete APK installed and launched on API 30 (`15DDFEBE…7AD07AF`) |

| 12.1. Embedded editor visibility hotfix | `548cb14` | Reproduced the blank editor after opening an imported Markdown file on API 30; constrained the WebView to a clipped native host; 177 JVM tests passed with 5 environment skips, 6 focused API 30 editor tests passed, lint reported 0 errors, and manual edit/autosave/preview verification passed. APK SHA-256: `3B70CB66F4CA12E4B5F391970FF4A3BDAC4C08E730651734ADE587171F397008` |

Every completed implementation task is kept as an independent commit. A task is
pushed only after specification review, code-quality review, and fresh
verification succeed. In-progress task code is not a rollback checkpoint.

## Current work

- Task 13: image assets, export/share, recovery UI, and external-conflict UI - next
  implementation checkpoint.
