package com.king250.sougou.ui.page

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.king250.sougou.BuildConfig
import com.king250.sougou.OpenSourceLicensesActivity
import com.king250.sougou.R

private const val PROJECT_URL = "https://github.com/250king/SougouEmojiFixer"

@Composable
fun AboutPage() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AboutHeader()
        HorizontalDivider()
        AboutTile(
            title = "项目地址",
            subtitle = PROJECT_URL,
            onClick = { uriHandler.openUri(PROJECT_URL) },
        )
        AboutTile(
            title = "作者",
            subtitle = "250king",
            onClick = { uriHandler.openUri("https://250king.top/") },
        )
        AboutTile(
            title = "开源许可证",
            subtitle = "第三方开源软件与 Emoji 资源许可",
            onClick = {
                context.startActivity(Intent(context, OpenSourceLicensesActivity::class.java))
            },
        )
    }
}

@Composable
private fun AboutHeader() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val iconSizePx = with(density) { 104.dp.roundToPx() }
    val icon = remember(context, iconSizePx) {
        context.packageManager
            .getApplicationIcon(context.packageName)
            .toBitmap(iconSizePx, iconSizePx)
            .asImageBitmap()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(104.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    bitmap = icon,
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
