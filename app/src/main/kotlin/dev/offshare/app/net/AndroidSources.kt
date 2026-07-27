package dev.offshare.app.net

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import dev.offshare.protocol.FailureReason
import dev.offshare.protocol.FileMeta
import dev.offshare.protocol.FileSource
import dev.offshare.protocol.TransferException
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * A [FileSource] over a `content://` URI handed in by the share sheet or the
 * document picker.
 *
 * Opens a fresh descriptor per call, because the sender's parallel streams
 * each need their own file position.
 */
class UriFileSource(
    private val resolver: ContentResolver,
    override val meta: FileMeta,
    private val uri: Uri,
) : FileSource {

    override fun openChannel(): FileChannel {
        val descriptor = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            throw TransferException(FailureReason.STORAGE, "Could not open ${meta.name}", e)
        } ?: throw TransferException(FailureReason.STORAGE, "Could not open ${meta.name}")

        // AutoCloseInputStream ties the descriptor's lifetime to the stream,
        // so closing the channel releases the fd. A plain FileInputStream over
        // the raw FileDescriptor would leak one per stream per file.
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel
    }
}

/**
 * A [FileSource] over a file this app already owns on disk. Used for content
 * providers that hand back a pipe rather than a seekable file -- see
 * [AndroidFiles.sourceFor].
 */
class SpooledFileSource(
    override val meta: FileMeta,
    private val file: File,
) : FileSource {
    override fun openChannel(): FileChannel = FileInputStream(file).channel

    fun delete() {
        runCatching { file.delete() }
    }
}

object AndroidFiles {

    /**
     * Builds a [FileSource] for a shared URI.
     *
     * Most providers back a `content://` URI with a real file, which supports
     * the positional reads the parallel sender depends on. A few -- typically
     * cloud or streaming providers -- return a pipe instead, where seeking is
     * impossible. Rather than fail or silently drop to one stream, those get
     * spooled to the cache first: one sequential read up front buys the whole
     * parallel path afterwards.
     */
    fun sourceFor(context: Context, uri: Uri, id: Int): FileSource {
        val resolver = context.contentResolver
        val meta = metadataFor(resolver, uri, id)

        return if (isSeekable(resolver, uri)) {
            UriFileSource(resolver, meta, uri)
        } else {
            Log.i(TAG, "${meta.name} is not seekable; spooling to cache")
            spool(context, uri, meta)
        }
    }

    private fun isSeekable(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(
                ParcelFileDescriptor.dup(descriptor.fileDescriptor),
            ).use { stream ->
                // A pipe reports size -1 and throws on position queries.
                descriptor.statSize >= 0 && stream.channel.size() >= 0
            }
        } ?: false
    } catch (e: Exception) {
        false
    }

    private fun spool(context: Context, uri: Uri, meta: FileMeta): FileSource {
        val cacheDir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val spooled = File(cacheDir, "${meta.id}-${meta.name}")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "no stream for $uri" }
                spooled.outputStream().buffered().use { output -> input.copyTo(output, COPY_BUFFER) }
            }
        } catch (e: Exception) {
            throw TransferException(FailureReason.STORAGE, "Could not read ${meta.name}", e)
        }
        return SpooledFileSource(meta.copy(size = spooled.length()), spooled)
    }

    /**
     * Reads display name and size for a URI.
     *
     * Both are advisory: providers are allowed to return null for either, and
     * a wrong size would desynchronise the transfer, so a missing size falls
     * back to the descriptor's actual length rather than a guess.
     */
    fun metadataFor(resolver: ContentResolver, uri: Uri, id: Int): FileMeta {
        var name: String? = null
        var size: Long = -1

        val cursor: Cursor? = runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
        }.getOrNull()

        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }

        if (size < 0) {
            size = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()?.takeIf { it >= 0 } ?: 0L
        }

        return FileMeta(
            id = id,
            name = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file-$id",
            size = size,
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
        )
    }

    /** Where received files land. */
    fun downloadDirectory(context: Context): File =
        File(context.getExternalFilesDir(null), "OfflineShare").apply { mkdirs() }

    private const val TAG = "AndroidFiles"
    private const val COPY_BUFFER = 256 * 1024
}
