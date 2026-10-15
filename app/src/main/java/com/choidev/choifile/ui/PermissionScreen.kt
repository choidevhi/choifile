package com.choidev.choifile.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.choidev.choifile.R

@Composable
fun PermissionScreen(onRequestLegacy: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val c = ChoiTheme.colors
        androidx.compose.foundation.layout.Box(
            Modifier.size(64.dp)
                .background(c.muted, androidx.compose.foundation.shape.RoundedCornerShape(Radius.xl))
                .border(Hairline, c.border, androidx.compose.foundation.shape.RoundedCornerShape(Radius.xl)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(28.dp), tint = c.foreground) }
        Spacer(Modifier.height(Space.xl))
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.sm))
        Text(stringResource(R.string.perm_body), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = c.mutedForeground)
        Spacer(Modifier.height(Space.xl))
        PrimaryButton(onClick = {
            if (Build.VERSION.SDK_INT >= 30) {
                val uri = Uri.parse("package:${context.packageName}")
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            } else {
                onRequestLegacy()
            }
        }) { Text(stringResource(R.string.perm_button)) }
    }
}
