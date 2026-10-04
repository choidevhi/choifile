package com.choidev.choifile.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.archive.ArchiveLimitException
import com.choidev.choifile.archive.ArchiveLocation
import com.choidev.choifile.archive.ArchiveUnsupportedException
import com.choidev.choifile.archive.normalizeEntryName
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The pages of one comic: a folder of images, or a zip/cbz (optionally one folder inside it). */
interface ComicBook : Closeable {
    /** Key for the saved reading position. */
    val key: String
    val title: String
    /** Page names in reading order (file names, or entry paths inside an archive). */
    val pages: List<String>

    /** Pixel size of page [i] without decoding it (header only); (0, 0) when it cannot be read. Blocks. */
    fun bounds(i: Int): Pair<Int, Int>

    /** A local file with page [i]'s bytes; archive pages are extracted to the cache on first use. Blocks. */
    fun fileFor(i: Int): File

    companion object {
        /** Opens [location]: a local folder, a local .zip/.cbz, or a folder inside a zip shown as an archive tab. */
        fun open(location: String, cacheDir: File): ComicBook {
            if (ArchiveLocation.isArchive(location)) {
                val archive = File(ArchiveLocation.archiveOf(location))
                if (ArchiveFormat.detect(archive.name) != ArchiveFormat.ZIP) throw ArchiveUnsupportedException("comic: zip/cbz only")
                return ZipComic(archive, ArchiveLocation.innerOf(location), cacheDir)
            }
            val f = File(location)
            return when {
                f.isDirectory -> FolderComic(f)
                ArchiveFormat.detect(f.name) == ArchiveFormat.ZIP -> ZipComic(f, "", cacheDir)
                else -> throw ArchiveUnsupportedException("comic: folders and zip/cbz only")
            }
        }
    }
}

private fun boundsOf(open: () -> InputStream): Pair<Int, Int> = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, o) }
    if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else 0 to 0
}.getOrDefault(0 to 0)

class FolderComic(dir: File) : ComicBook {
    override val key: String = dir.absolutePath
    override val title: String = dir.name
    private val files: List<File> = comicPageOrder(dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()).map { File(dir, it) }
    override val pages: List<String> = files.map { it.name }

    override fun bounds(i: Int): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[i].path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else 0 to 0
    }

    override fun fileFor(i: Int): File = files[i]
    override fun close() {}
}

/**
 * Zip-based comic. The zip's central directory gives random access, so each page is read on demand; pages are
 * written once into a per-archive cache folder (cleared by "캐시 삭제" in settings) because the region decoder
 * needs a seekable file.
 */
class ZipComic(private val archive: File, inner: String, cacheRoot: File) : ComicBook {
    private val zip: ZipFile = ZipFile.builder().setFile(archive).get()
    private val entries: List<ZipArchiveEntry>
    override val pages: List<String>
    override val key: String = if (inner.isEmpty()) archive.absolutePath else ArchiveLocation.make(archive.absolutePath, inner)
    override val title: String = if (inner.isEmpty()) archive.name else inner.substringAfterLast('/')
    private val dir = File(cacheRoot, "comic/" + Integer.toHexString("${archive.absolutePath}|${archive.lastModified()}|${archive.length()}".hashCode()))

    init {
        try {
            val prefix = if (inner.isEmpty()) "" else "$inner/"
            val byName = zip.entries.toList()
                .filter { !it.isDirectory && !it.isUnixSymlink }
                .associateBy { normalizeEntryName(it.name) }
                .filterKeys { it.startsWith(prefix) }
            pages = comicPageOrder(byName.keys.toList())
            entries = pages.map { byName.getValue(it) }
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    private fun stream(i: Int): InputStream {
        val e = entries[i]
        if (!zip.canReadEntryData(e)) throw ArchiveUnsupportedException("encrypted or unsupported zip entry")
        return zip.getInputStream(e)
    }

    @Synchronized override fun bounds(i: Int): Pair<Int, Int> = boundsOf { stream(i) }

    @Synchronized override fun fileFor(i: Int): File {
        val ext = pages[i].substringAfterLast('.', "img")
        val out = File(dir, "$i.$ext")
        if (out.isFile && out.length() > 0) return out
        dir.mkdirs()
        val tmp = File(dir, "$i.part")
        stream(i).use { input ->
            tmp.outputStream().buffered().use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_PAGE_BYTES) throw ArchiveLimitException("page too large")
                    o.write(buf, 0, n)
                }
            }
        }
        if (!tmp.renameTo(out)) throw IOException("cannot write page cache")
        return out
    }

    override fun close() = zip.close()

    private companion object {
        const val MAX_PAGE_BYTES = 256L * 1024 * 1024
    }
}

/**
 * Decodes page strips at screen width. Tall pages are cut into strips with BitmapRegionDecoder and every strip
 * is subsampled (power of two) to the viewer width, so memory stays bounded however long a webtoon page is.
 * Decoded strips live in a byte-bounded LRU cache.
 */
class ComicDecoder(maxBytes: Int) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val regions = LinkedHashMap<String, BitmapRegionDecoder>()

    fun cached(key: String): Bitmap? = cache.get(key)

    /** Blocks. [whole] = the strip is the entire page (no region decoding needed). */
    fun decode(key: String, file: File, slice: ComicSlice, targetWidth: Int, whole: Boolean): Bitmap? {
        cache.get(key)?.let { return it }
        val sample = ComicLayout.sampleSize(slice.width, targetWidth)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = if (whole) {
            BitmapFactory.decodeFile(file.path, opts)
        } else {
            regionDecode(file, slice, opts) ?: run {
                // Formats without region support (GIF, BMP): decode subsampled, then crop the strip.
                val full = BitmapFactory.decodeFile(file.path, opts) ?: return null
                val top = (slice.top / sample).coerceIn(0, full.height - 1)
                val h = (slice.height / sample).coerceIn(1, full.height - top)
                Bitmap.createBitmap(full, 0, top, full.width, h).also { if (it !== full) full.recycle() }
            }
        } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    private fun regionDecode(file: File, slice: ComicSlice, opts: BitmapFactory.Options): Bitmap? = synchronized(regions) {
        val decoder = regions[file.path] ?: run {
            val d = runCatching {
                if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.path)
                else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.path, false)
            }.getOrNull() ?: return null
            regions[file.path] = d
            while (regions.size > 3) regions.remove(regions.keys.first())?.recycle()
            d
        }
        runCatching { decoder.decodeRegion(Rect(0, slice.top, slice.width, slice.bottom), opts) }.getOrNull()
    }

    fun clear() {
        cache.evictAll()
        synchronized(regions) {
            regions.values.forEach { it.recycle() }
            regions.clear()
        }
    }
}
