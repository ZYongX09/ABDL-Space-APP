package org.joinmastodon.reader.parser

import org.joinmastodon.reader.domain.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookParserTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private val parser = BookParser()

	@Test
	fun parsesUtf8TxtFixtureIntoOrderedChapters() {
		val fixture = temporaryFolder.newFile("utf8-novel.txt").apply {
			writeText("序章内容。\n\n第一章 初见\n第一章正文。\n\n第二章 重逢\n第二章正文。", Charsets.UTF_8)
		}

		val parsed = parser.parse(fixture)

		assertEquals(BookFormat.TXT, parsed.book.format)
		assertEquals("utf8-novel", parsed.book.title)
		assertEquals(listOf("前言", "第一章 初见", "第二章 重逢"), parsed.chapters.map { it.title })
		assertTrue(parsed.chapters[1].content.contains("第一章正文"))
	}

	@Test
	fun parsesGb18030TxtFixtureWithoutReplacementCharacters() {
		val fixture = temporaryFolder.newFile("gb-novel.txt").apply {
			writeBytes("第一章 烟火\r\n夜色里的中文正文。".toByteArray(Charset.forName("GB18030")))
		}

		val parsed = parser.parse(fixture)

		assertEquals(listOf("第一章 烟火"), parsed.chapters.map { it.title })
		assertEquals("夜色里的中文正文。", parsed.chapters.single().content)
		assertTrue(parsed.chapters.none { '\uFFFD' in it.content })
	}

	@Test
	fun parsesEpubMetadataAndSpineOrder() {
		val fixture = createEpubFixture("ordered.epub")

		val parsed = parser.parse(fixture)

		assertEquals(BookFormat.EPUB, parsed.book.format)
		assertEquals("微型书", parsed.book.title)
		assertEquals("测试作者", parsed.book.author)
		assertEquals(listOf("第二节", "第一节"), parsed.chapters.map { it.title })
		assertEquals(listOf("先读这一节。", "后读这一节。"), parsed.chapters.map { it.content })
	}

	@Test
	fun sameContentKeepsStableBookChapterAndAnchorIds() {
		val firstFile = temporaryFolder.newFile("first-name.txt").apply {
			writeText("第一章 稳定\n相同正文。", Charsets.UTF_8)
		}
		val secondFile = temporaryFolder.newFile("renamed.txt").apply {
			writeBytes(firstFile.readBytes())
		}

		val first = parser.parse(firstFile)
		val second = parser.parse(secondFile)

		assertEquals(first.book.id, second.book.id)
		assertEquals(first.chapters.map { it.id }, second.chapters.map { it.id })
		assertEquals(first.chapters.map { it.anchorId }, second.chapters.map { it.anchorId })
		assertNotEquals(first.book.title, second.book.title)
	}

	private fun createEpubFixture(name: String): File = temporaryFolder.newFile(name).also { file ->
		ZipOutputStream(file.outputStream()).use { zip ->
			val mimetype = "application/epub+zip".toByteArray()
			zip.putNextEntry(ZipEntry("mimetype").apply {
				method = ZipEntry.STORED
				size = mimetype.size.toLong()
				compressedSize = mimetype.size.toLong()
				crc = CRC32().apply { update(mimetype) }.value
			})
			zip.write(mimetype)
			zip.closeEntry()
			zip.writeEntry(
				"META-INF/container.xml",
				"""<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
			)
			zip.writeEntry(
				"OPS/content.opf",
				"""<?xml version="1.0" encoding="UTF-8"?>
					<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
						<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>微型书</dc:title><dc:creator>测试作者</dc:creator></metadata>
						<manifest><item id="one" href="text/one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="text/two.xhtml" media-type="application/xhtml+xml"/></manifest>
						<spine><itemref idref="two"/><itemref idref="one"/></spine>
					</package>""".trimIndent(),
			)
			zip.writeEntry("OPS/text/one.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一节</title></head><body><h1>第一节</h1><p>后读这一节。</p></body></html>")
			zip.writeEntry("OPS/text/two.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第二节</title></head><body><h1>第二节</h1><p>先读这一节。</p></body></html>")
		}
	}

	private fun ZipOutputStream.writeEntry(path: String, content: String) {
		putNextEntry(ZipEntry(path))
		write(content.toByteArray(Charsets.UTF_8))
		closeEntry()
	}
}
