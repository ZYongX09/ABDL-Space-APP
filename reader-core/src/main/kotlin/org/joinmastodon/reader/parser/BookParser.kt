package org.joinmastodon.reader.parser

import org.joinmastodon.reader.domain.BookFormat
import org.joinmastodon.reader.domain.ParsedBook
import org.joinmastodon.reader.domain.ReaderBook
import org.joinmastodon.reader.domain.ReaderChapter
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

class BookParser {
	fun parse(file: File): ParsedBook = when (file.extension.lowercase(Locale.ROOT)) {
		"txt" -> parseTxt(file)
		"epub" -> parseEpub(file)
		else -> throw IllegalArgumentException("不支持的文件格式: ${file.extension}")
	}

	private fun parseTxt(file: File): ParsedBook {
		val text = decodeText(file.readBytes()).normalizeText()
		val drafts = splitTxtChapters(text)
		return buildParsedBook(
			title = file.nameWithoutExtension,
			author = null,
			format = BookFormat.TXT,
			drafts = drafts,
		)
	}

	private fun parseEpub(file: File): ParsedBook = ZipFile(file).use { zip ->
		val containerPath = "META-INF/container.xml"
		val containerEntry = zip.getEntry(containerPath)
			?: throw IllegalArgumentException("EPUB 缺少 $containerPath")
		val container = zip.getInputStream(containerEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
		val containerDocument = Jsoup.parse(container, "", Parser.xmlParser())
		val opfPath = safeArchivePath(null, containerDocument.selectFirst("rootfile")?.attr("full-path"))
			?: throw IllegalArgumentException("EPUB container.xml 中的 OPF 路径无效")
		val opfEntry = zip.getEntry(opfPath) ?: throw IllegalArgumentException("EPUB 缺少 OPF: $opfPath")
		val opf = zip.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
		val opfDocument = Jsoup.parse(opf, "", Parser.xmlParser())
		val metadata = opfDocument.selectFirst("metadata")
		val title = metadata?.children()?.firstOrNull { it.tagName().substringAfter(':') == "title" }?.text()?.trim().orEmpty()
			.ifBlank { file.nameWithoutExtension }
		val author = metadata?.children()?.firstOrNull { it.tagName().substringAfter(':') == "creator" }?.text()?.trim()?.takeIf { it.isNotBlank() }
		val opfDirectory = opfPath.substringBeforeLast('/', "")
		val manifest = opfDocument.select("manifest > item").mapNotNull { item ->
			val id = item.attr("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val path = safeArchivePath(opfDirectory, item.attr("href")) ?: return@mapNotNull null
			id to path
		}.toMap()
		val drafts = opfDocument.select("spine > itemref").mapNotNull { itemRef ->
			val path = manifest[itemRef.attr("idref")] ?: return@mapNotNull null
			val entry = zip.getEntry(path) ?: return@mapNotNull null
			val markup = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
			parseXhtml(markup)
		}
		if (drafts.isEmpty()) throw IllegalArgumentException("EPUB 没有可读取的 spine 章节")
		buildParsedBook(title, author, BookFormat.EPUB, drafts)
	}

	private fun parseXhtml(markup: String): ChapterDraft? {
		val document = Jsoup.parse(markup, "", Parser.xmlParser())
		document.select("script, style, nav").remove()
		val heading = document.selectFirst("body h1, body h2, body h3")
		val title = document.selectFirst("head > title")?.text()?.trim().orEmpty()
			.ifBlank { heading?.text().orEmpty().trim() }
			.ifBlank { "未命名章节" }
		heading?.remove()
		val paragraphs = document.select("body p, body div, body li, body blockquote")
			.map { it.text().trim() }
			.filter { it.isNotBlank() }
		val content = (if (paragraphs.isEmpty()) document.body().text() else paragraphs.joinToString("\n\n"))
			.normalizeText()
		return content.takeIf { it.isNotBlank() }?.let { ChapterDraft(title, it) }
	}

	private fun splitTxtChapters(text: String): List<ChapterDraft> {
		val chapters = mutableListOf<ChapterDraft>()
		var title = "前言"
		val body = StringBuilder()
		fun flush() {
			val content = body.toString().normalizeText()
			if (content.isNotBlank()) chapters += ChapterDraft(title, content)
			body.clear()
		}
		text.lineSequence().forEach { line ->
			val trimmed = line.trim()
			if (trimmed.length in 1..100 && isChapterHeading(trimmed)) {
				flush()
				title = trimmed
			} else {
				body.appendLine(line)
			}
		}
		flush()
		return chapters.ifEmpty { listOf(ChapterDraft("全部内容", text)) }
			.flatMap(::splitOversizedChapter)
	}

	private fun splitOversizedChapter(chapter: ChapterDraft): List<ChapterDraft> {
		if (chapter.content.length <= MAX_CHAPTER_CHARACTERS) return listOf(chapter)
		val sections = mutableListOf<ChapterDraft>()
		var remaining = chapter.content
		var part = 1
		while (remaining.length > MAX_CHAPTER_CHARACTERS) {
			val split = remaining.lastIndexOf("\n\n", MAX_CHAPTER_CHARACTERS).takeIf { it >= MAX_CHAPTER_CHARACTERS / 2 }
				?: remaining.lastIndexOf('\n', MAX_CHAPTER_CHARACTERS).takeIf { it >= MAX_CHAPTER_CHARACTERS / 2 }
				?: remaining.lastIndexOf(' ', MAX_CHAPTER_CHARACTERS).takeIf { it >= MAX_CHAPTER_CHARACTERS / 2 }
				?: MAX_CHAPTER_CHARACTERS
			sections += ChapterDraft("${chapter.title} · ${part++}", remaining.substring(0, split).trimEnd())
			remaining = remaining.substring(split).trimStart()
		}
		if (remaining.isNotBlank()) sections += ChapterDraft("${chapter.title} · $part", remaining)
		return sections
	}

	private fun isChapterHeading(line: String): Boolean {
		if (line.startsWith("chapter ", ignoreCase = true)) return true
		if (!line.startsWith("第")) return false
		val markerIndex = line.indexOfFirst { it in "章节卷部篇回" }
		return markerIndex >= 2 && (markerIndex == line.lastIndex || line[markerIndex + 1].isWhitespace())
	}

	private fun buildParsedBook(
		title: String,
		author: String?,
		format: BookFormat,
		drafts: List<ChapterDraft>,
	): ParsedBook {
		val canonical = buildString {
			append(format.name).append('\n').append(author.orEmpty().trim()).append('\n')
			drafts.forEach { append(it.title.trim()).append('\n').append(it.content.normalizeText()).append('\n') }
		}
		val bookId = stableId("book", canonical)
		val chapters = drafts.mapIndexed { index, draft ->
			val normalized = draft.content.normalizeText()
			ReaderChapter(
				id = stableId("chapter", "$bookId\n$index\n${draft.title.trim()}\n$normalized"),
				bookId = bookId,
				index = index,
				title = draft.title.trim(),
				content = normalized,
				anchorId = stableId("anchor", "$bookId\n$index\n$normalized"),
			)
		}
		return ParsedBook(ReaderBook(bookId, title, author, format), chapters)
	}

	private fun decodeText(bytes: ByteArray): String {
		val content = if (bytes.startsWith(UTF8_BOM)) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
		return try {
			StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(content)).toString()
		} catch (_: Exception) {
			String(content, charset("GB18030"))
		}
	}

	private fun safeArchivePath(base: String?, rawPath: String?): String? {
		val clean = rawPath?.substringBefore('#')?.substringBefore('?')?.replace('\\', '/')?.trim()
			?.takeIf { it.isNotBlank() && !it.startsWith('/') && !it.contains('\u0000') } ?: return null
		val parts = mutableListOf<String>()
		(base.orEmpty() + "/" + clean).split('/').forEach { part ->
			when (part) {
				"", "." -> Unit
				".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
				else -> parts += part
			}
		}
		return parts.joinToString("/").takeIf { it.isNotBlank() }
	}

	private fun stableId(namespace: String, value: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest("$namespace\u0000$value".toByteArray(Charsets.UTF_8))
		return digest.joinToString("") { "%02x".format(it) }
	}

	private fun String.normalizeText(): String = replace("\r\n", "\n")
		.replace('\r', '\n')
		.lines()
		.joinToString("\n") { it.trimEnd() }
		.trim()

	private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

	private data class ChapterDraft(val title: String, val content: String)

	internal companion object {
		internal const val MAX_CHAPTER_CHARACTERS = 200_000
		val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
	}
}
