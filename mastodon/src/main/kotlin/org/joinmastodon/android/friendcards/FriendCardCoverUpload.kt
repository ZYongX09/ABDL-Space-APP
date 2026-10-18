package org.joinmastodon.android.friendcards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import org.joinmastodon.android.api.CosMediaUpload
import org.joinmastodon.android.api.MastodonAPIController
import org.joinmastodon.android.api.session.AccountSessionManager
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.RequestBody

/** Re-encodes pixels (including EXIF orientation), strips metadata, and keeps the generic upload ID. */
class FriendCardCoverUpload(private val context: Context, private val account: String) {
    companion object { const val MAX_BYTES = 10L * 1024 * 1024 }
    private val cancelled = AtomicBoolean()
    @Volatile private var call: Call? = null
    fun cancel() { cancelled.set(true); call?.cancel() }
    private fun check() {
        if (cancelled.get() || AccountSessionManager.getInstance().lastActiveAccountID != account || AccountSessionManager.getInstance().tryGetAccount(account) == null) throw IOException("封面上传已取消，请重新选择")
    }
    fun upload(uri: Uri): Pair<String, String> {
        val source = File.createTempFile("friend_cover_source", ".tmp", context.cacheDir)
        val clean = File.createTempFile("friend_cover_clean", ".jpg", context.cacheDir)
        var bitmap: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L
                while (true) { check(); val n = input.read(buffer); if (n < 0) break; total += n; if (total > MAX_BYTES) throw IOException("封面图片不能超过10 MiB"); output.write(buffer, 0, n) }
            } } ?: throw IOException("无法读取封面图片")
            check()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("请选择有效图片")
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 4096) inSampleSize *= 2
            }
            var image = BitmapFactory.decodeFile(source.path, options) ?: throw IOException("图片解码失败")
            bitmap = image; check()
            val transform = Matrix()
            when (ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                2 -> transform.setScale(-1f, 1f)
                3 -> transform.setRotate(180f)
                4 -> transform.setScale(1f, -1f)
                5 -> transform.setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
                6 -> transform.setRotate(90f)
                7 -> transform.setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
                8 -> transform.setRotate(-90f)
            }
            if (!transform.isIdentity) {
                val next = Bitmap.createBitmap(image, 0, 0, image.width, image.height, transform, true)
                if (next !== image) image.recycle(); image = next; bitmap = next
            }
            val scale = minOf(1f, 2048f / maxOf(image.width, image.height))
            if (scale < 1f) {
                val next = Bitmap.createScaledBitmap(image, maxOf(1, (image.width * scale).toInt()), maxOf(1, (image.height * scale).toInt()), true)
                if (next !== image) image.recycle(); image = next; bitmap = next
            }
            clean.outputStream().use { if (!image.compress(Bitmap.CompressFormat.JPEG, 88, it)) throw IOException("图片压缩失败") }
            if (clean.length() !in 1..MAX_BYTES) throw IOException("压缩后的封面超过10 MiB")
            check()
            val session = AccountSessionManager.getInstance().tryGetAccount(account) ?: throw IOException("账号已退出")
            val client = MastodonAPIController.getHttpClient().newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build()
            val body = RequestBody.create(MediaType.get("image/jpeg"), clean)
            val listener = CosMediaUpload.CallListener { call = it; check() }
            val auth = CosMediaUpload.authorize(client, session.domain, session.token.accessToken, "generic", body, image.width, image.height, listener)
            check(); CosMediaUpload.put(client, auth, body, listener); check()
            val attachment = CosMediaUpload.complete(client, session.domain, session.token.accessToken, auth.uploadId, null, listener)
            val url = attachment.url ?: auth.publicUrl
            if (auth.uploadId.isNullOrBlank() || url.isNullOrBlank()) throw IOException("封面上传响应不完整")
            return auth.uploadId to url
        } finally { bitmap?.takeUnless { it.isRecycled }?.recycle(); source.delete(); clean.delete(); call = null }
    }
}
