package org.joinmastodon.android.novel.app

import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.joinmastodon.android.api.MastodonAPIController
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.novel.app.models.ChapterBody
import org.joinmastodon.android.novel.app.models.NovelJson
import org.joinmastodon.android.novel.app.models.Progress
import org.joinmastodon.android.novel.app.models.Work
import org.json.JSONObject

/** Cancellation for synchronous IO consumers. A cancelled token cannot be reused. */
class NovelRequestCancellation {
    @Volatile var isCancelled: Boolean = false
        private set
    private val calls = mutableSetOf<Call>()
    @Synchronized fun cancel() { isCancelled = true; calls.toList().forEach { it.cancel() }; calls.clear() }
    @Synchronized internal fun attach(call: Call) {
        if (isCancelled) { call.cancel(); throw InterruptedIOException("Novel request cancelled") }
        calls.add(call)
    }
    @Synchronized internal fun detach(call: Call) { calls.remove(call) }
}

class NovelAppApiException(
    val status: Int, val code: String, val payload: JSONObject?, message: String,
) : IOException(message)

/** Injectable transport seam. Suspend cancellation always cancels its HTTP call. */
interface NovelAppTransport {
    suspend fun raw(method: String, path: String, body: JSONObject? = null, idempotencyKey: String? = null): JSONObject
}

class NovelAppApi private constructor(
    private val baseUrl: String, private val token: String, factory: Call.Factory,
    private val authority: NovelSessionRequests.Authority,
) : NovelAppTransport {
    private val calls: Call.Factory = if (factory is OkHttpClient) factory.newBuilder()
        .cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build() else factory

    private constructor(session: AccountSession, accessToken: String) : this(
        BASE_URL, accessToken, MastodonAPIController.getHttpClient(), NovelSessionRequests.capture(session, accessToken),
    )
    constructor(session: AccountSession) : this(session, session.token.accessToken)

    /** Loopback-only injection; tests still pass through the real captured account generation gate. */
    internal constructor(baseUrl: String, token: String, factory: Call.Factory, allowHttpForTests: Boolean, authority: NovelSessionRequests.Authority) : this(baseUrl.trimEnd('/'), token, factory, authority) {
        val url = requireNotNull(okhttp3.HttpUrl.parse(baseUrl))
        require(url.host() in setOf("localhost", "127.0.0.1", "::1") && (url.isHttps || allowHttpForTests))
    }

    fun isSessionActive(): Boolean = authority.isActive()
    fun requireSessionActive() = authority.requireActive()

    override suspend fun raw(method: String, path: String, body: JSONObject?, idempotencyKey: String?): JSONObject {
        authority.requireActive()
        val call = calls.newCall(buildRequest(method, path, body, idempotencyKey))
        NovelSessionRequests.register(authority, call)
        try {
            val value = suspendCancellableCoroutine<JSONObject> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    NovelSessionRequests.dispatch(authority, call) {
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                val error = try { authority.requireActive(); e } catch (expired: NovelSessionExpiredException) { expired }
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                            override fun onResponse(call: Call, response: Response) {
                                try {
                                    val result = response.use { authority.requireActive(); decode(it) }
                                    authority.requireActive()
                                    if (continuation.isActive) continuation.resume(result)
                                } catch (error: Exception) {
                                    val failure = try { authority.requireActive(); error } catch (expired: NovelSessionExpiredException) { expired }
                                    if (continuation.isActive) continuation.resumeWithException(failure)
                                }
                            }
                        })
                    }
                } catch (error: Exception) {
                    call.cancel()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            // Also fence a response already queued for resumption when logout wins the dispatcher race.
            authority.requireActive()
            return value
        } finally { NovelSessionRequests.unregister(authority, call) }
    }

    /** Blocking helper: use only from an IO dispatcher. Persist caller keys for retriable submissions. */
    @JvmOverloads
    fun request(method: String, path: String, body: JSONObject? = null, idempotencyKey: String? = null, cancellation: NovelRequestCancellation? = null): JSONObject {
        authority.requireActive()
        val call = calls.newCall(buildRequest(method, path, body, idempotencyKey))
        NovelSessionRequests.register(authority, call)
        try {
            cancellation?.attach(call)
            // Never hold the logout lock during blocking IO: revoke must be able to cancel it.
            NovelSessionRequests.dispatch(authority, call) { }
            val value = call.execute().use { authority.requireActive(); decode(it) }
            authority.requireActive()
            if (cancellation?.isCancelled == true || call.isCanceled()) throw InterruptedIOException("Novel request cancelled")
            return value
        } catch (error: IOException) {
            authority.requireActive()
            throw error
        } finally { cancellation?.detach(call); NovelSessionRequests.unregister(authority, call) }
    }

    @JvmOverloads fun getWork(id: String, cancellation: NovelRequestCancellation? = null): Work = NovelJson.work(request("GET", "/works/${segment(id)}", cancellation = cancellation))
    @JvmOverloads fun getOwnerWork(id: String, cancellation: NovelRequestCancellation? = null): Work = NovelJson.work(request("GET", "/owner/works/${segment(id)}", cancellation = cancellation))
    @JvmOverloads fun getChapter(workId: String, chapterId: String, releaseId: String? = null, cancellation: NovelRequestCancellation? = null): ChapterBody = NovelJson.chapterBody(request("GET", "/works/${segment(workId)}/chapters/${segment(chapterId)}${query("release_id" to releaseId)}", cancellation = cancellation))
    @JvmOverloads fun getOwnerChapter(workId: String, chapterId: String, submissionId: String? = null, cancellation: NovelRequestCancellation? = null): ChapterBody = NovelJson.chapterBody(request("GET", "/owner/works/${segment(workId)}/chapters/${segment(chapterId)}${query("submission_id" to submissionId)}", cancellation = cancellation))
    @JvmOverloads fun getProgress(workId: String, cancellation: NovelRequestCancellation? = null): Progress? = request("GET", "/progress/${segment(workId)}", cancellation = cancellation).optJSONObject("progress")?.let(NovelJson::progress)
    @JvmOverloads fun putProgress(workId: String, progress: Progress, idempotencyKey: String = UUID.randomUUID().toString(), cancellation: NovelRequestCancellation? = null): Progress = NovelJson.progress(request("PUT", "/progress/${segment(workId)}", progress.toJson(), idempotencyKey, cancellation).getJSONObject("progress"))
    @JvmOverloads fun recordRead(workId: String, chapterId: String, releaseId: String, idempotencyKey: String = UUID.randomUUID().toString(), cancellation: NovelRequestCancellation? = null): Boolean = request("POST", "/works/${segment(workId)}/read", JSONObject().put("chapter_id", chapterId).put("release_id", releaseId), idempotencyKey, cancellation).optBoolean("counted")

    private fun buildRequest(method: String, path: String, body: JSONObject?, key: String?): Request {
        val verb = method.uppercase(Locale.ROOT)
        require(verb in setOf("GET", "POST", "PUT", "DELETE")) { "Unsupported novel method" }
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('#') && !path.contains('\\')) { "Use an app-relative path" }
        val decodedPath = java.net.URLDecoder.decode(path.substringBefore('?'), "UTF-8")
        require(decodedPath.split('/').none { it == "." || it == ".." } && !decodedPath.contains('\\')) { "Invalid app path" }
        val json = body?.toString() ?: "{}"
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "Novel request exceeds 2 MiB" }
        require(verb != "GET" || body == null) { "GET cannot have a JSON body" }
        val builder = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token")
            .header("Accept", "application/json").header("Cache-Control", "no-store")
        if (verb == "GET") builder.get() else {
            val operation = key ?: UUID.randomUUID().toString()
            require(runCatching { UUID.fromString(operation).toString() == operation.lowercase(Locale.ROOT) }.getOrDefault(false)) { "Idempotency-Key must be a UUID" }
            builder.header("Idempotency-Key", operation).method(verb, RequestBody.create(JSON, json))
        }
        return builder.build()
    }

    private fun decode(response: Response): JSONObject = response.use {
        if (it.priorResponse() != null || it.code() in 300..399) throw IOException("Novel API redirects are not allowed")
        val body = it.body() ?: throw IOException("Novel API returned no response")
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Novel response exceeds 10 MiB")
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1L) && source.buffer().size() > MAX_RESPONSE_BYTES) throw IOException("Novel response exceeds 10 MiB")
        val text = source.readUtf8()
        val parsed = runCatching { JSONObject(text) }.getOrNull()
        if (!it.isSuccessful) throw NovelAppApiException(it.code(), parsed?.optString("code")?.ifBlank { "http_${it.code()}" } ?: "http_${it.code()}", parsed, parsed?.optString("error")?.ifBlank { null } ?: "请求失败（${it.code()}）")
        return parsed ?: throw IOException("Novel API returned invalid JSON")
    }

    companion object {
        const val BASE_URL = "https://api.abdl-space.top/api/v1/novels/app"
        const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024
        private val JSON = MediaType.parse("application/json; charset=utf-8")
        @JvmStatic fun segment(value: String): String { require(value.isNotBlank()); return URLEncoder.encode(value, "UTF-8").replace("+", "%20") }
        @JvmStatic fun query(vararg values: Pair<String, String?>): String = values.filter { !it.second.isNullOrBlank() }.joinToString("&", prefix = "?") { segment(it.first) + "=" + segment(it.second!!) }.let { if (it == "?") "" else it }
    }
}
