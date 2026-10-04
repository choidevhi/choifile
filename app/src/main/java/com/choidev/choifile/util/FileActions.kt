package com.choidev.choifile.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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

private fun mimeOf(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extensionLower()) ?: "*/*"

private fun uriOf(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

fun openWithOtherApp(context: Context, file: File) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriOf(context, file), mimeOf(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launchChooser(context, view, R.string.chooser_open)
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
