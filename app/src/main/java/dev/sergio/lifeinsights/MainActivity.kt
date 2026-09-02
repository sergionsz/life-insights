package dev.sergio.lifeinsights

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sergio.lifeinsights.ui.checkin.CheckInScreen
import dev.sergio.lifeinsights.ui.checkin.CheckInViewModel
import dev.sergio.lifeinsights.ui.insights.InsightsScreen
import dev.sergio.lifeinsights.ui.insights.InsightsViewModel
import dev.sergio.lifeinsights.ui.settings.SettingsScreen
import dev.sergio.lifeinsights.ui.settings.SettingsViewModel
import dev.sergio.lifeinsights.ui.theme.LifeInsightsTheme
import dev.sergio.lifeinsights.ui.trends.TrendsScreen
import dev.sergio.lifeinsights.ui.trends.TrendsViewModel

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Default.Today),
    TRENDS("Trends", Icons.Default.ShowChart),
    INSIGHTS("Insights", Icons.Default.Insights),
    SETTINGS("Settings", Icons.Default.Settings),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LifeInsightsApp

        setContent {
            LifeInsightsTheme {
                RequestNotificationPermissionOnce()

                // The check-in is the landing screen: the whole app depends on that taking seconds.
                var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }

                val factory = rememberViewModelFactory(app)

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { entry ->
                                NavigationBarItem(
                                    selected = tab == entry,
                                    onClick = { tab = entry },
                                    icon = { Icon(entry.icon, contentDescription = entry.label) },
                                    label = { Text(entry.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val contentModifier = Modifier.padding(padding)
                    when (tab) {
                        Tab.TODAY -> CheckInScreen(
                            viewModel = viewModel(factory = factory),
                            modifier = contentModifier,
                        )

                        Tab.TRENDS -> {
                            val vm: TrendsViewModel = viewModel(factory = factory)
                            LaunchedEffect(Unit) { vm.refresh() }
                            TrendsScreen(vm, contentModifier)
                        }

                        Tab.INSIGHTS -> {
                            val vm: InsightsViewModel = viewModel(factory = factory)
                            LaunchedEffect(Unit) { vm.refresh() }
                            InsightsScreen(vm, contentModifier)
                        }

                        Tab.SETTINGS -> SettingsScreen(
                            viewModel = viewModel(factory = factory),
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberViewModelFactory(app: LifeInsightsApp): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { CheckInViewModel(app.repository, app.usageStatsSource) }
        initializer { TrendsViewModel(app.repository) }
        initializer { InsightsViewModel(app.repository, app.insightsEngine) }
        initializer {
            SettingsViewModel(
                app.repository, app.settings, app.exporter, app.database,
                app.usageStatsSource, app.usageRepository, app.syncEngine,
            )
        }
    }

@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
