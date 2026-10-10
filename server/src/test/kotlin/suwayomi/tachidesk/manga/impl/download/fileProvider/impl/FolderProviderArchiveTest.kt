package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.channels.SeekableByteChannel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FolderProviderArchiveTest {
    @TempDir
    lateinit var dir: File

    private fun pages(): List<File> =
        listOf(
            "001.jpg" to 300_000,
            "002.webp" to 1,
            "003 é.png" to 70_000,
            "ComicInfo.xml" to 0,
        ).map { (name, size) -> File(dir, name).apply { writeBytes(Random(size).nextBytes(size)) } }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val channel: SeekableByteChannel = SeekableInMemoryByteChannel(bytes)
        return ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
            zip.entries.toList().associate { entry ->
                assertEquals(ZipArchiveOutputStream.STORED, entry.method, entry.name)
                entry.name to zip.getInputStream(entry).use { it.readBytes() }
            }
        }
    }

    @Test
    fun streamedArchiveHasTheAnnouncedSizeAndThePages() {
        val files = pages()

        val bytes = FolderProvider.StoredZipInputStream(files, chapterId = 1).use { it.readBytes() }

        assertEquals(FolderProvider.storedZipSize(files), bytes.size.toLong())
        val entries = readZip(bytes)
        assertEquals(files.map { it.name }, entries.keys.toList())
        files.forEach { assertContentEquals(it.readBytes(), entries.getValue(it.name), it.name) }
    }
}
