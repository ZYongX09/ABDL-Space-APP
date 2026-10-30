package org.joinmastodon.android.novel.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.joinmastodon.android.api.CosMediaUpload
import org.joinmastodon.android.api.MastodonAPIController
import kotlinx.coroutines.CancellationException
import org.joinmastodon.android.novel.app.NovelSessionExpiredException
import org.joinmastodon.android.novel.app.NovelSessionRequests

/** Private account-owned pixels; never retains a provider grant, credential, or original EXIF. */
internal object NovelCoverImages {
    const val MAX_BYTES = 5L * 1024 * 1024
    fun select(context: Context, uri: Uri, folder: File): File {
        val resolver = context.contentResolver
        require(uri.scheme in setOf("content", "file")) { "请选择本地图片" }
        val declared = resolver.getType(uri)
        require(declared == null || declared in setOf("image/jpeg", "image/png")) { "封面仅支持 JPG / PNG" }
        // Copy while the picker grant is live; do not acquire or release another screen's persisted grant.
        folder.mkdirs()
        val source = File(folder, "${UUID.randomUUID()}.source")
        var output: File? = null
        try {
            resolver.openInputStream(uri)?.use { input -> source.outputStream().use { sink ->
                val bytes = ByteArray(32768); var total = 0L
                while (true) { val count = input.read(bytes); if (count < 0) break; total += count; require(total <= MAX_BYTES) { "封面不能超过 5 MB" }; sink.write(bytes, 0, count) }
                require(total > 0) { "图片为空" }
            } } ?: throw IOException("无法读取封面，请重新选择并允许读取图片")
            val bounds = bounds(source)
            require(bounds.outMimeType in setOf("image/jpeg", "image/png")) { "封面仅支持 JPG / PNG" }
            val png = bounds.outMimeType == "image/png"
            output = File(folder, "${UUID.randomUUID()}.${if (png) "png" else "jpg"}")
            val image = decode(source, 2048)
            try { output.outputStream().use { require(image.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)) { "图片编码失败" } } }
            finally { image.recycle() }
            require(output.length() in 1..MAX_BYTES) { "处理后的封面超过 5 MB，请选择较小图片" }
            return output
        } catch (error: Exception) { output?.delete(); throw error }
        finally { source.delete() }
    }
    private fun bounds(file: File) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true; BitmapFactory.decodeFile(file.path, this)
        require(outWidth > 0 && outHeight > 0 && outWidth <= 100_000 && outHeight <= 100_000) { "图片无效或尺寸过大" }
    }
    fun decode(file: File, maximum: Int = 512): Bitmap {
        val bounds = bounds(file)
        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > maximum) inSampleSize *= 2 }
        var image = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("图片解码失败")
        try {
            val matrix = Matrix()
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                2 -> matrix.setScale(-1f, 1f)
                3 -> matrix.setRotate(180f)
                4 -> matrix.setScale(1f, -1f)
                5 -> matrix.setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
                6 -> matrix.setRotate(90f)
                7 -> matrix.setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
                8 -> matrix.setRotate(-90f)
            }
            if (!matrix.isIdentity) {
                val next = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
                if (next !== image) image.recycle()
                image = next
            }
            return image
        } catch (error: Exception) { image.recycle(); throw error }
    }
}

internal fun interface NovelCoverUploader { suspend fun upload(file: File): String }
internal data class NovelCoverSession(val domain: String, val token: String, val authority: NovelSessionRequests.Authority)

/** Display only this message/structured fields, never the internal cause's message or stack. */
internal class NovelCoverUploadException(val failure: CosMediaUpload.UploadException) : IOException(coverFailureMessage(failure), failure)

private fun coverFailureMessage(error: CosMediaUpload.UploadException): String {
    val stage = when (error.stage) {
        CosMediaUpload.Stage.AUTHORIZE -> "授权"
        CosMediaUpload.Stage.PUT -> "传输"
        CosMediaUpload.Stage.COMPLETE -> "完成校验"
    }
    val category = when (error.category) {
        CosMediaUpload.Category.AUTHENTICATION -> "账号授权失效，请重新登录"
        CosMediaUpload.Category.PERMISSION -> "签名或权限校验失败"
        CosMediaUpload.Category.EXPIRED -> "上传授权过期或时间校验失败"
        CosMediaUpload.Category.CONFLICT -> "上传状态冲突"
        CosMediaUpload.Category.VERIFICATION -> "服务端对象校验失败"
        CosMediaUpload.Category.SERVER -> "服务暂时不可用"
        CosMediaUpload.Category.REDIRECT -> "服务返回不允许的重定向"
        CosMediaUpload.Category.INVALID_REQUEST -> "上传参数未通过校验"
        CosMediaUpload.Category.INVALID_RESPONSE -> "服务响应无效"
        CosMediaUpload.Category.NETWORK -> "网络连接失败"
        CosMediaUpload.Category.HTTP -> "HTTP 请求失败"
    }
    return "封面上传${stage}阶段失败（${category}${if (error.status > 0) "，HTTP ${error.status}" else ""}），本地图片已保留，请重试"
}

/** Uses the existing generic media contract, not avatar credentials or the /media MIME fallback. */
internal class NovelCoverUpload private constructor(
    private val domain: String, private val token: String, private val authority: NovelSessionRequests.Authority,
    client: OkHttpClient, private val io: CoroutineDispatcher,
) : NovelCoverUploader {
    private val client = client.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    override suspend fun upload(file: File): String = coroutineScope {
        authority.requireActive()
        val active = AtomicReference<Call?>()
        val result = suspendCancellableCoroutine<String> { continuation ->
            val job = launch(io) {
                var preparing = true
                try {
                    require(file.isFile && file.length() in 1..NovelCoverImages.MAX_BYTES) { "封面文件无效或超过 5 MB，请重新选择" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeFile(file.path, this) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in setOf("image/jpeg", "image/png")) { "请选择有效 JPG / PNG 封面" }
                    val body = RequestBody.create(MediaType.get(bounds.outMimeType), file)
                    val listener = CosMediaUpload.CallListener { call ->
                        active.getAndSet(call)?.let { NovelSessionRequests.unregister(authority, it) }
                        NovelSessionRequests.register(authority, call)
                        NovelSessionRequests.dispatch(authority, call) { if (!continuation.isActive) call.cancel() }
                    }
                    preparing = false
                    val auth = CosMediaUpload.authorize(client, domain, token, "generic", body, bounds.outWidth, bounds.outHeight, listener)
                    authority.requireActive()
                    if (HttpUrl.parse(auth.uploadUrl)?.isHttps != true || HttpUrl.parse(auth.publicUrl)?.isHttps != true)
                        throw CosMediaUpload.UploadException(CosMediaUpload.Stage.AUTHORIZE, 200, CosMediaUpload.Category.INVALID_RESPONSE, null)
                    CosMediaUpload.put(client, auth, body, listener)
                    authority.requireActive()
                    val attachment = CosMediaUpload.complete(client, domain, token, auth.uploadId, null, listener)
                    authority.requireActive()
                    val url = attachment.url?.takeIf { HttpUrl.parse(it)?.isHttps == true }
                        ?: throw CosMediaUpload.UploadException(CosMediaUpload.Stage.COMPLETE, 200, CosMediaUpload.Category.INVALID_RESPONSE, null)
                    if (continuation.isActive) continuation.resume(url)
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    val failure = when {
                        error is NovelSessionExpiredException -> error
                        !authority.isActive() -> NovelSessionExpiredException()
                        error is CosMediaUpload.UploadException -> NovelCoverUploadException(error)
                        // Cancellation IO keeps its type; local validation retains its safe local message.
                        error is IOException && active.get()?.isCanceled() == true -> error
                        preparing && error is IllegalArgumentException -> error
                        else -> IOException("封面上传准备失败，本地图片已保留，请重试", error)
                    }
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally { active.getAndSet(null)?.let { NovelSessionRequests.unregister(authority, it) } }
            }
            continuation.invokeOnCancellation { active.get()?.cancel(); job.cancel() }
        }
        // Fence a success queued for resumption before logout, not just the blocking worker.
        authority.requireActive()
        result
    }
    companion object {
        fun forRepository(repository: NovelEditorRepository): NovelCoverUploader {
            val session = repository.coverSession
            return NovelCoverUploader { file ->
                repository.requireActive()
                val pinned = session ?: throw IOException("账号授权不可用，请重新打开编辑器")
                NovelCoverUpload(pinned.domain, pinned.token, pinned.authority, MastodonAPIController.getHttpClient(), repository.io).upload(file)
                    .also { repository.requireActive() }
            }
        }
        internal fun forTests(domain: String, token: String, authority: NovelSessionRequests.Authority, client: OkHttpClient, io: CoroutineDispatcher = Dispatchers.IO): NovelCoverUploader =
            NovelCoverUpload(domain, token, authority, client, io)
    }
}
