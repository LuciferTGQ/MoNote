# Task 10 Metadata and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve directory metadata across every Task 10 library lifecycle and persist executable recovery state when a batch-move rollback fails.

**Architecture:** Extend the existing atomic directory metadata sidecar with active and stable-trash namespaces, and coordinate it from `LibraryService` and `TrashRepository`. Add a focused atomic `MoveRecoveryRepository`, expose its durable records in `LibraryUiState`, and execute recovery directly from `LibraryScreen`.

**Tech Stack:** Kotlin, coroutines and StateFlow, kotlinx.serialization JSON, `AtomicTextStore`, JUnit 4, Android Compose UI tests.

---

### Task 1: Atomic directory path migration

**Files:**
- Modify: `app/src/main/java/app/monote/mobile/feature/library/DirectoryMetadataRepository.kt`
- Modify: `app/src/test/java/app/monote/mobile/feature/library/DirectoryMetadataRepositoryTest.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/LibraryService.kt`
- Modify: `app/src/test/java/app/monote/mobile/feature/library/LibraryServiceTest.kt`

- [ ] Add failing tests showing `movePaths(mapOf("course" to "archive/course"))` migrates the exact directory and descendants but not `coursework`, and showing rename plus batch move retain favorite/tags.
- [ ] Run the focused tests and confirm missing migration APIs/integration are the failure reason.
- [ ] Implement `suspend fun movePaths(paths: Map<String, String>)` as one validated snapshot transform and one `AtomicTextStore.replace`.
- [ ] Inject `DirectoryMetadataRepository` into `LibraryService`; migrate a directory after single or all batch filesystem moves succeed, before returning success.
- [ ] Re-run the focused tests and confirm green.

### Task 2: Trash metadata lifecycle

**Files:**
- Modify: `app/src/main/java/app/monote/mobile/feature/library/DirectoryMetadataRepository.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/TrashRepository.kt`
- Modify: `app/src/test/java/app/monote/mobile/feature/library/TrashRepositoryTest.kt`

- [ ] Add failing tests showing directory favorite/tags survive trash and restore, and permanent deletion followed by same-path recreation has no metadata.
- [ ] Run the focused tests and confirm the lifecycle is absent.
- [ ] Add `moveToTrash`, `restoreFromTrash`, and `deleteTrashed` repository operations, each persisted atomically before publishing state.
- [ ] Coordinate those operations with trash content moves, restore rollback, permanent delete, expired purge, and empty-trash.
- [ ] Re-run the focused tests and confirm green.

### Task 3: Durable move rollback recovery

**Files:**
- Create: `app/src/main/java/app/monote/mobile/feature/library/MoveRecoveryRepository.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/LibraryModels.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/LibraryService.kt`
- Create: `app/src/test/java/app/monote/mobile/feature/library/MoveRecoveryRepositoryTest.kt`
- Modify: `app/src/test/java/app/monote/mobile/feature/library/LibraryServiceTest.kt`

- [ ] Add failing tests for atomic log persistence/reload, non-overwriting executable recovery, and rollback failure producing a durable record plus a rescan request.
- [ ] Run the focused tests and confirm missing repository/integration is the failure reason.
- [ ] Implement serialized recovery entries, atomic record/remove updates, root and `NOFOLLOW` validation, target-conflict behavior, and idempotent recovery.
- [ ] Persist rollback failures in `LibraryService`, return persisted records, and request a rescan.
- [ ] Re-run the focused tests and confirm green.

### Task 4: Real rescan wiring and persistent recovery UI

**Files:**
- Modify: `app/src/main/java/app/monote/mobile/AppContainer.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/LibraryViewModel.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/LibraryScreen.kt`
- Modify: `app/src/main/java/app/monote/mobile/ui/navigation/MoNoteApp.kt`
- Modify: `app/src/androidTest/java/app/monote/mobile/feature/library/LibraryScreenTest.kt`

- [ ] Add a failing Compose test that a recovery record shows “已记录待恢复” and clicking “执行恢复” invokes the callback.
- [ ] Run that single Android test and confirm the persistent entry is missing.
- [ ] Construct both repositories in `AppContainer`, inject `requestRescan = { indexer.scan(paths.root) }`, expose recovery records in `LibraryUiState`, rescan on batch failure, and implement `recoverMove(id)`.
- [ ] Render the durable recovery list and action in `LibraryScreen` and wire navigation.
- [ ] Re-run the focused Android test and confirm green.

### Task 5: Final verification and commit

**Files:**
- Verify every modified source, test, spec, and plan file.

- [ ] Run all focused JVM tests for directory metadata, library moves, trash lifecycle, and recovery repository; expect zero failures.
- [ ] Run `:app:testDebugUnitTest --offline --no-daemon`; expect zero failures.
- [ ] Run the necessary focused connected Android UI test; expect zero failures.
- [ ] Run `git diff --check`, review the complete staged diff, and verify only intended files are staged.
- [ ] Commit once with `fix: preserve library metadata and recovery state`; do not amend or push.

### Task 6: Harden exceptional consistency paths

**Files:**
- Modify: `app/src/main/java/app/monote/mobile/feature/library/MoveRecoveryRepository.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/LibraryModels.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/LibraryService.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/LibraryViewModel.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/DirectoryMetadataRepository.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/TrashRepository.kt`
- Test: `app/src/test/java/app/monote/mobile/feature/library/MoveRecoveryRepositoryTest.kt`
- Test: `app/src/test/java/app/monote/mobile/feature/library/LibraryServiceTest.kt`
- Test: `app/src/test/java/app/monote/mobile/feature/library/TrashRepositoryTest.kt`

- [ ] Add a failing service test with a throwing recovery store and assert no unpersisted records are returned, a rescan is requested, and the message never says “已记录待恢复”.
- [ ] Introduce an explicit `RecoveryWriteResult.Recorded/Failed` boundary and use only `Recorded` records in the batch failure result.
- [ ] Add failing reload tests for missing current paths and unsafe stored paths; load safe missing records, clear already-restored records idempotently, and keep double-missing records visible on explicit failure.
- [ ] Add failing real-`AtomicTextStore` tests for one-time cleanup failure followed by permanent-delete, empty-trash, and list/purge reconciliation retries.
- [ ] Add atomic `reconcileTrashed(actualStableIds)` and invoke it only after irreversible trash deletion or while reading current physical trash state.
- [ ] Run focused tests, full JVM, necessary UI verification, staged diff checks, and commit once with `fix: harden recovery reconciliation` without pushing.

### Task 7: Make recovery and startup cleanup cancellation-safe

**Files:**
- Modify: `app/src/main/java/app/monote/mobile/feature/library/LibraryService.kt`
- Modify: `app/src/main/java/app/monote/mobile/feature/library/TrashRepository.kt`
- Modify: `app/src/main/java/app/monote/mobile/AppContainer.kt`
- Test: `app/src/test/java/app/monote/mobile/feature/library/LibraryServiceTest.kt`
- Test: `app/src/test/java/app/monote/mobile/feature/library/TrashRepositoryTest.kt`

- [ ] Add a failing batch-move test where rollback has failed and the recovery store throws `CancellationException`; assert a visible failure, one rescan, no unpersisted records, and the severe persistence message.
- [ ] Move the post-rollback journal attempt, rescan request, and result construction into `withContext(NonCancellable)`; convert journal cancellation into `RecoveryWriteResult.Failed` rather than rethrowing it.
- [ ] Add a failing trash test where a format-valid directory has corrupt entry data and currently protects an orphan held metadata bucket.
- [ ] Derive every reconciliation stable-ID from `parseEntry` success so corrupt, empty, temporary, and unsafe children are ignored.
- [ ] Add a failing restart test for public `reconcileStartup()` that clears orphan held metadata without calling `listEntries()`.
- [ ] Launch `reconcileStartup()` after `TrashRepository` construction in `AppContainer` on the application IO scope, catching non-cancellation failures so initialization remains available for later retry.
- [ ] Run the related JVM tests, complete JVM suite, necessary Android compile/UI checks, staged diff validation, and commit `fix: make recovery cleanup cancellation-safe` without amending or pushing.

### Task 8: Serialize trash reconciliation with state transitions

**Files:**
- Modify: `app/src/main/java/app/monote/mobile/feature/library/TrashRepository.kt`
- Test: `app/src/test/java/app/monote/mobile/feature/library/TrashRepositoryTest.kt`

- [ ] Add a test-only suspend hook after reconciliation scans parseable entries but before it writes held metadata.
- [ ] Add a deterministic failing test that pauses startup reconciliation on an empty snapshot, races `moveToTrash`, then proves the new entry's directory metadata bucket is lost without serialization.
- [ ] Add one repository `Mutex`; make `moveToTrash`, `listEntries`, `reconcileStartup`, `restore`, `deletePermanently`, `emptyTrash`, and `purgeExpired` acquire it exactly once.
- [ ] Extract private locked list, reconcile, and delete helpers so public methods never call another lock-taking public method.
- [ ] Add deterministic reconciliation-versus-restore and reconciliation-versus-delete tests proving no deadlock and no incorrect bucket removal.
- [ ] Run `TrashRepositoryTest`, the complete JVM suite, and one debug assemble; validate the staged diff and commit `fix: serialize trash reconciliation` without amending or pushing.
