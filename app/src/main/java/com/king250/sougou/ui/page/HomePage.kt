package com.king250.sougou.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.king250.sougou.patch.EmojiCatalog
import com.king250.sougou.patch.PatchConfiguration
import com.king250.sougou.patch.PatchPhase
import com.king250.sougou.patch.PatchUiState
import com.king250.sougou.patch.UnicodeVersionOption
import com.king250.sougou.ui.component.InfoRow

@Composable
fun HomePage(
    state: PatchUiState,
    configuration: PatchConfiguration,
    versions: List<UnicodeVersionOption>,
    versionsLoading: Boolean,
    versionsMessage: String,
    onInstall: () -> Unit,
    onImportArchive: () -> Unit,
    archiveMessage: String,
    archiveBusy: Boolean,
    onRefreshVersions: () -> Unit,
    onSelectVersion: (UnicodeVersionOption) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "用完整、统一风格的 Noto Emoji 替换搜狗自带表情包。",
            style = MaterialTheme.typography.bodyLarge,
        )
        StatusCard(state)
        VersionCard(
            configuration = configuration,
            versions = versions,
            loading = versionsLoading,
            message = versionsMessage,
            onRefresh = onRefreshVersions,
            onSelect = onSelectVersion,
        )
        SourceCard(configuration)
        OutlinedButton(
            onClick = onImportArchive,
            enabled = !archiveBusy && !state.isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导入本地 Noto Emoji ZIP") }
        Text(archiveMessage, style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = onInstall,
            enabled = state.canInstall && !archiveBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (state.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(10.dp))
                Text("正在处理")
            } else {
                Text("安装 / 重新安装 Emoji")
            }
        }
        Text(
            text = "安装完成后，Hook 会在搜狗进程内主动退出，让新表情在下次启动时重载，无需 root。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VersionCard(
    configuration: PatchConfiguration,
    versions: List<UnicodeVersionOption>,
    loading: Boolean,
    message: String,
    onRefresh: () -> Unit,
    onSelect: (UnicodeVersionOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Unicode 版本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Emoji ${configuration.emojiVersion}  ·  ${configuration.unicodeFolder}")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    versions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("${option.displayName}  (${option.unicodeFolder})") },
                            onClick = {
                                expanded = false
                                onSelect(option)
                            },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text(if (loading) "获取中" else "刷新")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: PatchUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = CircleShape,
                    color = statusColor(state),
                    content = {},
                )
                Text(
                    text = "当前状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
            if (state.isRunning) {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${state.current} / ${state.total}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Text(
                text = if (state.hookReady) {
                    "LSPosed Hook 已连接"
                } else {
                    "LSPosed Hook 未连接"
                },
                color = if (state.hookReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SourceCard(configuration: PatchConfiguration) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "当前安装快照",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            InfoRow("Unicode", "Emoji ${configuration.emojiVersion}")
            InfoRow("Noto Emoji", EmojiCatalog.NOTO_VERSION)
            InfoRow("图片源", configuration.notoDownloadSource.displayName)
            InfoRow(
                "资格状态",
                if (configuration.includeNonFullyQualified) {
                    "全部资格形式"
                } else {
                    "fully-qualified + component"
                },
            )
            InfoRow(
                "系统缺字",
                if (configuration.includeUnsupportedSystemEmoji) "保留" else "过滤",
            )
            InfoRow("分组", "${EmojiCatalog.groups.size} 个 Unicode 顶级分组")
        }
    }
}

@Composable
private fun statusColor(state: PatchUiState): Color = when {
    state.phase == PatchPhase.ERROR -> MaterialTheme.colorScheme.error
    state.phase == PatchPhase.COMPLETE -> Color(0xFF2E7D32)
    state.isRunning -> MaterialTheme.colorScheme.tertiary
    state.hookReady -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}
