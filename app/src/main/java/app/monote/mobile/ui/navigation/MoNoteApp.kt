package app.monote.mobile.ui.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.monote.mobile.AppContainer
import app.monote.mobile.feature.onboarding.PermissionScreen
import app.monote.mobile.feature.onboarding.PermissionViewModel
import app.monote.mobile.ui.SplashOverlay
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

    LaunchedEffect(granted, appContainer) {
        if (granted) {
            withContext(Dispatchers.IO) { appContainer.libraryServices() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            NavigationShell()
        } else {
            PermissionScreen(
                granted = false,
                onRequest = { openAllFilesAccessSettings(context) },
            )
        }
        SplashOverlay()
    }
}

private fun openAllFilesAccessSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
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
        modifier = Modifier.fillMaxSize(),
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
