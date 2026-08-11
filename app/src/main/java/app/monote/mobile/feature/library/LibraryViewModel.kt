package app.monote.mobile.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.monote.mobile.data.catalog.CatalogRepository
import app.monote.mobile.data.catalog.DocumentEntity
import app.monote.mobile.data.catalog.LibraryIndexer
import app.monote.mobile.data.catalog.ScanStatus
import app.monote.mobile.core.storage.LibraryDirectories
import app.monote.mobile.feature.importing.DocumentSource
import app.monote.mobile.feature.importing.FolderImportCoordinator
import app.monote.mobile.feature.importing.ImportCoordinator
import app.monote.mobile.feature.importing.TreeDocumentSource
import app.monote.mobile.feature.editor.ReadingStateRepository
import app.monote.mobile.feature.editor.editorDocumentFor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    paths: LibraryDirectories,
    private val catalog: CatalogRepository,
    private val indexer: LibraryIndexer,
    private val libraryService: LibraryService,
    private val importCoordinator: ImportCoordinator,
    private val trashRepository: TrashRepository,
    private val folderImportCoordinator: FolderImportCoordinator,
    private val directoryMetadataRepository: DirectoryMetadataRepository,
    private val moveRecoveryRepository: MoveRecoveryRepository,
    private val readingStateRepository: ReadingStateRepository = ReadingStateRepository(paths),
) : ViewModel() {
    private val browser = LibraryBrowser(paths)
    private val root = browser.root
    private val currentFolder = MutableStateFlow(root)
    private val query = MutableStateFlow("")
    private val selection = SelectionCoordinator()
    private val selectedIds = selection.selectedIds
    private val activeTags = MutableStateFlow<Set<String>>(emptySet())
    private val error = MutableStateFlow<String?>(null)

    private val documents = combine(catalog.observeAll(), query.debounce(250)) { all, requested -> all to requested }
        .mapLatest { (all, requested) ->
            val matching = if (requested.isBlank()) all else withContext(Dispatchers.IO) { catalog.search(requested) }
            withContext(Dispatchers.IO) { matching.map { it to catalog.tags(it.id) } }
        }

    val uiState = combine(
        documents,
        currentFolder,
        query,
        selectedIds,
        activeTags,
        directoryMetadataRepository.metadata,
        moveRecoveryRepository.records,
        indexer.progress,
        error,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tagged = values[0] as List<Pair<DocumentEntity, Set<String>>>
        val folder = values[1] as File
        val requested = values[2] as String
        @Suppress("UNCHECKED_CAST") val selected = values[3] as Set<String>
        @Suppress("UNCHECKED_CAST") val tags = values[4] as Set<String>
        @Suppress("UNCHECKED_CAST") val directoryMetadata = values[5] as Map<String, DirectoryMetadata>
        @Suppress("UNCHECKED_CAST") val recoveryRecords = values[6] as List<MoveRecoveryRecord>
        val progress = values[7] as app.monote.mobile.data.catalog.ScanProgress
        val failure = values[8] as String?
        val state = buildState(
            folder,
            tagged,
            requested,
            selected,
            tags,
            directoryMetadata,
            recoveryRecords,
            progress.status == ScanStatus.SCANNING,
            progress.errors.distinct().takeLast(MAX_SCAN_WARNINGS),
            failure,
        )
        val visibleIds = state.items.mapTo(linkedSetOf()) { it.id }
        selection.reconcile(visibleIds)
        state.copy(selectedIds = selected.intersect(visibleIds))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LibraryUiState(currentFolder = root, loading = true),
    )

    init { rescan() }

    fun updateQuery(value: String) { query.value = value }
    fun clearError() { error.value = null }
    fun reportError(message: String) { error.value = message }
    fun updateSelection(value: Set<String>) { selection.update(value) }
    fun openFolder(folder: File) {
        runCatching { browser.folder(folder) }
            .onSuccess { currentFolder.value = it }
            .onFailure { error.value = "无法打开目录：${it.message.orEmpty()}" }
    }
    fun goToParent() {
        val current = currentFolder.value.toPath().toAbsolutePath().normalize()
        val rootPath = root.toPath().toAbsolutePath().normalize()
        if (current == rootPath) return
        val parent = current.parent ?: return
        runCatching { browser.folder(parent.toFile()) }
            .onSuccess { currentFolder.value = it }
            .onFailure { error.value = "无法返回上一级：${it.message.orEmpty()}" }
    }
    fun setActiveTags(tags: Set<String>) { activeTags.value = tags }

    fun rescan() = launchAction(clearSelection = false) { indexer.scan(root) }
    fun createNote(name: String) = launchAction { libraryService.createMarkdown(browser.folder(currentFolder.value), name).throwIfFailure(); indexer.scan(root) }
    fun createFolder(name: String) = launchAction { libraryService.createFolder(browser.folder(currentFolder.value), name).throwIfFailure(); indexer.scan(root) }
    fun renameSelected(name: String) = launchAction {
        val item = selectedItems().singleOrNull() ?: error("重命名仅支持单个条目")
        migrateLegacyReadingStates(listOf(item))
        libraryService.rename(item.file, name).throwIfFailure(); indexer.scan(root)
    }
    fun moveSelected(destinationRelativePath: String) = launchAction {
        val destination = browser.moveDestination(destinationRelativePath)
        val items = selectedItems().also { require(it.isNotEmpty()) { "没有可移动的已选条目" } }
        migrateLegacyReadingStates(items)
        when (val result = libraryService.moveBatch(items.map { it.file }, destination)) {
            is BatchMoveResult.Success -> {
                if (!result.catalogSynchronized) error.value = "移动已完成，但资料库索引同步失败，请重新扫描。"
                indexer.scan(root)
            }
            is BatchMoveResult.Conflict -> error("目标位置已有 ${result.existing.name}")
            is BatchMoveResult.Failure -> {
                indexer.scan(root)
                error(batchMoveFailureMessage(result))
            }
        }
    }
    fun recoverMove(id: String) = launchAction(clearSelection = false) {
        when (val result = moveRecoveryRepository.recover(id)) {
            is LibraryResult.Success -> indexer.scan(root)
            is LibraryResult.Conflict -> error("恢复失败：目标位置已有 ${result.existing.name}")
            is LibraryResult.Failure -> error("恢复失败：${result.message}")
        }
    }
    fun toggleFavorite(ids: Set<String>) = launchAction {
        val items = uiState.value.items.filter { it.id in ids }
        require(items.isNotEmpty() && items.size == ids.size) { "没有可收藏的已选条目" }
        val favorite = items.any { !it.favorite }
        val documentIds = items.filterNot { it.isFolder }.mapTo(linkedSetOf()) { it.id }
        val folders = items.filter { it.isFolder }.mapTo(linkedSetOf()) { it.relativePath }
        if (documentIds.isNotEmpty()) catalog.setFavorite(documentIds, favorite)
        if (folders.isNotEmpty()) directoryMetadataRepository.setFavorite(folders, favorite)
    }
    fun setTags(ids: Set<String>, tags: Set<String>) = launchAction {
        val items = uiState.value.items.filter { it.id in ids }
        require(items.isNotEmpty() && items.size == ids.size) { "没有可设置标签的已选条目" }
        val documentIds = items.filterNot { it.isFolder }.mapTo(linkedSetOf()) { it.id }
        val folders = items.filter { it.isFolder }.mapTo(linkedSetOf()) { it.relativePath }
        if (documentIds.isNotEmpty()) catalog.setTags(documentIds, tags)
        if (folders.isNotEmpty()) directoryMetadataRepository.setTags(folders, tags)
    }
    fun deleteSelected() = launchAction {
        migrateLegacyReadingStates(selectedItems())
        selectedItems().also { require(it.isNotEmpty()) { "没有可删除的已选条目" } }
            .forEach { trashRepository.moveToTrash(it.file) }
        indexer.scan(root)
    }
    fun importDocuments(sources: List<DocumentSource>, onImported: (String?, File) -> Unit = { _, _ -> }) = launchAction {
        importDocumentsInto(sources, browser.folder(currentFolder.value), onImported)
    }

    fun importIncomingDocuments(
        sources: List<DocumentSource>,
        onImported: (String?, File) -> Unit = { _, _ -> },
    ) = launchAction {
        val inbox = when (val result = libraryService.createFolder(root, INBOX_DIRECTORY)) {
            is LibraryResult.Success -> result.file
            is LibraryResult.Conflict -> result.existing
            is LibraryResult.Failure -> throw result.cause
        }
        importDocumentsInto(sources, browser.folder(inbox), onImported)
    }

    private suspend fun importDocumentsInto(
        sources: List<DocumentSource>,
        destination: File,
        onImported: (String?, File) -> Unit,
    ) {
        var first: File? = null
        val failures = mutableListOf<String>()
        var importedCount = 0
        sources.forEach { source ->
            try {
                val imported = importCoordinator.import(source, destination)
                importedCount += 1
                if (first == null) first = imported.file
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += "${source.displayName}：${failure.message ?: "导入失败"}"
            }
        }
        indexer.scan(root)
        first?.let { file -> onImported(stableDocumentId(file), file) }
        if (failures.isNotEmpty()) {
            error.value = "已导入 $importedCount 个，失败 ${failures.size} 个。${failures.joinToString("；")}"
        }
        if (importedCount == 0 && failures.isNotEmpty()) error(failures.joinToString("；"))
    }

    fun importFolder(
        source: TreeDocumentSource,
        onImported: (String?, File) -> Unit = { _, _ -> },
    ) = launchAction {
        val imported = folderImportCoordinator.import(source, browser.folder(currentFolder.value))
        indexer.scan(root)
        onImported(stableDocumentId(imported.firstMarkdown), imported.firstMarkdown)
    }

    private suspend fun stableDocumentId(file: File): String? {
        val relative = root.toPath().toAbsolutePath().normalize()
            .relativize(file.toPath().toAbsolutePath().normalize())
            .toString()
            .replace(File.separatorChar, '/')
        return catalog.getByPath(relative)?.id
    }

    private suspend fun migrateLegacyReadingStates(items: List<LibraryItem>) {
        catalog.all().filter { document ->
            items.any { item ->
                if (item.isFolder) {
                    document.relativePath.startsWith("${item.relativePath.trimEnd('/')}/")
                } else {
                    document.id == item.id
                }
            }
        }.forEach { document ->
            val file = root.resolve(document.relativePath.replace('/', File.separatorChar))
            readingStateRepository.migrate(editorDocumentFor(file, root).id, document.id)
        }
    }

    private fun launchAction(clearSelection: Boolean = true, action: suspend () -> Unit) {
        viewModelScope.launch {
            error.value = null
            try {
                action()
                if (clearSelection) selection.update(emptySet())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = failure.message ?: "操作失败，请重试"
            }
        }
    }

    private fun selectedItems(): List<LibraryItem> = uiState.value.items.filter { it.id in selectedIds.value }

    private fun buildState(
        folder: File,
        documents: List<Pair<DocumentEntity, Set<String>>>,
        requested: String,
        selected: Set<String>,
        tags: Set<String>,
        directoryMetadata: Map<String, DirectoryMetadata>,
        recoveryRecords: List<MoveRecoveryRecord>,
        scanning: Boolean,
        scanWarnings: List<String>,
        failure: String?,
    ): LibraryUiState {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val folderPath = folder.toPath().toAbsolutePath().normalize()
        val documentItems = documents.mapNotNull { (document, documentTags) ->
            if (tags.isNotEmpty() && !documentTags.containsAll(tags)) return@mapNotNull null
            val file = runCatching { browser.catalogFile(document.relativePath) }.getOrNull() ?: return@mapNotNull null
            if (requested.isBlank() && file.parentFile?.toPath()?.toAbsolutePath()?.normalize() != folderPath) return@mapNotNull null
            LibraryItem(document.id, file, document.relativePath, file.name, false, document.favorite, documentTags, document.modifiedAt)
        }
        val folders = if (requested.isBlank()) runCatching { browser.folders(folder) }.getOrDefault(emptyList())
            .map { directory ->
                val relative = rootPath.relativize(directory.toPath().toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/')
                val metadata = directoryMetadata[relative]
                LibraryItem("folder:$relative", directory, relative, directory.name, true, metadata?.favorite == true, metadata?.tags.orEmpty())
            }
            .filter { tags.isEmpty() || it.tags.containsAll(tags) }
        else emptyList()
        val allRecent = documents.sortedByDescending { it.first.lastOpenedAt ?: it.first.modifiedAt }.mapNotNull { (document, documentTags) ->
            val file = runCatching { browser.catalogFile(document.relativePath) }.getOrNull() ?: return@mapNotNull null
            LibraryItem(document.id, file, document.relativePath, file.name, false, document.favorite, documentTags, document.modifiedAt)
        }.take(3)
        return LibraryUiState(
            currentFolder = folder,
            recent = allRecent,
            items = folders + documentItems.sortedBy { it.displayName.lowercase() },
            query = requested,
            selectedIds = selected,
            activeTags = tags,
            moveRecoveryRecords = recoveryRecords,
            scanning = scanning,
            canNavigateUp = folderPath != rootPath,
            scanWarnings = scanWarnings,
            error = failure,
        )
    }

    private fun LibraryResult.throwIfFailure() {
        when (this) {
            is LibraryResult.Success -> Unit
            is LibraryResult.Conflict -> error("同名条目已存在：${existing.name}")
            is LibraryResult.Failure -> throw cause
        }
    }
}

internal fun batchMoveFailureMessage(result: BatchMoveResult.Failure): String {
    val recovery = when {
        result.rolledBack -> "所有变更已回滚。"
        result.recoveryPersistenceFailure != null ->
            "严重：移动回滚未完成，且待恢复记录写入失败：${result.recoveryPersistenceFailure}。已重新扫描，请立即检查资料库。"
        result.recoveryRecords.isNotEmpty() ->
            "已记录待恢复：${result.recoveryRecords.joinToString { "${it.current.path} → ${it.original.path}" }}"
        else -> "严重：移动回滚未完成，且没有可用的恢复记录。已重新扫描，请立即检查资料库。"
    }
    return "移动失败：${result.message} $recovery"
}

class LibraryViewModelFactory(
    private val create: () -> LibraryViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

private const val MAX_SCAN_WARNINGS = 5
private const val INBOX_DIRECTORY = "收件箱"
