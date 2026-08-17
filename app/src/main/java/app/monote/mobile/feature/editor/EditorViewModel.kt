package app.monote.mobile.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.core.storage.LibraryDirectories
import app.monote.mobile.feature.editor.bridge.DocumentSurfaceController
import app.monote.mobile.feature.editor.bridge.EditorCommand
import app.monote.mobile.feature.editor.bridge.EditorMode
import app.monote.mobile.feature.editor.bridge.EditorTheme
import app.monote.mobile.feature.editor.bridge.NativeMessage
import app.monote.mobile.feature.editor.bridge.SearchAction
import app.monote.mobile.feature.editor.bridge.WebMessage
import app.monote.mobile.feature.settings.AppSettingsStore
import app.monote.mobile.feature.settings.EditorFontSize
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(
    private val document: EditorDocument,
    private val paths: LibraryDirectories,
    private val recoveryStore: RecoveryStore,
    private val orientationStore: OrientationPreferenceStore,
    private val appSettingsStore: AppSettingsStore,
    private val readingStateRepository: ReadingStateRepository = ReadingStateRepository(paths),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val reducer = DocumentSessionReducer()
    private val mutableUiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = mutableUiState.asStateFlow()

    private var surfaceReady = false
    private var isLandscape = false
    private var theme = EditorTheme.LIGHT
    private var editorFontSize = EditorFontSize.Standard
    private var documentLoadStarted = false
    private var splitRatioSaveJob: Job? = null
    private var readingStateSaveJob: Job? = null
    private var readingControlsJob: Job? = null
    private var readingDocumentState: ReadingDocumentState? = null
    private var readingPositionRestored = false
    private val saveCoordinator = SaveCoordinator(
        scope = viewModelScope,
        recovery = recoveryStore,
        original = FileDocumentSink(paths),
        onEvent = ::handleDocumentEvent,
    )
    private val conflictResolver = ConflictResolver(paths)

    val surfaceController = DocumentSurfaceController(
        onProtocolError = { error ->
            mutableUiState.update { it.copy(error = error.message ?: "编辑器通信失败") }
        },
        onMessage = ::handleWebMessage,
    )

    init {
        viewModelScope.launch {
            orientationStore.preference.collect { preference ->
                mutableUiState.update { it.copy(orientationPreference = preference) }
            }
        }
        viewModelScope.launch {
            orientationStore.splitRatio.collect { ratio ->
                mutableUiState.update { it.copy(splitRatio = ratio) }
                if (surfaceReady) surfaceController.send(NativeMessage.SetSplitRatio(ratio))
            }
        }
        viewModelScope.launch {
            appSettingsStore.fontSize.collect { size ->
                editorFontSize = size
                if (surfaceReady) surfaceController.send(NativeMessage.SetFontSize(size.pixels))
            }
        }
        viewModelScope.launch {
            appSettingsStore.autoSave.collect { enabled ->
                val session = mutableUiState.value.session
                if (session == null) {
                    if (!documentLoadStarted) {
                        documentLoadStarted = true
                        loadDocument(enabled)
                    }
                    return@collect
                }
                val updated = reducer.reduce(session, DocumentEvent.AutoSaveChanged(enabled))
                mutableUiState.update { it.copy(session = updated) }
                if (updated.isDirty) saveCoordinator.onChanged(updated)
            }
        }
    }

    fun selectTab(tab: EditorTab) {
        mutableUiState.update { it.copy(selectedTab = tab) }
        sendMode()
    }

    fun setLandscape(landscape: Boolean) {
        if (isLandscape == landscape) return
        isLandscape = landscape
        sendMode()
    }

    fun setTheme(dark: Boolean) {
        val next = if (dark) EditorTheme.DARK else EditorTheme.LIGHT
        if (theme == next) return
        theme = next
        if (surfaceReady) sendLoad()
    }

    fun setSplitRatio(ratio: Float) {
        val bounded = normalizedSplitRatio(ratio)
        mutableUiState.update { it.copy(splitRatio = bounded) }
        if (surfaceReady) surfaceController.send(NativeMessage.SetSplitRatio(bounded))
        splitRatioSaveJob?.cancel()
        splitRatioSaveJob = viewModelScope.launch {
            delay(SPLIT_RATIO_SAVE_DELAY_MILLIS)
            orientationStore.setSplitRatio(bounded)
        }
    }

    fun setOrientation(preference: OrientationPreference) {
        viewModelScope.launch {
            try {
                orientationStore.set(preference)
            } catch (error: Exception) {
                mutableUiState.update { it.copy(error = error.message ?: "无法保存旋转设置") }
            }
        }
    }

    fun execute(command: EditorCommand) {
        surfaceController.send(NativeMessage.Command(command))
    }

    fun showSearch() {
        readingControlsJob?.cancel()
        mutableUiState.update { it.copy(searchVisible = true, readingControlsVisible = true) }
    }

    fun hideSearch() {
        mutableUiState.update {
            it.copy(searchVisible = false, searchQuery = "", searchCurrent = 0, searchTotal = 0)
        }
        if (surfaceReady) surfaceController.send(NativeMessage.SearchDocument("", SearchAction.RESET))
        scheduleReadingControlsHide()
    }

    fun updateSearchQuery(query: String) {
        val bounded = query.take(MAX_SEARCH_QUERY_LENGTH)
        mutableUiState.update { it.copy(searchQuery = bounded) }
        if (surfaceReady) surfaceController.send(NativeMessage.SearchDocument(bounded, SearchAction.RESET))
    }

    fun findNext() = search(SearchAction.NEXT)

    fun findPrevious() = search(SearchAction.PREVIOUS)

    fun showOutline(tab: OutlineTab = OutlineTab.Outline) {
        readingControlsJob?.cancel()
        mutableUiState.update {
            it.copy(outlineVisible = true, outlineTab = tab, readingControlsVisible = true)
        }
    }

    fun dismissOutline() {
        mutableUiState.update { it.copy(outlineVisible = false) }
        scheduleReadingControlsHide()
    }

    fun selectOutlineTab(tab: OutlineTab) {
        mutableUiState.update { it.copy(outlineTab = tab) }
    }

    fun navigateToHeading(headingId: String) {
        if (mutableUiState.value.outline.none { it.id == headingId }) return
        surfaceController.send(NativeMessage.NavigateToHeading(headingId))
        dismissOutline()
    }

    fun toggleHeadingBookmark(headingId: String) {
        val current = readingDocumentState ?: return
        val existing = current.bookmarks.firstOrNull { it.id == headingId }
        val bookmarks = if (existing != null) {
            current.bookmarks - existing
        } else {
            val heading = mutableUiState.value.outline.firstOrNull { it.id == headingId } ?: return
            if (current.bookmarks.size >= MAX_HEADING_BOOKMARKS) {
                mutableUiState.update { it.copy(error = "每份文档最多保存 200 个标题书签") }
                return
            }
            current.bookmarks + HeadingBookmark(heading.id, heading.title, heading.level, heading.sourceLine)
        }
        readingDocumentState = current.copy(bookmarks = bookmarks, updatedAt = clock())
        updateResolvedBookmarks()
        scheduleReadingStateSave(immediate = true)
        showReadingControls()
    }

    fun enterReadingMode() {
        mutableUiState.update {
            it.copy(
                readingMode = true,
                readingControlsVisible = true,
                searchVisible = false,
                outlineVisible = false,
            )
        }
        sendMode()
        scheduleReadingControlsHide()
    }

    fun exitReadingMode() {
        readingControlsJob?.cancel()
        val clearSearch = mutableUiState.value.searchVisible || mutableUiState.value.searchQuery.isNotEmpty()
        mutableUiState.update {
            it.copy(
                readingMode = false,
                readingControlsVisible = true,
                searchVisible = false,
                searchQuery = "",
                searchCurrent = 0,
                searchTotal = 0,
                outlineVisible = false,
            )
        }
        if (clearSearch && surfaceReady) {
            surfaceController.send(NativeMessage.SearchDocument("", SearchAction.RESET))
        }
        sendMode()
    }

    fun showReadingControls() {
        if (!mutableUiState.value.readingMode) return
        mutableUiState.update { it.copy(readingControlsVisible = true) }
        scheduleReadingControlsHide()
    }

    fun requestExit(onExit: () -> Unit) {
        val requested = mutableUiState.value.requestExit()
        mutableUiState.value = requested
        if (requested.canExitImmediately) {
            viewModelScope.launch {
                flushReadingState()
                requested.session?.let { saveCoordinator.onCleanClose(it) }
                onExit()
            }
        }
    }

    fun cancelExit() {
        mutableUiState.update { it.copy(pendingExit = false, canExitImmediately = false) }
    }

    fun saveAndExit(onExit: () -> Unit) {
        val session = mutableUiState.value.session ?: return onExit()
        viewModelScope.launch {
            saveCoordinator.flushForBackground(session.copy(autoSaveEnabled = true))
            flushReadingState()
            val latest = mutableUiState.value.session
            if (latest != null && !latest.isDirty && latest.externalConflict == null) {
                saveCoordinator.onCleanClose(latest)
                onExit()
            } else {
                mutableUiState.update {
                    it.copy(pendingExit = true, error = "保存未完成，请处理错误后重试")
                }
            }
        }
    }

    fun discardAndExit(onExit: () -> Unit) {
        val id = mutableUiState.value.session?.id
        saveCoordinator.cancel()
        splitRatioSaveJob?.cancel()
        viewModelScope.launch {
            flushReadingState()
            if (id != null) runCatching { recoveryStore.delete(id) }
            onExit()
        }
    }

    fun flushForBackground() {
        val session = mutableUiState.value.session
        viewModelScope.launch {
            if (session?.isDirty == true) saveCoordinator.flushForBackground(session)
            flushReadingState()
        }
    }

    fun saveNow() {
        val session = mutableUiState.value.session ?: return
        viewModelScope.launch {
            saveCoordinator.flushForBackground(session.copy(autoSaveEnabled = true))
        }
    }

    fun restoreRecovery() {
        val state = mutableUiState.value
        val draft = state.recoveryCandidate ?: return
        val session = state.session ?: return
        val restored = reducer.reduce(
            session,
            DocumentEvent.Changed(
                revision = session.revision + 1,
                text = draft.text,
                canUndo = false,
                canRedo = false,
            ),
        )
        mutableUiState.value = state.copy(session = restored, recoveryCandidate = null)
        saveCoordinator.onChanged(restored)
        if (surfaceReady) sendLoad()
    }

    fun dismissRecovery() {
        val id = mutableUiState.value.session?.id
        mutableUiState.update { it.copy(recoveryCandidate = null) }
        if (id != null) viewModelScope.launch { runCatching { recoveryStore.delete(id) } }
    }

    fun keepMine() = resolveConflict { session ->
        val saved = conflictResolver.keepMine(session)
        handleDocumentEvent(DocumentEvent.ResolvedWithMine(saved.fingerprint))
    }

    fun loadExternal() = resolveConflict { session ->
        when (val external = conflictResolver.loadExternal(session)) {
            is ExternalDocumentVersion.Present -> {
                handleDocumentEvent(
                    DocumentEvent.LoadedExternal(
                        session.revision + 1,
                        external.text,
                        external.fingerprint,
                    ),
                )
                sendLoad()
            }
            ExternalDocumentVersion.Deleted -> {
                mutableUiState.update { it.copy(error = "原文件已被删除，可选择保留我的版本") }
            }
        }
    }

    fun saveCopy() = resolveConflict { session ->
        val copy = conflictResolver.saveCopy(session)
        mutableUiState.update { it.copy(error = "已另存为 ${copy.file.name}") }
    }

    fun consumeExternalLink(): String? {
        val link = mutableUiState.value.externalLink
        mutableUiState.update { it.copy(externalLink = null) }
        return link
    }

    private fun resolveConflict(action: suspend (DocumentSession) -> Unit) {
        val session = mutableUiState.value.session ?: return
        viewModelScope.launch {
            try {
                action(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableUiState.update { it.copy(error = error.message ?: "冲突处理失败") }
            }
        }
    }

    private suspend fun loadDocument(autoSaveEnabled: Boolean) {
        try {
            val result = withContext(Dispatchers.IO) {
                val file = validateDocument(document.file)
                val text = file.readText(Charsets.UTF_8)
                val fingerprint = DocumentFingerprint.from(file)
                check(fingerprint.sha256 == DocumentFingerprint.sha256(text)) {
                    "文档读取过程中发生变化"
                }
                val session = DocumentSession(
                    id = requireSafeDocumentId(document.id),
                    file = file,
                    text = text,
                    revision = 0,
                    baseline = fingerprint,
                    saveStatus = SaveStatus.Saved,
                    canUndo = false,
                    canRedo = false,
                    autoSaveEnabled = autoSaveEnabled,
                )
                val legacyId = editorDocumentFor(file, paths.root).id
                val reading = if (legacyId == session.id) {
                    readingStateRepository.get(session.id)
                } else {
                    readingStateRepository.migrate(legacyId, session.id)
                        ?: readingStateRepository.get(session.id)
                } ?: ReadingDocumentState(documentId = session.id)
                Triple(session, recoveryStore.candidate(session.id, file), reading)
            }
            readingDocumentState = result.third
            mutableUiState.update {
                it.copy(
                    session = result.first,
                    recoveryCandidate = result.second,
                    bookmarks = reconcileHeadingBookmarks(result.third.bookmarks, it.outline),
                    loading = false,
                )
            }
            if (surfaceReady) sendLoad()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableUiState.update {
                it.copy(loading = false, error = error.message ?: "无法打开文档")
            }
        }
    }

    private fun validateDocument(file: File): File {
        val root = paths.root.toPath().toAbsolutePath().normalize()
        val system = paths.system.toPath().toAbsolutePath().normalize()
        val candidate = file.toPath().toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(root)) { "资料库根目录不能是符号链接" }
        require(candidate.startsWith(root) && candidate != root && !candidate.startsWith(system)) {
            "只能打开墨笺资料库中的文档"
        }
        var current = root
        root.relativize(candidate).forEach { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "不允许打开符号链接文档" }
        }
        require(Files.isRegularFile(candidate, NOFOLLOW_LINKS)) { "文档不存在或不可读取" }
        require(candidate.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("md", "markdown")) {
            "当前编辑器仅支持 Markdown 文档"
        }
        require(Files.size(candidate) <= MAX_DOCUMENT_BYTES) { "文档超过 64 MiB 上限" }
        return candidate.toFile()
    }

    private fun handleWebMessage(message: WebMessage) {
        when (message) {
            WebMessage.Ready -> {
                surfaceReady = true
                sendLoad()
                setSplitRatio(mutableUiState.value.splitRatio)
                surfaceController.send(NativeMessage.SetFontSize(editorFontSize.pixels))
            }
            is WebMessage.Changed -> {
                val session = mutableUiState.value.session ?: return
                val changed = reducer.reduce(
                    session,
                    DocumentEvent.Changed(message.revision, message.text, message.canUndo, message.canRedo),
                )
                if (changed == session) return
                mutableUiState.update { it.copy(session = changed) }
                saveCoordinator.onChanged(changed)
            }
            is WebMessage.ExternalLink -> mutableUiState.update { it.copy(externalLink = message.href) }
            is WebMessage.OutlineChanged -> {
                mutableUiState.update { it.copy(outline = message.headings) }
                val current = readingDocumentState
                if (current != null) {
                    val resolved = reconcileHeadingBookmarks(current.bookmarks, message.headings)
                    val reconciled = resolved.map(ResolvedHeadingBookmark::bookmark)
                    if (reconciled != current.bookmarks) {
                        readingDocumentState = current.copy(bookmarks = reconciled, updatedAt = clock())
                        scheduleReadingStateSave(immediate = true)
                    }
                    mutableUiState.update { it.copy(bookmarks = resolved) }
                }
            }
            is WebMessage.SearchResult -> mutableUiState.update {
                it.copy(searchCurrent = message.current, searchTotal = message.total)
            }
            is WebMessage.ActiveHeadingChanged -> mutableUiState.update {
                it.copy(activeHeadingId = message.headingId)
            }
            is WebMessage.ReadingPositionChanged -> updateReadingPosition(message)
            WebMessage.PreviewTapped -> showReadingControls()
            is WebMessage.RenderError -> mutableUiState.update {
                it.copy(renderWarning = "${message.block}：${message.message}")
            }
        }
    }

    private fun handleDocumentEvent(event: DocumentEvent) {
        mutableUiState.update { state ->
            val session = state.session ?: return@update state
            state.copy(session = reducer.reduce(session, event))
        }
    }

    private fun sendMode() {
        if (surfaceReady) surfaceController.send(NativeMessage.SetMode(mutableUiState.value.editorMode(isLandscape)))
    }

    private fun sendLoad() {
        if (!surfaceReady) return
        val state = mutableUiState.value
        val session = state.session ?: return
        surfaceController.send(
            NativeMessage.Load(session.revision, session.text, state.editorMode(isLandscape), theme),
        )
        if (!readingPositionRestored) {
            readingDocumentState?.position?.let { position ->
                surfaceController.send(
                    NativeMessage.RestoreReadingPosition(
                        position.editorLine,
                        position.editorColumn,
                        position.editorProgress,
                        position.previewHeadingId,
                        position.previewProgress,
                    ),
                )
                readingPositionRestored = true
            }
        }
    }

    private fun search(action: SearchAction) {
        if (surfaceReady) {
            surfaceController.send(NativeMessage.SearchDocument(mutableUiState.value.searchQuery, action))
        }
    }

    private fun updateReadingPosition(message: WebMessage.ReadingPositionChanged) {
        val current = readingDocumentState ?: return
        readingDocumentState = current.copy(
            position = ReadingPosition(
                message.editorLine,
                message.editorColumn,
                message.editorProgress,
                message.previewHeadingId,
                message.previewProgress,
            ),
            updatedAt = clock(),
        )
        scheduleReadingStateSave(immediate = false)
    }

    private fun updateResolvedBookmarks() {
        val current = readingDocumentState ?: return
        mutableUiState.update {
            it.copy(bookmarks = reconcileHeadingBookmarks(current.bookmarks, it.outline))
        }
    }

    private fun scheduleReadingStateSave(immediate: Boolean) {
        readingStateSaveJob?.cancel()
        readingStateSaveJob = viewModelScope.launch {
            if (!immediate) delay(READING_STATE_SAVE_DELAY_MILLIS)
            persistReadingState()
        }
    }

    private suspend fun flushReadingState() {
        readingStateSaveJob?.cancel()
        readingStateSaveJob = null
        persistReadingState()
    }

    private suspend fun persistReadingState() {
        val state = readingDocumentState ?: return
        try {
            readingStateRepository.replace(state)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableUiState.update { it.copy(error = error.message ?: "阅读位置保存失败") }
        }
    }

    private fun scheduleReadingControlsHide() {
        readingControlsJob?.cancel()
        val state = mutableUiState.value
        if (!state.readingMode || state.searchVisible || state.outlineVisible) return
        readingControlsJob = viewModelScope.launch {
            delay(READING_CONTROLS_HIDE_DELAY_MILLIS)
            mutableUiState.update { current ->
                if (current.readingMode && !current.searchVisible && !current.outlineVisible) {
                    current.copy(readingControlsVisible = false)
                } else current
            }
        }
    }

    override fun onCleared() {
        saveCoordinator.cancel()
        readingStateSaveJob?.cancel()
        readingControlsJob?.cancel()
        surfaceController.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 64L * 1024 * 1024
        const val SPLIT_RATIO_SAVE_DELAY_MILLIS = 250L
        const val READING_STATE_SAVE_DELAY_MILLIS = 750L
        const val READING_CONTROLS_HIDE_DELAY_MILLIS = 3_000L
        const val MAX_SEARCH_QUERY_LENGTH = 256
        const val MAX_HEADING_BOOKMARKS = 200
    }
}

class EditorViewModelFactory(private val create: () -> EditorViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
