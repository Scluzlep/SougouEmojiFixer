package com.king250.sougou.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.king250.sougou.patch.NotoDownloadSource
import com.king250.sougou.patch.PatchConfiguration

@Composable
fun SettingsPage(
    configuration: PatchConfiguration,
    onIncludeNonFullyQualifiedChange: (Boolean) -> Unit,
    onIncludeUnsupportedSystemEmojiChange: (Boolean) -> Unit,
    onNotoDownloadSourceChange: (NotoDownloadSource) -> Unit,
) {
    var sourceMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingSwitch(
            title = "插入非 fully-qualified Emoji",
            description = "额外包含 minimally-qualified 与 unqualified 序列；" +
                "可能出现外观相同但编码不同的条目。",
            checked = configuration.includeNonFullyQualified,
            onCheckedChange = onIncludeNonFullyQualifiedChange,
        )
        SettingSwitch(
            title = "插入系统残缺 Emoji",
            description = "跳过当前系统字体的可用性过滤。图标仍能显示，" +
                "但输入后可能变成方框或拆开的序列。",
            checked = configuration.includeUnsupportedSystemEmoji,
            onCheckedChange = onIncludeUnsupportedSystemEmojiChange,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Noto 图片下载源",
                    style = MaterialTheme.typography.titleMedium,
                )
                Box {
                    OutlinedButton(
                        onClick = { sourceMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(configuration.notoDownloadSource.displayName)
                    }
                    DropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false },
                    ) {
                        NotoDownloadSource.entries.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.displayName) },
                                onClick = {
                                    sourceMenuExpanded = false
                                    onNotoDownloadSourceChange(source)
                                },
                            )
                        }
                    }
                }
                Text(
                    text = "Unicode 清单始终从 unicode.org 获取；" +
                        "该选项只影响数千张 Noto PNG。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}
