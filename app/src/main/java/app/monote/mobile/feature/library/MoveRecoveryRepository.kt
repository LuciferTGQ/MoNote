package app.monote.mobile.feature.library

import app.monote.mobile.core.storage.AtomicTextStore
import app.monote.mobile.core.storage.LibraryDirectories
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface MoveRecoveryStore {
    val records: StateFlow<List<MoveRecoveryRecord>>

    suspend fun record(failures: List<MoveRecoveryRecord>): RecoveryWriteResult

    suspend fun recover(id: String): LibraryResult
}

sealed interface RecoveryWriteResult {
    data class Recorded(val records: List<MoveRecoveryRecord>) : RecoveryWriteResult

    data class Failed(val message: String, val cause: Throwable) : RecoveryWriteResult
}

class MoveRecoveryRepository(
    private val paths: LibraryDirectories,
    private val textStore: AtomicTextStore = AtomicTextStore(),
) : MoveRecoveryStore {
    private val guard = LibraryPathGuard(paths)
    private val file = paths.system.resolve("move-recovery.json")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val mutableRecords = MutableStateFlow(read())
    override val records: StateFlow<List<MoveRecoveryRecord>> = mutableRecords.asStateFlow()

    override suspend fun record(failures: List<MoveRecoveryRecord>): RecoveryWriteResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val validated = failures.map(::validatedRecord)
                val byId = mutableRecords.value.associateByTo(linkedMapOf()) { it.id }
                validated.forEach { recovery -> byId[recovery.id] = recovery }
                persist(byId.values.toList())
                RecoveryWriteResult.Recorded(validated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RecoveryWriteResult.Failed(
                    failure.message ?: "Unable to persist move recovery records",
                    failure,
                )
            }
        }
    }

    override suspend fun recover(id: String): LibraryResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val record = mutableRecords.value.firstOrNull { it.id == id }
                ?: return@withContext LibraryResult.Failure(
                    "Move recovery record not found: $id",
                    IllegalArgumentException("Unknown move recovery record"),
                )
            try {
                val original = guard.contentPath(record.original)
                val current = guard.contentPath(record.current)
                val originalExists = Files.exists(original, NOFOLLOW_LINKS)
                val currentExists = Files.exists(current, NOFOLLOW_LINKS)
                if (originalExists && currentExists) {
                    return@withContext LibraryResult.Conflict(original.toFile())
                }
                if (!originalExists && !currentExists) {
                    throw IllegalStateException(
                        "无法自动恢复：原位置与当前位置均不存在（${original.toFile().path}；${current.toFile().path}）",
                    )
                }
                if (currentExists) {
                    require(!Files.isSymbolicLink(current)) { "Move recovery source is a symbolic link" }
                    val parent = original.parent ?: throw IllegalArgumentException("Move recovery target has no parent")
                    guard.contentPath(parent.toFile(), allowRoot = true, requireExists = true)
                    require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
                        "Move recovery target parent is not a real directory: $parent"
                    }
                    Files.move(current, original)
                }
                persist(mutableRecords.value.filterNot { it.id == id })
                LibraryResult.Success(original.toFile())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LibraryResult.Failure(failure.message ?: "Move recovery failed", failure)
            }
        }
    }

    private fun validatedRecord(record: MoveRecoveryRecord): MoveRecoveryRecord {
        require(RECORD_ID.matches(record.id)) { "Invalid move recovery ID: ${record.id}" }
        val original = guard.contentPath(record.original)
        val current = guard.contentPath(record.current, requireExists = true)
        require(Files.exists(current, NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) {
            "Move recovery source is missing or unsafe: $current"
        }
        return record.copy(original = original.toFile(), current = current.toFile())
    }

    private fun persist(next: List<MoveRecoveryRecord>) {
        val sorted = next.sortedWith(compareBy<MoveRecoveryRecord> { it.createdAt }.thenBy { it.id })
        textStore.replace(
            file,
            json.encodeToString(
                MoveRecoveryJournal(
                    records = sorted.map { record ->
                        SerializedMoveRecovery(
                            id = record.id,
                            originalRelativePath = guard.relativeContentPath(record.original.toPath()),
                            currentRelativePath = guard.relativeContentPath(record.current.toPath()),
                            message = record.message,
                            createdAt = record.createdAt.toString(),
                        )
                    },
                ),
            ),
        )
        mutableRecords.value = sorted
    }

    private fun read(): List<MoveRecoveryRecord> = try {
        if (!file.isFile) return emptyList()
        json.decodeFromString<MoveRecoveryJournal>(file.readText(Charsets.UTF_8)).records.mapNotNull { stored ->
            runCatching {
                val original = guard.contentPath(paths.root.resolve(stored.originalRelativePath))
                val current = guard.contentPath(paths.root.resolve(stored.currentRelativePath))
                require(RECORD_ID.matches(stored.id))
                MoveRecoveryRecord(
                    original = original.toFile(),
                    current = current.toFile(),
                    message = stored.message,
                    id = stored.id,
                    createdAt = Instant.parse(stored.createdAt),
                )
            }.getOrNull()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        val RECORD_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

@Serializable
private data class MoveRecoveryJournal(
    val version: Int = 1,
    val records: List<SerializedMoveRecovery> = emptyList(),
)

@Serializable
private data class SerializedMoveRecovery(
    val id: String,
    val originalRelativePath: String,
    val currentRelativePath: String,
    val message: String,
    val createdAt: String,
)
