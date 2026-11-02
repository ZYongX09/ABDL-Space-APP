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
import org.joinmastodon.android.R
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
        require(uri.scheme in setOf("content", "file")) { "novel_i18n_local_image_required" }
        val declared = resolver.getType(uri)
        require(declared == null || declared in setOf("image/jpeg", "image/png")) { "novel_i18n_cover_format" }
        // Copy while the picker grant is live; do not acquire or release another screen's persisted grant.
        folder.mkdirs()
        val source = File(folder, "${UUID.randomUUID()}.source")
        var output: File? = null
        try {
            resolver.openInputStream(uri)?.use { input -> source.outputStream().use { sink ->
                val bytes = ByteArray(32768); var total = 0L
                while (true) { val count = input.read(bytes); if (count < 0) break; total += count; require(total <= MAX_BYTES) { "novel_i18n_cover_size_limit" }; sink.write(bytes, 0, count) }
                require(total > 0) { "novel_i18n_empty_image" }
            } } ?: throw IOException("novel_i18n_cover_read_failed")
            val bounds = bounds(source)
            require(bounds.outMimeType in setOf("image/jpeg", "image/png")) { "novel_i18n_cover_format" }
            val png = bounds.outMimeType == "image/png"
            output = File(folder, "${UUID.randomUUID()}.${if (png) "png" else "jpg"}")
            val image = decode(source, 2048)
            try { output.outputStream().use { require(image.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)) { "novel_i18n_image_encode_failed" } } }
            finally { image.recycle() }
            require(output.length() in 1..MAX_BYTES) { "novel_i18n_processed_cover_limit" }
            return output
        } catch (error: Exception) { output?.delete(); throw error }
        finally { source.delete() }
    }
    private fun bounds(file: File) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true; BitmapFactory.decodeFile(file.path, this)
        require(outWidth > 0 && outHeight > 0 && outWidth <= 100_000 && outHeight <= 100_000) { "novel_i18n_image_dimensions" }
    }
    fun decode(file: File, maximum: Int = 512): Bitmap {
        val bounds = bounds(file)
        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > maximum) inSampleSize *= 2 }
        var image = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("novel_i18n_image_decode_failed")
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
internal class NovelCoverUploadException(val failure: CosMediaUpload.UploadException) : IOException("novel_cover_upload_failed", failure)

internal fun coverFailureMessage(context: Context, error: CosMediaUpload.UploadException): String {
    val stage = when (error.stage) {
        CosMediaUpload.Stage.AUTHORIZE -> R.string.novel_en_upload_stage_authorize
        CosMediaUpload.Stage.PUT -> R.string.novel_en_upload_stage_transfer
        CosMediaUpload.Stage.COMPLETE -> R.string.novel_en_upload_stage_complete
    }
    val category = when (error.category) {
        CosMediaUpload.Category.AUTHENTICATION -> R.string.novel_en_upload_authentication
        CosMediaUpload.Category.PERMISSION -> R.string.novel_en_upload_permission
        CosMediaUpload.Category.EXPIRED -> R.string.novel_en_upload_expired
        CosMediaUpload.Category.CONFLICT -> R.string.novel_en_upload_conflict
        CosMediaUpload.Category.VERIFICATION -> R.string.novel_en_upload_verification
        CosMediaUpload.Category.SERVER -> R.string.novel_en_upload_server
        CosMediaUpload.Category.REDIRECT -> R.string.novel_en_upload_redirect
        CosMediaUpload.Category.INVALID_REQUEST -> R.string.novel_en_upload_invalid_request
        CosMediaUpload.Category.INVALID_RESPONSE -> R.string.novel_en_upload_invalid_response
        CosMediaUpload.Category.NETWORK -> R.string.novel_en_upload_network
        CosMediaUpload.Category.HTTP -> R.string.novel_en_upload_http
    }
    return context.getString(R.string.novel_en_cover_upload_failed, context.getString(stage), context.getString(category),
        if (error.status > 0) ", HTTP ${error.status}" else "")
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
                    require(file.isFile && file.length() in 1..NovelCoverImages.MAX_BYTES) { "novel_i18n_cover_file_invalid" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeFile(file.path, this) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in setOf("image/jpeg", "image/png")) { "novel_i18n_valid_cover_required" }
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
                        else -> IOException("novel_i18n_cover_prepare_failed", error)
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
                val pinned = session ?: throw IOException("novel_i18n_cover_auth_unavailable")
                NovelCoverUpload(pinned.domain, pinned.token, pinned.authority, MastodonAPIController.getHttpClient(), repository.io).upload(file)
                    .also { repository.requireActive() }
            }
        }
        internal fun forTests(domain: String, token: String, authority: NovelSessionRequests.Authority, client: OkHttpClient, io: CoroutineDispatcher = Dispatchers.IO): NovelCoverUploader =
            NovelCoverUpload(domain, token, authority, client, io)
    }
}
