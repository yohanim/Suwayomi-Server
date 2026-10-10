package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import eu.kanade.tachiyomi.util.lang.launchIO
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.FileType.RegularFile
import suwayomi.tachidesk.manga.impl.util.getChapterCachePath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.impl.util.storage.FileDeletionHelper
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream

private val applicationDirs: ApplicationDirs by injectLazy()
private val logger = KotlinLogging.logger {}

/*
* Provides downloaded files when pages were downloaded into folders
* */
class FolderProvider(
    mangaId: Int,
    chapterId: Int,
) : ChaptersFilesProvider<RegularFile>(mangaId, chapterId) {
    override suspend fun getImageFiles(): List<RegularFile> {
        val chapterFolder = File(getChapterDownloadPath(mangaId, chapterId))

        if (!chapterFolder.exists()) {
            throw NoSuchElementException("download folder does not exist")
        }

        return chapterFolder
            .listFiles()
            .orEmpty()
            .toList()
            .map(::RegularFile)
    }

    override suspend fun getImageInputStream(image: RegularFile): FileInputStream = FileInputStream(image.file)

    override suspend fun extractExistingDownload() {
        // nothing to do
    }

    override suspend fun handleSuccessfulDownload() {
        val chapterDir = getChapterDownloadPath(mangaId, chapterId)
        val folder = File(chapterDir)

        val cacheChapterDir = getChapterCachePath(mangaId, chapterId)
        File(cacheChapterDir).copyRecursively(folder, true)
    }

    override suspend fun delete(): Boolean {
        val chapterDirPath = getChapterDownloadPath(mangaId, chapterId)
        val chapterDir = File(chapterDirPath)
        if (!chapterDir.exists()) {
            return true
        }

        val chapterDirDeleted = chapterDir.deleteRecursively()
        if (chapterDirDeleted) {
            transaction {
                ChapterUserTable.update({ ChapterUserTable.chapter eq chapterId }) {
                    it[koreaderHash] = null
                }
            }
        }
        FileDeletionHelper.cleanupParentFoldersFor(chapterDir, applicationDirs.mangaDownloadsRoot)
        return chapterDirDeleted
    }

    private suspend fun archiveFiles(): List<File>? =
        File(getChapterDownloadPath(mangaId, chapterId))
            .listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }

    override suspend fun getAsArchiveStream(): Pair<InputStream, Long> {
        val files = archiveFiles()

        if (files.isNullOrEmpty()) {
            throw IllegalArgumentException("Invalid folder to create CBZ for chapter ID: $chapterId")
        }

        return StoredZipInputStream(files, chapterId) to storedZipSize(files)
    }

    /** Writes the zip on another thread as it's read, so it's never held in memory */
    internal class StoredZipInputStream(
        private val files: List<File>,
        private val chapterId: Int,
    ) : InputStream() {
        private val pipe = PipedInputStream(PIPE_BUFFER_SIZE)
        private var started = false

        // started on the first read so a stream that is never read doesn't leave a writer blocked on the pipe
        private fun start() {
            if (started) return
            started = true
            val outputStream = PipedOutputStream(pipe)
            launchIO {
                try {
                    writeStoredZip(files, outputStream)
                } catch (e: IOException) {
                    // the client usually closed the connection
                    logger.debug(e) { "Stopped writing the CBZ of chapter $chapterId" }
                }
            }
        }

        override fun read(): Int {
            start()
            return pipe.read()
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            start()
            return pipe.read(b, off, len)
        }

        override fun available(): Int = pipe.available()

        override fun close() = pipe.close()
    }

    override suspend fun getArchiveSize(): Long = archiveFiles()?.let(::storedZipSize) ?: 0L

    companion object {
        private const val PIPE_BUFFER_SIZE = 64 * 1024

        /** Pages are already compressed images, so they are stored as is */
        internal fun writeStoredZip(
            files: List<File>,
            outputStream: OutputStream,
            withContent: Boolean = true,
        ) {
            ZipArchiveOutputStream(outputStream).use { zipOutputStream ->
                zipOutputStream.setMethod(ZipArchiveOutputStream.STORED)

                files.forEach { file ->
                    val zipEntry = ZipArchiveEntry(file.name)
                    zipEntry.method = ZipArchiveOutputStream.STORED
                    zipEntry.time = 0L
                    // a stored entry needs its size and crc before its content
                    zipEntry.size = if (withContent) file.length() else 0L
                    zipEntry.crc = if (withContent) crc32(file) else 0L
                    zipOutputStream.putArchiveEntry(zipEntry)
                    if (withContent) file.inputStream().use { it.copyTo(zipOutputStream) }
                    zipOutputStream.closeArchiveEntry()
                }
            }
        }

        /** The zip headers don't depend on the entries' content, so only they are written */
        internal fun storedZipSize(files: List<File>): Long {
            val headers = CountingOutputStream()
            writeStoredZip(files, headers, withContent = false)
            return headers.count + files.sumOf { it.length() }
        }

        private fun crc32(file: File): Long =
            CheckedInputStream(file.inputStream(), CRC32()).use {
                it.copyTo(OutputStream.nullOutputStream())
                it.checksum.value
            }
    }

    private class CountingOutputStream : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            count++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            count += len
        }
    }
}
