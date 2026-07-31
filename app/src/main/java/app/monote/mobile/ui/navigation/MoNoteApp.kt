package app.monote.mobile.ui.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import app.monote.mobile.feature.onboarding.PermissionScreen
import app.monote.mobile.feature.onboarding.PermissionViewModel
import app.monote.mobile.ui.SplashGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MoNoteApp(
    appContainer: AppContainer,
    permissionViewModel: PermissionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val granted by permissionViewModel.granted.collectAsStateWithLifecycle()

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
                    withContext(Dispatchers.IO) { appContainer.libraryServices() }
                },
                onPermissionLost = permissionViewModel::refresh,
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
            currentPermissionLost()
        } catch (_: Exception) {
            state = InitializationState.Failed
        }
    }

    when (state) {
        InitializationState.Loading -> InitializationLoading()
        InitializationState.Ready -> NavigationShell()
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
private fun NavigationShell() {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentPath = currentEntry?.destination?.route

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
            Route.entries.forEach { route ->
                composable(route.path) {
                    PlaceholderDestination(route)
                }
            }
        }
    }
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
