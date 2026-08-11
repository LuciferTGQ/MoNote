package app.monote.mobile.ui.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.monote.mobile.AppContainer
import app.monote.mobile.LibraryServices
import app.monote.mobile.feature.importing.ContentUriDocumentSource
import app.monote.mobile.feature.importing.IncomingRequest
import app.monote.mobile.feature.library.ClearableStorageCategory
import app.monote.mobile.feature.library.ImportSheet
import app.monote.mobile.feature.library.LibraryScreen
import app.monote.mobile.feature.library.LibraryViewModel
import app.monote.mobile.feature.library.LibraryViewModelFactory
import app.monote.mobile.feature.library.StorageScreen
import app.monote.mobile.feature.library.TrashScreen
import app.monote.mobile.feature.library.restoreResultMessage
import app.monote.mobile.feature.library.deleteResultMessage
import app.monote.mobile.feature.onboarding.PermissionScreen
import app.monote.mobile.feature.onboarding.PermissionViewModel
import app.monote.mobile.ui.SplashGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

@Composable
fun MoNoteApp(
    appContainer: AppContainer,
    permissionViewModel: PermissionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val granted by permissionViewModel.granted.collectAsStateWithLifecycle()
    var services by remember { mutableStateOf<LibraryServices?>(null) }

    DisposableEffect(lifecycleOwner, permissionViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionViewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SplashGate {
        if (granted) {
            AuthorizedShell(
                initializeLibrary = {
                    services = withContext(Dispatchers.IO) { appContainer.libraryServices() }
                },
                onPermissionLost = permissionViewModel::refresh,
                readyContent = {
                    services?.let {
                        NavigationShell(
                            services = it,
                            incomingRequests = appContainer.incomingRequests,
                            acknowledgeIncoming = appContainer::acknowledgeIncomingRequest,
                        )
                    } ?: InitializationLoading()
                },
            )
        } else {
            PermissionScreen(
                granted = false,
                onRequest = { launchAllFilesAccessSettings(context) },
            )
        }
    }
}

@Composable
internal fun AuthorizedShell(
    initializeLibrary: suspend () -> Unit,
    onPermissionLost: () -> Unit,
    readyContent: @Composable () -> Unit = { NavigationShell() },
) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(InitializationState.Loading) }
    val currentInitializer by rememberUpdatedState(initializeLibrary)
    val currentPermissionLost by rememberUpdatedState(onPermissionLost)

    LaunchedEffect(attempt) {
        state = InitializationState.Loading
        try {
            currentInitializer()
            state = InitializationState.Ready
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            state = InitializationState.Failed
            currentPermissionLost()
        } catch (_: Exception) {
            state = InitializationState.Failed
        }
    }

    when (state) {
        InitializationState.Loading -> InitializationLoading()
        InitializationState.Ready -> readyContent()
        InitializationState.Failed -> InitializationError(onRetry = { attempt += 1 })
    }
}

@Composable
private fun InitializationLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("正在准备资料库")
    }
}

@Composable
private fun InitializationError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "资料库初始化失败",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "请检查存储空间后重试。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}

internal fun launchAllFilesAccessSettings(
    context: Context,
    launcher: (Intent) -> Unit = { intent -> context.startActivity(intent) },
) {
    val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        launcher(appSettings)
    } catch (_: ActivityNotFoundException) {
        val generalSettings = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launcher(generalSettings)
    }
}

@Composable
private fun NavigationShell(
    services: LibraryServices? = null,
    incomingRequests: Flow<IncomingRequest> = emptyFlow(),
    acknowledgeIncoming: (String) -> Unit = {},
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentPath = currentEntry?.destination?.route
    var incomingRequest by remember { mutableStateOf<IncomingRequest?>(null) }

    LaunchedEffect(incomingRequests) {
        incomingRequests.collect { request ->
            incomingRequest = request
            if (navController.currentDestination?.route != Route.Library.path) {
                navController.navigate(Route.Library.path) {
                    launchSingleTop = true
                    popUpTo(Route.Library.path)
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Route.entries.forEach { route ->
                    NavigationBarItem(
                        selected = currentPath == route.path,
                        onClick = {
                            navController.navigate(route.path) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(Route.Library.path) { saveState = true }
                            }
                        },
                        icon = { Text(route.mark) },
                        label = { Text(route.title) },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.Library.path,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.Library.path) {
                if (services == null) PlaceholderDestination(Route.Library) else Box(Modifier.fillMaxSize().testTag("route-library")) {
                    LibraryDestination(
                        services = services,
                        incomingRequest = incomingRequest,
                        onIncomingConsumed = { id ->
                            acknowledgeIncoming(id)
                            if (incomingRequest?.id == id) incomingRequest = null
                        },
                        onOpenEditor = { navController.navigate(Route.Editor.path) },
                        onOpenStorage = { navController.navigate(Route.Storage.path) },
                        onOpenSettings = { navController.navigate(Route.Settings.path) },
                    )
                }
            }
            composable(Route.Trash.path) {
                if (services == null) PlaceholderDestination(Route.Trash) else TrashDestination(services)
            }
            composable(Route.Storage.path) {
                if (services == null) PlaceholderDestination(Route.Storage) else StorageDestination(services)
            }
            composable(Route.Editor.path) { PlaceholderDestination(Route.Editor) }
            composable(Route.Settings.path) { PlaceholderDestination(Route.Settings) }
        }
    }
}

@Composable
private fun LibraryDestination(
    services: LibraryServices,
    incomingRequest: IncomingRequest?,
    onIncomingConsumed: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val model: LibraryViewModel = viewModel(factory = LibraryViewModelFactory {
        LibraryViewModel(services.paths, services.catalogRepository, services.libraryIndexer, services.libraryService, services.importCoordinator, services.trashRepository, services.folderImportCoordinator, services.directoryMetadataRepository, services.moveRecoveryRepository)
    })
    val state by model.uiState.collectAsStateWithLifecycle()
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedUriMetadata by remember { mutableStateOf<List<ResolvedUriMetadata>>(emptyList()) }
    var selectedTree by remember { mutableStateOf<Uri?>(null) }
    var selectedTreeMetadata by remember { mutableStateOf<ResolvedUriMetadata?>(null) }
    var folderImporting by remember { mutableStateOf(false) }
    var incomingImporting by remember { mutableStateOf(false) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedTree = null
        selectedTreeMetadata = null
        selectedUris = uris
        selectedUriMetadata = emptyList()
    }
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        selectedUris = emptyList()
        selectedUriMetadata = emptyList()
        selectedTree = uri
        selectedTreeMetadata = null
        folderImporting = false
        model.clearError()
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val source = exportFile
        exportFile = null
        if (uri != null && source != null) scope.launch {
            try {
                exportMarkdown(source) { context.contentResolver.openOutputStream(uri) }
            } catch (failure: Exception) {
                model.reportError("导出失败：${failure.message ?: "未知错误"}")
            }
        }
    }
    LaunchedEffect(selectedUris) {
        selectedUriMetadata = if (selectedUris.isEmpty()) emptyList()
        else selectedUris.map { resolveUriMetadata(context, it) }
    }
    LaunchedEffect(selectedTree) {
        selectedTreeMetadata = selectedTree?.let { resolveUriMetadata(context, it) }
    }
    LaunchedEffect(incomingRequest?.id) {
        val request = incomingRequest ?: return@LaunchedEffect
        selectedUris = emptyList()
        selectedUriMetadata = emptyList()
        selectedTree = null
        selectedTreeMetadata = null
        incomingImporting = false
        request.error?.let { failure ->
            model.reportError("无法导入：$failure")
            onIncomingConsumed(request.id)
        }
        if (request.error == null) model.clearError()
        if (request.error == null && request.documents.isEmpty()) {
            model.reportError("无法导入：没有可读取的 Markdown 文件")
            onIncomingConsumed(request.id)
        }
    }
    LaunchedEffect(state.error, incomingRequest?.id) {
        if (incomingRequest != null && state.error != null) incomingImporting = false
    }
    BackHandler(enabled = state.canNavigateUp) { model.goToParent() }
    LibraryScreen(
        state = state,
        onQueryChange = model::updateQuery,
        onOpenItem = { if (it.isFolder) model.openFolder(it.file) else onOpenEditor() },
        onOpenStorage = onOpenStorage,
        onOpenSettings = onOpenSettings,
        onNavigateUp = model::goToParent,
        onImportDocuments = { importFiles.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream")) },
        onImportFolder = { importFolder.launch(null) },
        onCreateNote = model::createNote,
        onCreateFolder = model::createFolder,
        onSelectionChange = model::updateSelection,
        onFavorite = model::toggleFavorite,
        onTags = model::setTags,
        onMove = { _, destination -> model.moveSelected(destination) },
        onRename = model::renameSelected,
        onExport = { ids ->
            state.items.singleOrNull { it.id in ids && !it.isFolder }?.let { file ->
                exportFile = file.file
                export.launch(file.displayName)
            }
        },
        onDelete = { model.deleteSelected() },
        onRecoverMove = model::recoverMove,
    )
    incomingRequest?.takeIf { it.error == null && it.documents.isNotEmpty() }?.let { request ->
        ImportSheet(
            selectedNames = request.documents.map { it.displayName },
            targetDirectory = "MoNote/收件箱",
            warning = "文件来自其他应用，将复制到墨笺后再打开；原文件不会被修改。",
            error = state.error,
            confirming = incomingImporting,
            onDismiss = { if (!incomingImporting) onIncomingConsumed(request.id) },
            onConfirm = {
                incomingImporting = true
                model.importIncomingDocuments(request.documents) {
                    incomingImporting = false
                    onIncomingConsumed(request.id)
                    onOpenEditor()
                }
            },
        )
    }
    if (incomingRequest == null && selectedUris.isNotEmpty() && selectedUriMetadata.size == selectedUris.size) {
        ImportSheet(selectedUriMetadata.map { it.displayName }, state.currentFolder.relativeTo(services.paths.root).path.ifBlank { "MoNote" }, onDismiss = {
            selectedUris = emptyList()
            selectedUriMetadata = emptyList()
        }) {
            val sources = selectedUris.zip(selectedUriMetadata).map { (uri, metadata) ->
                ContentUriDocumentSource(context.contentResolver, uri, metadata.displayName, metadata.mimeType)
            }
            selectedUris = emptyList()
            selectedUriMetadata = emptyList()
            model.importDocuments(sources) { onOpenEditor() }
        }
    }
    selectedTree?.let { tree -> selectedTreeMetadata?.let { treeMetadata ->
        LaunchedEffect(state.error) {
            if (state.error != null) folderImporting = false
        }
        ImportSheet(
            selectedNames = listOf(treeMetadata.displayName),
            targetDirectory = state.currentFolder.relativeTo(services.paths.root).path.ifBlank { "MoNote" },
            warning = "将安全复制包含 Markdown 的目录结构和附件；超出大小或深度限制时会停止并清理。",
            isFolder = true,
            error = state.error,
            confirming = folderImporting,
            onDismiss = { if (!folderImporting) selectedTree = null },
            onConfirm = {
                folderImporting = true
                model.importFolder(ContentResolverTreeDocumentSource(context, tree, treeMetadata.displayName)) {
                    folderImporting = false
                    selectedTree = null
                    onOpenEditor()
                }
            },
        )
    } }
}

@Composable
private fun TrashDestination(services: LibraryServices) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<app.monote.mobile.feature.library.TrashEntry>()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() { scope.launch { runCatching { services.trashRepository.listEntries() }.onSuccess { entries = it }.onFailure { error = it.message } } }
    LaunchedEffect(services) { refresh() }
    TrashScreen(
        entries,
        onRestore = { entry -> scope.launch {
            error = try { restoreResultMessage(services.trashRepository.restore(entry)) } catch (failure: Exception) { "恢复失败：${failure.message ?: "未知错误"}" }
            refresh()
        } },
        onDeletePermanently = { id -> scope.launch {
            error = try { deleteResultMessage(services.trashRepository.deletePermanently(id, true), "永久删除") } catch (failure: Exception) { "永久删除失败：${failure.message ?: "未知错误"}" }
            refresh()
        } },
        onEmptyTrash = { scope.launch {
            error = try { deleteResultMessage(services.trashRepository.emptyTrash(true), "清空回收站") } catch (failure: Exception) { "清空回收站失败：${failure.message ?: "未知错误"}" }
            refresh()
        } },
        error = error,
    )
}

@Composable
private fun StorageDestination(services: LibraryServices) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var breakdown by remember { mutableStateOf<app.monote.mobile.feature.library.StorageBreakdown?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun measure() {
        scope.launch {
            try {
                breakdown = withContext(Dispatchers.IO) { services.storageInspector.measure() }
                error = null
            } catch (failure: Exception) {
                error = "存储统计失败：${failure.message ?: "未知错误"}"
            }
        }
    }
    LaunchedEffect(services) { measure() }
    StorageScreen(
        breakdown = breakdown,
        onOpenRoot = { launchLibraryRoot(context) },
        onClear = { category ->
            scope.launch {
                try {
                    breakdown = withContext(Dispatchers.IO) {
                        clearDirectoryContents(
                            if (category == ClearableStorageCategory.RecoveryDrafts) services.paths.recovery
                            else context.cacheDir.resolve("renderer"),
                        )
                        services.storageInspector.measure()
                    }
                    error = null
                } catch (failure: Exception) {
                    error = "清理失败：${failure.message ?: "未知错误"}"
                }
            }
        },
        error = error,
    )
}

private fun clearDirectoryContents(directory: File) {
    val root = directory.toPath().toAbsolutePath().normalize()
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult { if (!Files.isSymbolicLink(file)) Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult { if (dir != root && error == null && !Files.isSymbolicLink(dir)) Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
    })
}

@Composable
private fun PlaceholderDestination(route: Route) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("route-${route.path}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = route.title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "功能即将加入",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private enum class InitializationState {
    Loading,
    Ready,
    Failed,
}
