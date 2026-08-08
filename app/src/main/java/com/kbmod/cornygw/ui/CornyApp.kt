package com.kbmod.cornygw.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kbmod.cornygw.ui.screens.HuntScreen
import com.kbmod.cornygw.ui.screens.NetworksScreen
import com.kbmod.cornygw.ui.screens.SettingsScreen
import com.kbmod.cornygw.ui.screens.SurveyScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    NETWORKS("Networks", Icons.Filled.Wifi),
    HUNT("Hunt", Icons.Filled.Sensors),
    SURVEY("Survey", Icons.Filled.MyLocation),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CornyApp(viewModel: HunterViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.NETWORKS) }

    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val huntState by viewModel.huntState.collectAsStateWithLifecycle()
    val surveyState by viewModel.surveyState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Scanning, GPS and the compass are all battery-expensive and pointless
    // while the app is not on screen, so they follow the started lifecycle.
    LifecycleStartEffect(Unit) {
        viewModel.startStreams()
        onStopOrDispose { viewModel.stopStreams() }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (tab) {
                            Tab.NETWORKS -> "Networks nearby"
                            Tab.HUNT -> "Hunt"
                            Tab.SURVEY -> "Survey"
                            Tab.SETTINGS -> "Settings"
                        },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
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
        val contentPadding = PaddingValues(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
        )

        when (tab) {
            Tab.NETWORKS -> NetworksScreen(
                state = scanState,
                huntState = huntState,
                onQueryChange = viewModel::setQuery,
                onSelect = { sighting ->
                    viewModel.selectTarget(sighting)
                    tab = Tab.HUNT
                },
                contentPadding = contentPadding,
            )

            Tab.HUNT -> HuntScreen(
                huntState = huntState,
                scanState = scanState,
                settings = settings,
                onRescan = viewModel::requestScanNow,
                onClearTarget = {
                    viewModel.clearTarget()
                    tab = Tab.NETWORKS
                },
                onGoToNetworks = { tab = Tab.NETWORKS },
                contentPadding = contentPadding,
            )

            Tab.SURVEY -> SurveyScreen(
                surveyState = surveyState,
                huntState = huntState,
                onToggleRecording = viewModel::toggleRecording,
                onSave = { viewModel.saveSurvey() },
                onClear = viewModel::clearSurvey,
                onRecompute = viewModel::recomputeNow,
                onLoadSurvey = viewModel::loadSurvey,
                onDeleteSurvey = viewModel::deleteSurvey,
                onShareSurvey = viewModel::shareSurvey,
                onDismissMessage = viewModel::dismissMessage,
                onGoToNetworks = { tab = Tab.NETWORKS },
                contentPadding = contentPadding,
            )

            Tab.SETTINGS -> SettingsScreen(
                settings = settings,
                onUpdate = viewModel::updateSettings,
                contentPadding = contentPadding,
            )
        }
    }
}
