package app.monote.mobile.feature.editor

import app.monote.mobile.core.storage.AtomicTextStore
import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.core.storage.LibraryDirectories
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface RecoveryDraftSink {
    suspend fun write(session: DocumentSession)
    suspend fun delete(stableId: String)
}

interface OriginalDocumentSink {
    suspend fun save(session: DocumentSession): DocumentSaveResult
}

sealed interface DocumentSaveResult {
    data class Saved(val fingerprint: DocumentFingerprint) : DocumentSaveResult
    data class Conflict(val external: ExternalDocumentVersion) : DocumentSaveResult
}

class SaveCoordinator(
    private val scope: CoroutineScope,
    private val recovery: RecoveryDraftSink,
    private val original: OriginalDocumentSink,
    private val onEvent: (DocumentEvent) -> Unit,
) {
    private var pendingSave: Job? = null
    private val recoveryWriteMutex = Mutex()
    private val originalSaveMutex = Mutex()
    private var baselineDocumentId: String? = null
    private val knownOwnBaselines = linkedSetOf<DocumentFingerprint>()
    private var latestOwnBaseline: DocumentFingerprint? = null

    fun onChanged(session: DocumentSession) {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(RECOVERY_DELAY_MILLIS)
            persistRecovery(session)
            if (!session.autoSaveAllowed || !session.isDirty) return@launch
            delay(ORIGINAL_DELAY_MILLIS - RECOVERY_DELAY_MILLIS)
            persistOriginal(session)
        }
    }

    suspend fun flushForBackground(session: DocumentSession) {
        pendingSave?.cancelAndJoin()
        pendingSave = null
        persistRecovery(session)
        if (session.autoSaveAllowed && session.isDirty) persistOriginal(session)
    }

    suspend fun onCleanClose(session: DocumentSession) {
        pendingSave?.cancelAndJoin()
        pendingSave = null
        if (!session.isDirty && session.externalConflict == null) {
            try {
                recovery.delete(session.id)
            } catch (error: Exception) {
                onEvent(DocumentEvent.RecoveryFailed(error.userFacingMessage()))
            }
        }
    }

    fun cancel() {
        pendingSave?.cancel()
        pendingSave = null
    }

    private suspend fun persistRecovery(session: DocumentSession) {
        withContext(NonCancellable) {
            try {
                recoveryWriteMutex.withLock { recovery.write(session) }
                onEvent(DocumentEvent.RecoverySaved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onEvent(DocumentEvent.RecoveryFailed(error.userFacingMessage()))
            }
        }
    }

    private suspend fun persistOriginal(session: DocumentSession) {
        onEvent(DocumentEvent.SaveStarted)
        withContext(NonCancellable) {
            try {
                val result = originalSaveMutex.withLock {
                    val effectiveSession = session.withLatestOwnBaseline()
                    original.save(effectiveSession).also {
                        if (it is DocumentSaveResult.Saved) {
                            knownOwnBaselines += effectiveSession.baseline
                            knownOwnBaselines += it.fingerprint
                            latestOwnBaseline = it.fingerprint
                        }
                    }
                }
                when (result) {
                    is DocumentSaveResult.Saved -> {
                        onEvent(DocumentEvent.Saved(result.fingerprint))
                    }
                    is DocumentSaveResult.Conflict -> {
                        onEvent(DocumentEvent.ExternalConflict(result.external))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onEvent(DocumentEvent.SaveFailed(error.userFacingMessage()))
            }
        }
    }

    private fun DocumentSession.withLatestOwnBaseline(): DocumentSession {
        if (baselineDocumentId != id) {
            baselineDocumentId = id
            knownOwnBaselines.clear()
            knownOwnBaselines += baseline
            latestOwnBaseline = baseline
            return this
        }
        if (baseline !in knownOwnBaselines) {
            knownOwnBaselines.clear()
            knownOwnBaselines += baseline
            latestOwnBaseline = baseline
            return this
        }
        val latest = latestOwnBaseline ?: baseline
        return if (latest == baseline) this else copy(baseline = latest)
    }

    private fun Throwable.userFacingMessage(): String =
        message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private companion object {
        const val RECOVERY_DELAY_MILLIS = 500L
        const val ORIGINAL_DELAY_MILLIS = 1_000L
    }
}

class FileDocumentSink(
    private val paths: LibraryDirectories,
    private val textStore: AtomicTextStore = AtomicTextStore(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OriginalDocumentSink {
    override suspend fun save(session: DocumentSession): DocumentSaveResult =
        withContext(ioDispatcher) {
            val before = readExternalDocument(session.file)
            if (!before.matches(session.baseline)) {
                return@withContext DocumentSaveResult.Conflict(before)
            }
            val validateBaseline = {
                val current = readExternalDocument(session.file)
                if (!current.matches(session.baseline)) {
                    throw ExternalChangeDuringSaveException()
                }
            }
            try {
                textStore.replace(
                    target = session.file,
                    text = session.text,
                    backup = backupFile(session.id),
                    beforeCommit = validateBaseline,
                    beforeReplace = validateBaseline,
                )
            } catch (_: ExternalChangeDuringSaveException) {
                return@withContext DocumentSaveResult.Conflict(
                    readExternalDocument(session.file),
                )
            }
            val fingerprint = DocumentFingerprint.from(session.file)
            check(fingerprint.sha256 == session.contentSha256) {
                "Saved document checksum does not match the editor content"
            }
            DocumentSaveResult.Saved(fingerprint)
        }

    private fun backupFile(stableId: String): File =
        paths.backups.resolve("${requireSafeDocumentId(stableId)}.md")

    private class ExternalChangeDuringSaveException : IllegalStateException()
}

private fun ExternalDocumentVersion.matches(baseline: DocumentFingerprint): Boolean =
    this is ExternalDocumentVersion.Present && fingerprint == baseline
