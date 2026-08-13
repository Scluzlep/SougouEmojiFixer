package com.king250.sougou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.king250.sougou.ui.navigation.EmojiFixerApp
import com.king250.sougou.ui.theme.SougouInputEmojiFixerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PatchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val state by viewModel.state.collectAsState()
            val configuration by viewModel.configuration.collectAsState()
            val versions by viewModel.versions.collectAsState()
            val versionsLoading by viewModel.versionsLoading.collectAsState()
            val versionsMessage by viewModel.versionsMessage.collectAsState()
            val logs by viewModel.logs.collectAsState()
            SougouInputEmojiFixerTheme {
                EmojiFixerApp(
                    state = state,
                    configuration = configuration,
                    versions = versions,
                    versionsLoading = versionsLoading,
                    versionsMessage = versionsMessage,
                    logs = logs,
                    onInstall = viewModel::install,
                    onRefreshVersions = viewModel::refreshVersions,
                    onSelectVersion = viewModel::selectVersion,
                    onMoveGroup = viewModel::moveGroup,
                    onResetGroupOrder = viewModel::resetGroupOrder,
                    onApplyOrder = viewModel::applyOrder,
                    onOrderEditing = viewModel::setOrderEditing,
                    onRefreshOrder = viewModel::refreshOrder,
                    onIncludeNonFullyQualifiedChange =
                        viewModel::setIncludeNonFullyQualified,
                    onIncludeUnsupportedSystemEmojiChange =
                        viewModel::setIncludeUnsupportedSystemEmoji,
                    onNotoDownloadSourceChange = viewModel::setNotoDownloadSource,
                    onClearLogs = viewModel::clearLogs,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.start()
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }
}
