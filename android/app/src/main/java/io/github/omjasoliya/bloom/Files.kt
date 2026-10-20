package io.github.omjasoliya.bloom

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class Kind { IMAGE, PDF, VIDEO, AUDIO, OTHER }

/** A file Bloom was given: shared from another app or picked inside Bloom. */
class Input(val uri: Uri, val name: String, val mime: String, val size: Long) {
    val ext = name.substringAfterLast('.', "").lowercase()
    val stem = if ('.' in name) name.substringBeforeLast('.') else name
    val kind = when {
        mime == "application/pdf" || ext == "pdf" -> Kind.PDF
        mime == "image/svg+xml" || ext == "svg" -> Kind.OTHER
        mime.startsWith("image/") || ext in IMAGE_EXTS -> Kind.IMAGE
        mime.startsWith("video/") || ext in VIDEO_EXTS -> Kind.VIDEO
        mime.startsWith("audio/") || ext in AUDIO_EXTS -> Kind.AUDIO
        else -> Kind.OTHER
    }

    fun open(context: Context): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open $name")

    companion object {
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp", "gif")
        private val VIDEO_EXTS = setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")
        private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr")

        fun from(context: Context, uri: Uri): Input {
            val resolver = context.contentResolver
            var name: String? = null
            var size = -1L
            if (uri.scheme == "file") {
                val file = File(uri.path.orEmpty())
                name = file.name
                size = file.length()
            } else runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            }
            val types = MimeTypeMap.getSingleton()
            var base = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val ext = base.substringAfterLast('.', "").lowercase()
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
                ?: types.getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            // Some apps share files without an extension: add one from the type.
            if (ext.isEmpty()) types.getExtensionFromMimeType(mime)?.let { base = "$base.$it" }
            return Input(uri, base, mime, size)
        }
    }
}

/** A file Bloom made, in Downloads/Bloom. */
class Saved(val uri: Uri, val name: String, val mime: String)

object Downloads {
    private const val FOLDER = "Bloom"
    const val WHERE = "Downloads/$FOLDER"

    fun mimeFor(ext: String): String = when (ext) {
        "jpg" -> "image/jpeg"
        "m4a" -> "audio/mp4"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Creates [name] in Downloads/Bloom and lets [write] fill it. A name that's taken gets a number. */
    fun save(context: Context, name: String, write: (OutputStream) -> Unit): Saved {
        val resolver = context.contentResolver
        val mime = mimeFor(name.substringAfterLast('.', "").lowercase())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create $name in $WHERE")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IOException("Couldn't write $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        val finalName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return Saved(uri, finalName ?: name, mime)
    }

    fun saveFile(context: Context, name: String, file: File): Saved =
        save(context, name) { out -> file.inputStream().use { it.copyTo(out) } }
}

fun humanSize(bytes: Long): String {
    if (bytes < 0) return ""
    var size = bytes.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB")) {
        if (size < 1024 || unit == "GB") return if (unit == "B") "${size.toLong()} B" else "%.1f %s".format(size, unit)
        size /= 1024
    }
    return ""
}
