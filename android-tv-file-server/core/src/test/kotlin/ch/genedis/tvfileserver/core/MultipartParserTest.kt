package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.http.MultipartParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class MultipartParserTest {

    @Test
    fun `extracts the boundary from a content type`() {
        assertEquals("abc", MultipartParser.boundaryOf("multipart/form-data; boundary=abc"))
        assertEquals("a b", MultipartParser.boundaryOf("multipart/form-data; boundary=\"a b\""))
        assertEquals("x", MultipartParser.boundaryOf("MULTIPART/FORM-DATA;charset=utf-8; BOUNDARY=x"))
        assertNull(MultipartParser.boundaryOf("application/json"))
        assertNull(MultipartParser.boundaryOf(null))
        assertNull(MultipartParser.boundaryOf("multipart/form-data"))
    }

    @Test
    fun `reads fields and files in order`() {
        val body = MultipartBuilder()
            .field("path", "/data/Movies")
            .file("file", "a.txt", "hello".toByteArray())
            .file("file", "b.bin", byteArrayOf(0, 1, 2, 3, 4))
            .build()

        val names = mutableListOf<String?>()
        val fileNames = mutableListOf<String?>()
        val payloads = mutableListOf<ByteArray>()

        MultipartParser(ByteArrayInputStream(body), "----TvFileServerTestBoundary").forEachPart { part ->
            names.add(part.name)
            fileNames.add(part.fileName)
            payloads.add(part.stream.readBytes())
        }

        assertEquals(listOf("path", "file", "file"), names)
        assertEquals(listOf(null, "a.txt", "b.bin"), fileNames)
        assertEquals("/data/Movies", String(payloads[0]))
        assertEquals("hello", String(payloads[1]))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), payloads[2])
    }

    @Test
    fun `finds boundaries split across buffer refills`() {
        // A 3 MiB payload parsed through a 64-byte window guarantees the delimiter straddles
        // refills many times over, which is exactly where a naive parser corrupts data.
        val payload = Random(7).nextBytes(3 * 1024 * 1024)
        val body = MultipartBuilder()
            .file("file", "big.bin", payload)
            .field("after", "trailing-field")
            .build()

        var received: ByteArray? = null
        var trailing: String? = null
        MultipartParser(ByteArrayInputStream(body), "----TvFileServerTestBoundary", bufferSize = 64)
            .forEachPart { part ->
                if (part.isFile) {
                    val sink = ByteArrayOutputStream()
                    part.stream.copyTo(sink, bufferSize = 37)
                    received = sink.toByteArray()
                } else {
                    trailing = part.readText()
                }
            }

        assertArrayEquals(payload, received)
        assertEquals("trailing-field", trailing)
    }

    @Test
    fun `keeps the relative path of a directory upload`() {
        val body = MultipartBuilder()
            .file("file", "season 1/ep01.mkv", "video".toByteArray())
            .build()

        var fileName: String? = null
        MultipartParser(ByteArrayInputStream(body), "----TvFileServerTestBoundary").forEachPart {
            fileName = it.fileName
        }

        assertEquals("season 1/ep01.mkv", fileName)
        assertEquals(listOf("season 1", "ep01.mkv"), MultipartParser.sanitizeRelativePath(fileName!!))
    }

    @Test
    fun `decodes RFC 5987 file names`() {
        val body = MultipartBuilder()
            .file(
                name = "file",
                fileName = "ignored",
                content = "x".toByteArray(),
                rawDisposition = "form-data; name=\"file\"; filename=\"e.txt\"; " +
                    "filename*=UTF-8''r%C3%A9sum%C3%A9%20%E2%98%85.txt",
            )
            .build()

        var fileName: String? = null
        MultipartParser(ByteArrayInputStream(body), "----TvFileServerTestBoundary").forEachPart {
            fileName = it.fileName
        }
        assertEquals("résumé ★.txt", fileName)
    }

    @Test
    fun `handles quoted semicolons and escaped quotes in the disposition`() {
        val body = MultipartBuilder()
            .file(
                name = "file",
                fileName = "ignored",
                content = "x".toByteArray(),
                rawDisposition = "form-data; name=\"fi;le\"; filename=\"we\\\"ird; name.txt\"",
            )
            .build()

        var name: String? = null
        var fileName: String? = null
        MultipartParser(ByteArrayInputStream(body), "----TvFileServerTestBoundary").forEachPart {
            name = it.name
            fileName = it.fileName
        }
        assertEquals("fi;le", name)
        assertEquals("we\"ird; name.txt", fileName)
    }

    @Test
    fun `skips a part the callback never reads`() {
        val body = MultipartBuilder()
            .file("file", "skipped.bin", Random(3).nextBytes(200_000))
            .field("kept", "value")
            .build()

        val seen = mutableListOf<String>()
        MultipartParser(ByteArrayInputStream(body), "----TvFileServerTestBoundary", bufferSize = 128)
            .forEachPart { part ->
                if (part.isFile) {
                    seen.add("file:${part.fileName}")
                } else {
                    seen.add("field:${part.name}=${part.readText()}")
                }
            }

        assertEquals(listOf("file:skipped.bin", "field:kept=value"), seen)
    }

    @Test
    fun `tolerates a preamble before the first boundary`() {
        val core = MultipartBuilder().field("a", "1").build()
        val withPreamble = "This is a MIME preamble that clients may send.\r\n".toByteArray() + core

        var value: String? = null
        MultipartParser(ByteArrayInputStream(withPreamble), "----TvFileServerTestBoundary").forEachPart {
            value = it.readText()
        }
        assertEquals("1", value)
    }

    @Test
    fun `rejects a truncated body instead of looping`() {
        val complete = MultipartBuilder().file("file", "a.bin", ByteArray(5_000)).build()
        val truncated = complete.copyOf(complete.size - 2_000)

        var failure: Exception? = null
        try {
            MultipartParser(ByteArrayInputStream(truncated), "----TvFileServerTestBoundary", bufferSize = 256)
                .forEachPart { it.stream.readBytes() }
        } catch (error: IOException) {
            failure = error
        }
        assertTrue("expected an IOException, got $failure", failure is IOException)
    }

    @Test
    fun `handles an empty file part`() {
        val body = MultipartBuilder().file("file", "empty.txt", ByteArray(0)).build()
        var size = -1
        MultipartParser(ByteArrayInputStream(body), "----TvFileServerTestBoundary").forEachPart {
            size = it.stream.readBytes().size
        }
        assertEquals(0, size)
    }
}
