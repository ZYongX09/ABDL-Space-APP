package org.joinmastodon.android.novel.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    // Completed disk sources outlive decoded-pixel eviction, but never their account/entity lease.
    private data class CompletedSource(val generation: Long, val diskKey: String)
    private val sources = LinkedHashMap<NovelCoverKey, CompletedSource>(16, .75f, true)
    private val generations = LinkedHashMap<String, Long>(16, .75f, true)
    private val transitions = LinkedHashMap<String, NovelCoverKey?>()
    private var nextGeneration = 0L
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
        images.clear(); failures.clear(); sources.clear(); generations.clear(); transitions.clear(); retainedBytes = 0
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
        failures.remove(key)
        val canonical = independentCover(image) ?: return
        val bytes = coverBytes(canonical)
        if (bytes > maxBytes) return
        images[key] = Cached(canonical, bytes); retainedBytes += bytes
        while (retainedBytes > maxBytes || images.size > maxEntries) forget(images.keys.first())
    }
    internal fun forget(key: NovelCoverKey) {
        images.remove(key)?.let { retainedBytes -= it.bytes }
    }
    private fun forgetEntityData(entity: String) {
        images.keys.filter { it.entity == entity }.toList().forEach(::forget)
        sources.keys.filter { it.entity == entity }.toList().forEach(sources::remove)
    }
    internal fun forgetEntity(entity: String, next: NovelCoverKey?) {
        // Two mounted views may observe the same metadata update; advance its lease only once.
        if (transitions.containsKey(entity) && transitions[entity] == next) return
        forgetEntityData(entity)
        transitions[entity] = next
        generations[entity] = ++nextGeneration
        trimGenerations()
    }
    internal fun generation(entity: String): Long {
        val generation = generations.getOrPut(entity) { ++nextGeneration }
        trimGenerations(entity)
        return generation
    }
    private fun trimGenerations(retain: String? = null) {
        val observed = bindings.mapNotNullTo(mutableSetOf()) { it.observedEntity() }
        retain?.let(observed::add)
        while (generations.size > maxEntries * 2) {
            val entity = generations.keys.firstOrNull { it !in observed } ?: return
            generations.remove(entity); transitions.remove(entity); forgetEntityData(entity)
        }
    }
    internal fun accepts(key: NovelCoverKey, generation: Long) = isActive() && generations[key.entity] == generation
    internal fun completedSource(key: NovelCoverKey, generation: Long): String? =
        sources[key]?.takeIf { accepts(key, generation) && it.generation == generation }?.diskKey
    internal fun loaded(key: NovelCoverKey, generation: Long, diskKey: String, image: Drawable) {
        if (!accepts(key, generation)) return
        remember(key, image)
        failures.remove(key)
        if (image is BitmapDrawable && usableCover(image)) {
            sources[key] = CompletedSource(generation, diskKey)
            while (sources.size > maxEntries) sources.remove(sources.keys.first())
        }
    }
    internal fun discardSource(key: NovelCoverKey, generation: Long, diskKey: String) {
        if (sources[key]?.let { it.generation == generation && it.diskKey == diskKey } == true) sources.remove(key)
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
internal class NovelCoverRequest(
    internal val scopeOwner: NovelCoverScope,
    val coverKey: NovelCoverKey,
    private val attempt: String = "",
    internal val generation: Long = scopeOwner.generation(coverKey.entity),
    private val completedDiskKey: String? = null,
) : UrlImageLoaderRequest(Bitmap.Config.ARGB_8888, 500, 700, emptyList(), Uri.parse(coverKey.url)) {
    private val source = MessageDigest.getInstance("SHA-256").digest(
        listOf(scopeOwner.namespace, coverKey.entity, coverKey.projection, coverKey.url, generation.toString())
            .joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val cacheKey = MessageDigest.getInstance("SHA-256").digest((source + attempt).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun nextAttempt() = NovelCoverRequest(scopeOwner, coverKey, UUID.randomUUID().toString().replace("-", ""),
        generation, scopeOwner.completedSource(coverKey, generation))
    internal fun isAuthorized() = scopeOwner.accepts(coverKey, generation)
    override fun getMemoryCacheKey() = "novel-cover-500x700-$cacheKey"
    override fun getDiskCacheKey() = completedDiskKey ?: cacheKey
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
    /** A shared flight records one result, regardless of how many views subscribe to it. */
    fun recordsScopeResults(): Boolean = false
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
    override fun recordsScopeResults() = true
    override fun load(request: NovelCoverRequest, result: (Drawable?) -> Unit): NovelCoverCancellation {
        if (!request.isAuthorized()) { result(null); return NovelCoverCancellation {} }
        request.scopeOwner.image(request.coverKey)?.let { image ->
            result(image)
            return NovelCoverCancellation {}
        }
        var flight = flights[request]
        if (flight == null) {
            // Only a completed disk source may be reused. Cancelled/failed sources retain fresh
            // attempt keys so a reattachment cannot join AppKit's asynchronously cancelling worker.
            while (flights.size >= 16) terminate(flights.keys.first(), flights.values.first(), null)
            flight = Flight(request.nextAttempt())
            flights[request] = flight
        }
        val current = flight
        current.detach?.let(main::removeCallbacks); current.detach = null
        current.subscribers += result
        if (current.pending == null) {
            current.timeout = Runnable { terminate(request, current, null, completed = true) }.also { main.postDelayed(it, 30_000) }
            current.pending = cache.get(current.request, null, object : ImageLoaderCallback {
                override fun onImageLoaded(request: ImageLoaderRequest, image: Drawable) = deliver(image)
                override fun onImageLoadingFailed(request: ImageLoaderRequest, error: Throwable?) = deliver(null)
                private fun deliver(image: Drawable?) {
                    main.post { terminate(request, current, image, completed = true) }
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
    private fun terminate(key: NovelCoverRequest, flight: Flight, image: Drawable?, completed: Boolean = false) {
        if (flights[key] !== flight) return
        flights.remove(key)
        flight.detach?.let(main::removeCallbacks); flight.timeout?.let(main::removeCallbacks)
        val authorized = flight.request.isAuthorized()
        val loaded = image?.takeIf { authorized && usableCover(it) }?.let(::independentCover)
        if (completed && authorized) {
            val owner = flight.request.scopeOwner
            val cover = flight.request.coverKey
            if (loaded != null) owner.loaded(cover, flight.request.generation, flight.request.diskCacheKey, loaded)
            else {
                owner.discardSource(cover, flight.request.generation, flight.request.diskCacheKey)
                owner.failed(cover)
            }
        }
        // Publish accepted pixels BEFORE cancelling/notifying, even if a lazy row is briefly gone.
        // A cancelled/invalidated source is never accepted into the scope or the completed map.
        flight.pending?.cancel(); flight.pending = null
        if (!completed) flight.request.scopeOwner.discardSource(flight.request.coverKey, flight.request.generation, flight.request.diskCacheKey)
        cache.remove(flight.request)
        flight.subscribers.toList().forEach { it(loaded) }; flight.subscribers.clear()
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
    private var detachedPending: NovelCoverCancellation? = null
    private var retry: Runnable? = null
    private var nonce = 0L
    private var loading = false
    private var attached = false
    private var released = false

    internal fun observedEntity(): String? = if (released) null else entity

    fun bind(nextScope: NovelCoverScope?, nextEntity: String, next: NovelCoverKey?, nextLoader: NovelCoverLoader) {
        if (released) return
        if (scope === nextScope && entity == nextEntity && key == next && loader === nextLoader) {
            validate()
            return
        }
        cancel()
        // Record every newly observed identity, including a fresh factory after navigation.
        // Otherwise a historical A -> B transition could suppress invalidation on remount A -> B.
        nextScope?.forgetEntity(nextEntity, next)
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
        if (scrolling) {
            old?.detached()
            if (old != null) detachedPending = old
        } else {
            old?.cancel(); detachedPending?.cancel(); detachedPending = null
        }
    }
    private fun load() {
        val owner = scope ?: return
        val expected = key ?: return
        if (!attached || released || loading || usableCover(image) || !owner.canLoad(expected)) return
        val token = ++nonce
        val source = requireNotNull(loader)
        val request = NovelCoverRequest(owner, expected)
        loading = true
        val handle = source.load(request) result@{ result ->
            if (token != nonce || !loading || !attached || released || scope !== owner || key != expected || !request.isAuthorized()) return@result
            loading = false; pending = null
            image = result?.takeIf(::usableCover)?.let(::independentCover)
            if (image != null) {
                owner.remember(expected, requireNotNull(image))
                show(image, true)
            } else {
                if (!source.recordsScopeResults()) owner.failed(expected)
                show(null, false)
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

/** Composition-owned presentation; the loader/binding remain shared with the native lifecycle tests. */
internal class NovelCoverPresentation {
    var image by mutableStateOf<Drawable?>(null); private set
    var attached = false; private set
    var coordinates: LayoutCoordinates? = null
    val binding = NovelCoverBinding(show = { value, _ -> image = value })

    fun attach(scope: NovelCoverScope?, entity: String, key: NovelCoverKey?, loader: NovelCoverLoader) {
        attached = true
        binding.bind(scope, entity, key, loader)
        binding.attach()
    }
    fun release() {
        attached = false
        coordinates = null
        binding.release()
    }
}

internal val LocalNovelCoverPresentationFactory = staticCompositionLocalOf<() -> NovelCoverPresentation> {
    { NovelCoverPresentation() }
}

/** Non-bitmap drawables keep independent callback/animation ownership without an AndroidView. */
@Composable
private fun NovelDrawableCover(image: Drawable, description: String?) {
    var frame by remember(image) { mutableIntStateOf(0) }
    val main = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(image) {
        val tasks = mutableMapOf<Runnable, Runnable>()
        val callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { frame++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
                tasks.remove(what)?.let(main::removeCallbacks)
                val task = Runnable { tasks.remove(what); what.run() }
                tasks[what] = task
                main.postAtTime(task, time)
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                tasks.remove(what)?.let(main::removeCallbacks)
            }
        }
        image.callback = callback
        (image as? Animatable)?.start()
        onDispose {
            (image as? Animatable)?.stop()
            if (image.callback === callback) image.callback = null
            tasks.values.forEach(main::removeCallbacks)
            tasks.clear()
        }
    }
    ComposeCanvas(Modifier.fillMaxSize().then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        frame
        val width = image.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: size.width
        val height = image.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: size.height
        val scale = maxOf(size.width / width.coerceAtLeast(1f), size.height / height.coerceAtLeast(1f))
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            try {
                native.clipRect(0f, 0f, size.width, size.height)
                native.translate((size.width - width * scale) / 2f, (size.height - height * scale) / 2f)
                native.scale(scale, scale)
                image.setBounds(0, 0, width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))
                image.draw(native)
            } finally { native.restoreToCount(save) }
        }
    }
}

@Composable
internal fun NovelRemoteCover(entity: String, projection: String, url: String?, modifier: Modifier, description: String? = null) {
    val scope = LocalNovelCoverScope.current
    val context = LocalContext.current
    val suppliedLoader = LocalNovelCoverLoader.current
    val loader = remember(scope, suppliedLoader, context) { suppliedLoader ?: scope?.loader(context) ?: AppKitNovelCoverLoader(context) }
    // A changed identity gets an empty presentation synchronously, before any new draw can borrow
    // old pixels. Binding retirement still clears state on logout without a model refresh.
    val key = if (scope?.active == true) novelCoverKey(entity, projection, url) else null
    val factory = LocalNovelCoverPresentationFactory.current
    val presentation = remember(scope, entity, key, loader, factory) { factory() }
    DisposableEffect(presentation) {
        presentation.attach(scope, entity, key, loader)
        onDispose { presentation.release() }
    }
    val current = presentation.image
    val bitmap = (current as? BitmapDrawable)?.bitmap
    val pixels = remember(bitmap) { bitmap?.takeUnless { it.isRecycled }?.asImageBitmap() }
    Box(modifier.onGloballyPositioned { presentation.coordinates = it }.drawWithContent {
        // Preserve the former native onDraw authority gate: even a draw-only invalidation may
        // never present pixels from an expired session. No loading or copy is done on each frame.
        if (scope?.isActive() == true && presentation.binding.validate() && presentation.image === current && usableCover(current)) drawContent()
    }) {
        if (pixels != null) Image(pixels, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (current != null) NovelDrawableCover(current, description)
    }
}

