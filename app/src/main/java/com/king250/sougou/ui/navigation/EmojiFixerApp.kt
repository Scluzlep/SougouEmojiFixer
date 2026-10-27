package com.king250.sougou.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.king250.sougou.R
import com.king250.sougou.patch.NotoDownloadSource
import com.king250.sougou.patch.PatchConfiguration
import com.king250.sougou.patch.PatchUiState
import com.king250.sougou.patch.RunLogEntry
import com.king250.sougou.patch.UnicodeVersionOption
import com.king250.sougou.ui.page.AboutPage
import com.king250.sougou.ui.page.GroupOrderPage
import com.king250.sougou.ui.page.HomePage
import com.king250.sougou.ui.page.LogPage
import com.king250.sougou.ui.page.SettingsPage

private enum class AppDestination(
    val label: String,
    val title: String,
    val icon: ImageVector,
) {
    HOME("首页", "", Icons.Filled.Home),
    ORDER("排序", "类种排序", Icons.Filled.SwapVert),
    SETTINGS("设置", "设置", Icons.Filled.Settings),
    LOGS("日志", "运行日志", Icons.AutoMirrored.Filled.Article),
    ABOUT("关于", "关于", Icons.Filled.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiFixerApp(
    state: PatchUiState,
    configuration: PatchConfiguration,
    versions: List<UnicodeVersionOption>,
    versionsLoading: Boolean,
    versionsMessage: String,
    logs: List<RunLogEntry>,
    onInstall: () -> Unit,
    onImportArchive: () -> Unit,
    archiveMessage: String,
    archiveBusy: Boolean,
    onRefreshVersions: () -> Unit,
    onSelectVersion: (UnicodeVersionOption) -> Unit,
    onMoveGroup: (Int, Int) -> Unit,
    onResetGroupOrder: () -> Unit,
    onApplyOrder: (List<Int>) -> Unit,
    onOrderEditing: (Boolean) -> Unit,
    onRefreshOrder: () -> Unit,
    onIncludeNonFullyQualifiedChange: (Boolean) -> Unit,
    onIncludeUnsupportedSystemEmojiChange: (Boolean) -> Unit,
    onNotoDownloadSourceChange: (NotoDownloadSource) -> Unit,
    onClearLogs: () -> Unit,
) {
    var destination by remember { mutableStateOf(AppDestination.HOME) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (destination == AppDestination.HOME) {
                            stringResource(R.string.app_name)
                        } else {
                            destination.title
                        },
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    val enter = slideInHorizontally(
                        animationSpec = tween(durationMillis = 280),
                        initialOffsetX = { width -> direction * width },
                    ) + fadeIn(animationSpec = tween(durationMillis = 180))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(durationMillis = 280),
                        targetOffsetX = { width -> -direction * width },
                    ) + fadeOut(animationSpec = tween(durationMillis = 140))
                    enter togetherWith exit
                },
                label = "navigation page",
            ) { currentDestination ->
                when (currentDestination) {
                    AppDestination.HOME -> HomePage(
                        state = state,
                        configuration = configuration,
                        versions = versions,
                        versionsLoading = versionsLoading,
                        versionsMessage = versionsMessage,
                        onInstall = onInstall,
                        onImportArchive = onImportArchive,
                        archiveMessage = archiveMessage,
                        archiveBusy = archiveBusy,
                        onRefreshVersions = onRefreshVersions,
                        onSelectVersion = onSelectVersion,
                    )

                    AppDestination.ORDER -> GroupOrderPage(
                        configuration = configuration,
                        state = state,
                        onMoveGroup = onMoveGroup,
                        onReset = onResetGroupOrder,
                        onApply = onApplyOrder,
                        onOrderEditing = onOrderEditing,
                        onRefreshOrder = onRefreshOrder,
                    )

                    AppDestination.SETTINGS -> SettingsPage(
                        configuration = configuration,
                        onIncludeNonFullyQualifiedChange =
                            onIncludeNonFullyQualifiedChange,
                        onIncludeUnsupportedSystemEmojiChange =
                            onIncludeUnsupportedSystemEmojiChange,
                        onNotoDownloadSourceChange = onNotoDownloadSourceChange,
                    )

                    AppDestination.LOGS -> LogPage(
                        logs = logs,
                        onClearLogs = onClearLogs,
                    )

                    AppDestination.ABOUT -> AboutPage()
                }
            }
        }
    }
}
