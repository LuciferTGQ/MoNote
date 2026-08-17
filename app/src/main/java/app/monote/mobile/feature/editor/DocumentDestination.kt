package app.monote.mobile.feature.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.monote.mobile.LibraryServices
import app.monote.mobile.feature.settings.AppSettingsStore

@Composable
fun DocumentDestination(
    document: EditorDocument,
    services: LibraryServices,
    settings: DataStore<Preferences>,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val model: EditorViewModel = viewModel(
        key = document.id,
        factory = EditorViewModelFactory {
            EditorViewModel(
                document = document,
                paths = services.paths,
                recoveryStore = services.recoveryStore,
                orientationStore = OrientationPreferenceStore(settings),
                appSettingsStore = AppSettingsStore(settings),
                readingStateRepository = services.readingStateRepository,
            )
        },
    )
    val state by model.uiState.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    LaunchedEffect(isLandscape) { model.setLandscape(isLandscape) }
    LaunchedEffect(darkTheme) { model.setTheme(darkTheme) }
    LaunchedEffect(activity, state.orientationPreference) {
        activity?.let { applyOrientationPreference(it, state.orientationPreference) }
    }
    LaunchedEffect(activity, state.readingMode) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (state.readingMode) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }
    LaunchedEffect(state.externalLink) {
        val link = state.externalLink ?: return@LaunchedEffect
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }
        model.consumeExternalLink()
    }
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.flushForBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler {
        if (state.readingMode) model.exitReadingMode() else model.requestExit(onExit)
    }
    DocumentScreen(
        state = state,
        isLandscape = isLandscape,
        onBack = { model.requestExit(onExit) },
        onSave = model::saveNow,
        onSelectTab = model::selectTab,
        onCommand = model::execute,
        onOrientation = model::setOrientation,
        onSplitRatio = model::setSplitRatio,
        onSaveAndExit = { model.saveAndExit(onExit) },
        onDiscardAndExit = { model.discardAndExit(onExit) },
        onCancelExit = model::cancelExit,
        onRestoreRecovery = model::restoreRecovery,
        onDismissRecovery = model::dismissRecovery,
        onKeepMine = model::keepMine,
        onLoadExternal = model::loadExternal,
        onSaveCopy = model::saveCopy,
        onShowSearch = model::showSearch,
        onHideSearch = model::hideSearch,
        onSearchQuery = model::updateSearchQuery,
        onFindPrevious = model::findPrevious,
        onFindNext = model::findNext,
        onShowOutline = model::showOutline,
        onDismissOutline = model::dismissOutline,
        onSelectOutlineTab = model::selectOutlineTab,
        onNavigateToHeading = model::navigateToHeading,
        onToggleBookmark = model::toggleHeadingBookmark,
        onEnterReading = model::enterReadingMode,
        onExitReading = model::exitReadingMode,
        surface = { modifier ->
            DocumentSurface(
                controller = model.surfaceController,
                libraryRoot = services.paths.root,
                modifier = modifier,
            )
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
