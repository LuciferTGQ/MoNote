# Task 10 Metadata and Recovery Design

## Scope

Close the remaining Task 10 lifecycle gaps without building the broader Task 12 recovery center. Directory favorite/tag metadata must follow directories through rename, batch move, trash, restore, and permanent deletion. Failed batch-move rollbacks must leave a durable, auditable, executable recovery record.

## Directory metadata lifecycle

`DirectoryMetadataRepository` remains the single owner of `_MoNoteSystem/directory-metadata.json`. The store contains active path-keyed metadata and metadata held under trash stable IDs. Every update constructs a complete next state and persists it with `AtomicTextStore.replace`; the observable state changes only after the atomic replacement succeeds.

Path migration operates on directory path segments: a key matches a source only when it equals the source or begins with `source/`. Batch migrations are computed from one snapshot and written once. Destination subtrees are cleared before migrated records are installed so stale legacy keys cannot leak into newly moved content.

Moving a directory to trash transfers its whole active subtree into a stable-ID bucket. Restore transfers the bucket back to the active destination. Permanent deletion, purge, and empty-trash remove the bucket. This prevents a newly created directory at an old path from inheriting deleted metadata.

## Move rollback recovery

`MoveRecoveryRepository` owns `_MoNoteSystem/move-recovery.json`. A failed rollback records the original path, current path, error, stable record ID, and timestamp using atomic replacement. Records are loaded on construction and exposed through a `StateFlow`.

Recovery validates both paths inside the content root without following symbolic links. It never overwrites an existing target. If the current path exists and the original does not, it moves the item back; if the move already completed, it treats the record idempotently. The record is removed only after successful recovery, then the caller performs a real library scan.

## Integration and UI

`LibraryService` migrates directory metadata only after filesystem moves succeed. Batch metadata migration occurs after every filesystem move succeeds, so a normal rollback leaves old metadata keys untouched. Rollback failures are persisted before the failure result is returned and trigger the injected rescan callback.

`TrashRepository` coordinates active/trash metadata transfers with content moves and removes trash metadata during permanent cleanup. `AppContainer` injects a real suspending `LibraryIndexer.scan(root)` callback.

The library screen observes durable move-recovery records. When records exist it displays “已记录待恢复”, lists the paths, and provides an “执行恢复” action. The action uses the repository recovery operation and refreshes the library index.

## Verification

Focused JVM tests cover path-segment-safe metadata migration, rename/move preservation, trash/restore/delete lifecycle, recovery-log persistence across reconstruction, rollback-failure logging and rescan, and executable recovery. A focused Android Compose test covers the durable recovery notice and action. The full JVM suite and staged diff checks are final gates.

## Consistency hardening amendment

Recovery-log writes have an explicit recorded-or-failed result. A failed atomic replace never exposes an in-memory record as durable, and the move failure message states that persistence failed without claiming an executable recovery card exists. Journal loading validates root containment and symbolic-link safety independently of path existence: an already-restored record is cleared idempotently, while a record whose original and current paths are both missing remains visible with a clear non-automatic-recovery result.

Held trash metadata is reconciled against the set of format-valid, real trash stable-ID directories. Listing trash and every retryable permanent cleanup path perform this reconciliation. Because reconciliation runs only after irreversible entity deletion, a metadata write failure cannot strip metadata from content that is still restorable; a later list, NotFound delete retry, empty-trash retry, or purge retry removes stale held buckets.

## Cancellation-safe cleanup amendment

Once a batch move has a failed rollback, recovery persistence and the rescan request form one `NonCancellable` critical section. Cancellation at that point is represented as a visible batch failure: a successful journal write exposes only durable recovery records, while a failed or cancelled journal write exposes no new recovery action and carries the high-priority persistence error.

Trash reconciliation derives its live stable-ID set only from entries that pass the repository's complete `parseEntry` validation. App startup schedules `reconcileStartup()` on the application IO scope, catches failures so startup continues, and leaves later startup/list/delete calls able to retry. Empty, corrupt, symbolic-link, and temporary children never protect orphan held metadata.

## Serialized reconciliation amendment

Every trash operation that transitions physical content and held directory metadata shares one repository mutex with reconciliation. Public methods acquire the mutex exactly once and delegate to private locked helpers, so `purgeExpired` can list and delete without re-entering a non-reentrant lock. Reconciliation scans parseable entries and persists that exact stable-ID set while the same lock prevents move, restore, permanent delete, empty, or purge from changing either side of the snapshot.
