package org.joinmastodon.android.novel

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

data class NovelDocument(val displayName: String, val format: String, val mimeType: String)

class NovelDocumentResolver(
	private val getType: (Uri) -> String?,
	private val getDisplayName: (Uri) -> String?,
) {
	constructor(resolver: ContentResolver) : this(
		resolver::getType,
		{ uri -> resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
		} },
	)

	fun resolve(uri: Uri): NovelDocument {
		val displayName = getDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
		val mimeType = getType(uri)?.lowercase()
		val format = when {
			mimeType == "application/epub+zip" -> "epub"
			mimeType == "text/plain" -> "txt"
			displayName.lowercase().endsWith(".epub") -> "epub"
			displayName.lowercase().endsWith(".txt") -> "txt"
			else -> error("无法识别小说文件类型")
		}
		return NovelDocument(displayName.substringBeforeLast('.').ifBlank { "未命名小说" }, format, if (format == "epub") "application/epub+zip" else "text/plain")
	}
}
