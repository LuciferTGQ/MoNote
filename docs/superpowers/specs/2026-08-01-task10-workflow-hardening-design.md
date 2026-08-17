# Task 10 Workflow Hardening Design

## Scope

This amendment closes seven rejected Task 10 workflows without adding Task 13 sharing. Changes remain local to trash recovery, SAF folder enumeration, picker metadata resolution, single-file export, library folder navigation, and scan-warning presentation.

## Restore intent state machine

Before moving trash content back into the library, `TrashRepository` writes and privately signs a `PENDING_RESTORE` sidecar containing the target path and a fingerprinted document snapshot. The physical move begins only after this durable intent exists.

A failed or cancelled move rewrites the sidecar to `TRASHED` and removes the private trust marker. If rollback persistence itself fails, the trusted pending intent remains recoverable at startup rather than disappearing.

Startup examines only structurally valid, privately trusted pending intents and never overwrites an existing target:

- Trash content present and target absent means the move never committed. Startup atomically restores the `TRASHED` sidecar and removes the trust marker.
- Trash content absent and target present means the move committed. Startup verifies target fingerprints, restores held directory metadata when applicable, recreates catalog rows with the journaled IDs, favorites, tags, and recent timestamps, then removes the journal only after catalog synchronization succeeds.
- Both paths present, neither path present, a mismatched target, an untrusted sidecar, or a changed target is left untouched for explicit recovery; startup never guesses ownership.

Test-only crash hooks throw outside normal `Exception` rollback handling immediately after intent persistence and immediately after the physical move. Rebuilding the repository simulates process death and proves both startup paths.

## Bounded SAF traversal

`TreeDocumentSource` exposes bounded incremental child delivery instead of returning an unbounded list. `FolderImportCoordinator` passes the global remaining entry budget plus one to each directory. Encountering that extra item fails immediately with `FolderImportLimitException`; providers therefore read no more than `maxEntries + 1` entries for a flat oversized directory.

`ContentResolverTreeDocumentSource` owns the cursor in a `use` block, calls `ensureActive()` before every cursor step and child delivery, and stops at the supplied bound. File streams retain their existing `use` ownership in the coordinator.

## Picker metadata and grants

Picker callbacks launch one IO coroutine that resolves display names and stores URI/name pairs. Import sheets render only cached names, so recomposition performs no provider queries. Folder imports use the activity result's temporary read grant only; the app does not request permanent URI permission because content is copied immediately.

## Single-file export

A small IO export helper validates that the selected source is an existing regular file, requires a non-null provider output stream, closes both streams, and maps provider or storage failures to a Chinese user-visible result. The launcher clears the pending source in `finally`. Success also produces visible feedback. Export remains copy-only and does not implement sharing.

## Folder navigation and scan warnings

`LibraryUiState` exposes `canNavigateUp` and a bounded, de-duplicated `scanWarnings` list. `LibraryViewModel.goToParent()` normalizes the current path, refuses to cross the library root, and returns whether navigation occurred. `LibraryScreen` shows an explicit up action only below root and consumes Android back only below root.

Indexer `ScanProgress.errors` are converted into at most five readable, distinct warnings in the state snapshot. The screen renders the current warning set rather than emitting repeated transient events for every progress update.

## Verification

Each behavior is introduced with a focused failing test before production changes. Final verification consists of related JVM tests, related Android Compose tests, the complete JVM suite once, related Android tests once, Android lint once, debug assemble once, and staged diff validation. No push or amend is performed.
