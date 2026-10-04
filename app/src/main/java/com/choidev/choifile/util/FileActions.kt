package com.choidev.choifile.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.choidev.choifile.R
import java.io.File

enum class FileKind { IMAGE, VIDEO, TEXT, PDF, MARKDOWN, OTHER }

private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val videoExt = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")
val codeExt = setOf(
    "kt", "kts", "java", "js", "ts", "py", "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift",
    "sh", "gradle", "json", "xml", "html", "css", "yml", "yaml", "toml", "sql", "php", "rb",
)
private val plainExt = setOf("txt", "log", "csv", "ini", "properties", "conf", "cfg")

fun File.extensionLower(): String = extension.lowercase()

fun kindOf(file: File): FileKind = when (file.extensionLower()) {
    in imageExt -> FileKind.IMAGE
    in videoExt -> FileKind.VIDEO
    "pdf" -> FileKind.PDF
    "md", "markdown" -> FileKind.MARKDOWN
    in codeExt, in plainExt -> FileKind.TEXT
    else -> FileKind.OTHER
}

fun mimeOf(file: File): String = when (file.extensionLower()) {
    "cbz" -> "application/vnd.comicbook+zip"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extensionLower()) ?: "*/*"
}

private fun uriOf(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private fun viewIntent(context: Context, file: File): Intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(uriOf(context, file), mimeOf(file))
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

fun openWithOtherApp(context: Context, file: File) {
    launchChooser(context, viewIntent(context, file), R.string.chooser_open)
}

/** An activity of another app that can view a file. */
data class AppTarget(val packageName: String, val activity: String, val label: String, val icon: Drawable?)

/**
 * Apps that accept ACTION_VIEW for this file's MIME type (needs the <queries> entry in the manifest on Android 11+).
 * Our own app is left out; the built-in viewer is offered separately.
 */
fun appsFor(context: Context, file: File): List<AppTarget> {
    val pm = context.packageManager
    val intent = viewIntent(context, file)
    val infos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION") pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return infos
        .filter { it.activityInfo.packageName != context.packageName }
        .map { AppTarget(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
        .distinctBy { it.packageName + "/" + it.activity }
        .sortedBy { it.label.lowercase() }
}

/** Opens [file] directly in one app's activity. False when that app is gone or refuses the file. */
fun launchWith(context: Context, file: File, packageName: String, activity: String): Boolean {
    val intent = viewIntent(context, file).setClassName(packageName, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

fun shareFiles(context: Context, files: List<File>) {
    val uris = ArrayList(files.map { uriOf(context, it) })
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mimeOf(files[0]))
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launchChooser(context, send, R.string.chooser_share)
}

private fun launchChooser(context: Context, intent: Intent, title: Int) {
    val chooser = Intent.createChooser(intent, context.getString(title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.msg_no_app, Toast.LENGTH_SHORT).show()
    }
}

fun hasAllFilesAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
