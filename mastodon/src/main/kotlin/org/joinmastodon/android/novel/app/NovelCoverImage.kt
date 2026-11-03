package org.joinmastodon.android.novel.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.squareup.otto.Subscribe
import java.security.MessageDigest
import java.util.UUID
import me.grishka.appkit.imageloader.ImageCache
import me.grishka.appkit.imageloader.ImageLoaderCallback
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import okhttp3.HttpUrl
import org.joinmastodon.android.E
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.events.AccountLoggedOutEvent
import org.joinmastodon.android.novel.NovelAccountDataCleaner

internal val LocalNovelCoverScope = staticCompositionLocalOf<NovelCoverScope?> { null }

/** One store/editor host owns decoded covers, not a lazy item's disposable AndroidView. */
@Composable
internal fun NovelCoverProvider(accountId: String, content: @Composable () -> Unit) {
    // Explicitly supplied scopes are used by offline rendering tests and nested novel hosts.
    if (LocalNovelCoverScope.current?.accountId == accountId) { content(); return }
    val session = AccountSessionManager.getInstance().tryGetAccount(accountId)
    val generation = NovelAccountDataCleaner.captureGeneration(accountId)
    val token = session?.token?.accessToken
    val scope = remember(accountId, session, generation, token) {
        NovelCoverScope(accountId, {
            session != null && AccountSessionManager.getInstance().tryGetAccount(accountId) === session &&
                session.token.accessToken == token && NovelAccountDataCleaner.isGenerationValid(accountId, generation)
        })
    }
    DisposableEffect(scope) {
        E.register(scope)
        onDispose { E.unregister(scope); scope.retire() }
    }
    CompositionLocalProvider(LocalNovelCoverScope provides scope, content = content)
}

/** Full URLs are opaque capabilities. Never strip a signature/query or alias two cover versions. */
internal data class NovelCoverKey(val entity: String, val projection: String, val url: String)
internal fun novelCoverKey(entity: String, projection: String, url: String?): NovelCoverKey? =
    url?.takeIf { HttpUrl.parse(it)?.isHttps == true }?.let { NovelCoverKey(entity, projection, it) }

/** Main-thread, byte- and count-bounded LRU. No bitmap is recycled while a draw frame can own it. */
internal class NovelCoverScope(
    val accountId: String,
    private val authority: () -> Boolean,
    private val maxBytes: Long = 12L * 1024 * 1024,
    private val maxEntries: Int = 64,
) {
    val namespace: String = UUID.randomUUID().toString()
    var active by mutableStateOf(true); private set
    private data class Cached(val image: Drawable, val bytes: Long)
    private val images = LinkedHashMap<NovelCoverKey, Cached>(16, .75f, true)
    private val failures = LinkedHashMap<NovelCoverKey, Int>(16, .75f, true)
    private val bindings = mutableSetOf<NovelCoverBinding>()
    private val loaders = mutableSetOf<AppKitNovelCoverLoader>()
    internal fun loader(context: Context) = loaders.firstOrNull() ?: AppKitNovelCoverLoader(context).also { loaders += it }
    internal var retainedBytes = 0L; private set
    internal val retainedCount get() = images.size

    fun isActive(): Boolean {
        if (active && !authority()) retire()
        return active
    }
    fun retire() {
        if (!active) return
        active = false
        images.clear(); failures.clear(); retainedBytes = 0
        bindings.toList().forEach { it.retire() }
        bindings.clear()
        loaders.forEach { it.retire() }; loaders.clear()
    }
    @Subscribe fun onLogout(event: AccountLoggedOutEvent) {
        if (event.id == accountId) {
            if (Looper.myLooper() == Looper.getMainLooper()) retire()
            else Handler(Looper.getMainLooper()).post { retire() }
        }
    }
    internal fun observe(binding: NovelCoverBinding) { bindings += binding }
    internal fun remove(binding: NovelCoverBinding) { bindings -= binding }
    internal fun image(key: NovelCoverKey): Drawable? {
        if (!isActive()) return null
        val image = images[key]?.image
        if (!usableCover(image)) { forget(key); return null }
        return independentCover(requireNotNull(image))
    }
    internal fun remember(key: NovelCoverKey, image: Drawable) {
        // Animated drawables may retain an unbounded decoded frame set. Only static bitmap pixels
        // with an exact allocationByteCount belong in this reusable memory budget.
        if (!isActive() || !usableCover(image) || image !is BitmapDrawable) return
        forget(key)
        val canonical = independentCover(image) ?: return
        val bytes = coverBytes(canonical)
        if (bytes > maxBytes) return
        images[key] = Cached(canonical, bytes); retainedBytes += bytes
        while (retainedBytes > maxBytes || images.size > maxEntries) forget(images.keys.first())
    }
    internal fun forget(key: NovelCoverKey) {
        images.remove(key)?.let { retainedBytes -= it.bytes }
    }
    internal fun forgetEntity(entity: String) {
        images.keys.filter { it.entity == entity }.toList().forEach(::forget)
    }
    internal fun failureCount(key: NovelCoverKey) = failures.getOrDefault(key, 0)
    internal fun canLoad(key: NovelCoverKey) = isActive() && failureCount(key) < 3
    internal fun failed(key: NovelCoverKey) {
        forget(key)
        failures[key] = failures.getOrDefault(key, 0) + 1
        while (failures.size > maxEntries) failures.remove(failures.keys.first())
    }
}

private fun usableCover(image: Drawable?) = image != null &&
    (image !is BitmapDrawable || !image.bitmap.isRecycled)
private fun independentCover(image: Drawable): Drawable? = image.constantState?.newDrawable()?.mutate()

private fun coverBytes(image: Drawable): Long = if (image is BitmapDrawable) image.bitmap.allocationByteCount.toLong()
    else (image.intrinsicWidth.coerceAtLeast(1).toLong() * image.intrinsicHeight.coerceAtLeast(1) * 4).coerceAtLeast(4096)

/** AppKit must not coalesce disk/in-flight/memory requests across accounts, books or projections. */
internal class NovelCoverRequest(scope: NovelCoverScope, val coverKey: NovelCoverKey, private val attempt: String = "") : UrlImageLoaderRequest(coverKey.url, 500, 700) {
    private val source = MessageDigest.getInstance("SHA-256").digest(
        listOf(scope.namespace, coverKey.entity, coverKey.projection, coverKey.url)
            .joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val cacheKey = MessageDigest.getInstance("SHA-256").digest((source + attempt).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val scopeOwner = scope
    internal fun nextAttempt() = NovelCoverRequest(scopeOwner, coverKey, UUID.randomUUID().toString().replace("-", ""))
    override fun getMemoryCacheKey() = "novel-cover-500x700-$cacheKey"
    override fun getDiskCacheKey() = cacheKey
    override fun sourcesEqual(other: ImageLoaderRequest?) = other is NovelCoverRequest && cacheKey == other.cacheKey
    override fun equals(other: Any?) = other is NovelCoverRequest && cacheKey == other.cacheKey
    override fun hashCode() = cacheKey.hashCode()
    override fun toString() = "NovelCoverRequest" // Do not log signed URLs or account identifiers.
}

internal fun interface NovelCoverCancellation {
    fun cancel()
    /** Scrolling may immediately subscribe again; authority/URL changes use cancel() instead. */
    fun detached() = cancel()
}
internal val LocalNovelCoverLoader = staticCompositionLocalOf<NovelCoverLoader?> { null }

internal fun interface NovelCoverLoader {
    /** null is a completed failure, not a loading placeholder; callbacks may arrive after cancel. */
    fun load(request: NovelCoverRequest, result: (Drawable?) -> Unit): NovelCoverCancellation
}

internal class AppKitNovelCoverLoader(context: Context) : NovelCoverLoader {
    private val cache = ImageCache.getInstance(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val flights = LinkedHashMap<NovelCoverRequest, Flight>()
    private class Flight(val request: NovelCoverRequest) {
        val subscribers = mutableSetOf<(Drawable?) -> Unit>()
        var pending: ImageCache.PendingImageRequest? = null
        var detach: Runnable? = null
        var timeout: Runnable? = null
    }
    override fun load(request: NovelCoverRequest, result: (Drawable?) -> Unit): NovelCoverCancellation {
        var flight = flights[request]
        if (flight == null) {
            // A cancelled AppKit HTTP request may still own currentlyLoading until its worker runs.
            // A NEW flight must never join that doomed source; subscribers within a flight still coalesce.
            while (flights.size >= 16) terminate(flights.keys.first(), flights.values.first(), null)
            flight = Flight(request.nextAttempt())
            flights[request] = flight
        }
        val current = flight
        current.detach?.let(main::removeCallbacks); current.detach = null
        current.subscribers += result
        if (current.pending == null) {
            current.timeout = Runnable { terminate(request, current, null) }.also { main.postDelayed(it, 30_000) }
            current.pending = cache.get(current.request, null, object : ImageLoaderCallback {
                override fun onImageLoaded(request: ImageLoaderRequest, image: Drawable) = deliver(image)
                override fun onImageLoadingFailed(request: ImageLoaderRequest, error: Throwable?) = deliver(null)
                private fun deliver(image: Drawable?) {
                    main.post { cache.remove(current.request); terminate(request, current, image) }
                }
            }, true)
        }
        return object : NovelCoverCancellation {
            override fun cancel() = unsubscribe(false)
            override fun detached() = unsubscribe(true)
            private fun unsubscribe(scrolling: Boolean) {
                current.subscribers -= result
                if (flights[request] !== current || current.subscribers.isNotEmpty()) return
                if (scrolling) {
                    current.detach?.let(main::removeCallbacks)
                    current.detach = Runnable { terminate(request, current, null) }.also { main.postDelayed(it, 500) }
                } else terminate(request, current, null)
            }
        }
    }
    private fun terminate(key: NovelCoverRequest, flight: Flight, image: Drawable?) {
        if (flights[key] !== flight) return
        flights.remove(key)
        flight.detach?.let(main::removeCallbacks); flight.timeout?.let(main::removeCallbacks)
        // Remove from our map BEFORE cancel: even a reentrant subscription gets a fresh source.
        flight.pending?.cancel(); flight.pending = null
        cache.remove(flight.request)
        flight.subscribers.toList().forEach { it(image) }; flight.subscribers.clear()
    }
    fun retire() {
        flights.keys.toList().forEach { key -> flights[key]?.let { terminate(key, it, null) } }
    }
}

/** Idempotent binding: recomposition is NOT a new load, detach is NOT an image failure. */
internal class NovelCoverBinding(
    private val show: (Drawable?, Boolean) -> Unit,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private var scope: NovelCoverScope? = null
    private var key: NovelCoverKey? = null
    private var entity: String? = null
    private var loader: NovelCoverLoader? = null
    private var image: Drawable? = null
    private var pending: NovelCoverCancellation? = null
    private var retry: Runnable? = null
    private var nonce = 0L
    private var loading = false
    private var attached = false
    private var released = false

    fun bind(nextScope: NovelCoverScope?, nextEntity: String, next: NovelCoverKey?, nextLoader: NovelCoverLoader) {
        if (released) return
        if (scope === nextScope && entity == nextEntity && key == next && loader === nextLoader) {
            validate()
            return
        }
        cancel()
        if (scope === nextScope && entity == nextEntity && key != next) scope?.forgetEntity(nextEntity)
        scope?.remove(this)
        scope = nextScope; entity = nextEntity; key = next; loader = nextLoader
        scope?.observe(this)
        image = next?.let { scope?.image(it) }
        show(image, false) // Clears a previous book/URL/authority synchronously, before the next draw.
        if (attached) load()
    }
    fun attach() {
        if (released) return
        attached = true
        if (!validate()) return
        if (!usableCover(image)) image = key?.let { scope?.image(it) }
        show(image, false)
        load()
    }
    fun detach() {
        attached = false
        cancel(scrolling = true)
        // Keep only this exact binding's successfully drawn pixels, never a pending placeholder.
        show(image, false)
    }
    fun release() {
        detach(); scope?.remove(this); scope = null; key = null; image = null; released = true
        show(null, false)
    }
    fun retire() {
        val hadImage = image != null
        cancel(); image = null
        if (hadImage) show(null, false)
    }
    fun validate(): Boolean {
        if (key == null || scope?.isActive() != true) { retire(); return false }
        if (image != null && !usableCover(image)) { image = null; show(null, false); load() }
        return true
    }
    private fun cancel(scrolling: Boolean = false) {
        nonce++; loading = false
        retry?.let(main::removeCallbacks); retry = null
        val old = pending; pending = null
        if (scrolling) old?.detached() else old?.cancel()
    }
    private fun load() {
        val owner = scope ?: return
        val expected = key ?: return
        if (!attached || released || loading || usableCover(image) || !owner.canLoad(expected)) return
        val token = ++nonce
        loading = true
        val handle = requireNotNull(loader).load(NovelCoverRequest(owner, expected)) result@{ result ->
            if (token != nonce || !loading || !attached || released || scope !== owner || key != expected || !owner.isActive()) return@result
            loading = false; pending = null
            image = result?.takeIf(::usableCover)?.let(::independentCover)
            if (image != null) {
                owner.remember(expected, requireNotNull(image))
                show(image, true)
            } else {
                owner.failed(expected); show(null, false)
                if (owner.canLoad(expected)) {
                    retry = Runnable { retry = null; load() }.also { main.postDelayed(it, 600) }
                }
            }
        }
        // A memory/synthetic loader may complete synchronously before returning its handle.
        if (token == nonce && loading) pending = handle else handle.cancel()
    }
}

internal class NovelCoverImageView(context: Context) : ImageView(context) {
    internal val coverLoader: NovelCoverLoader = AppKitNovelCoverLoader(context)
    internal val binding = NovelCoverBinding(show = { image, animate ->
        animate().cancel()
        (drawable as? Animatable)?.stop()
        setImageDrawable(image)
        (image as? Animatable)?.let { if (isAttachedToWindow) it.start() else it.stop() }
        alpha = 1f
        if (animate && image != null && isAttachedToWindow) {
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        }
    })
    init { scaleType = ScaleType.CENTER_CROP }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); binding.attach() }
    override fun onDetachedFromWindow() { binding.detach(); (drawable as? Animatable)?.stop(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) { binding.validate(); super.onDraw(canvas) }
}

@Composable
internal fun NovelRemoteCover(entity: String, projection: String, url: String?, modifier: Modifier, description: String? = null) {
    val scope = LocalNovelCoverScope.current
    val loader = LocalNovelCoverLoader.current
    // Observe retirement so a native view cannot survive logout until an unrelated model update.
    val permitted = scope?.active == true
    val key = if (permitted) novelCoverKey(entity, projection, url) else null
    AndroidView(factory = { context -> NovelCoverImageView(context) }, modifier = modifier,
        update = { view ->
            view.importantForAccessibility = if (description == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            view.contentDescription = description
            view.binding.bind(scope, entity, key, loader ?: scope?.loader(view.context) ?: view.coverLoader)
        }, onRelease = { it.binding.release() })
}

