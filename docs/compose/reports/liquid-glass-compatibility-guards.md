# Liquid-glass compatibility guards

## Scope

This change closes the unsupported-device settings bypass and adds process-local fallback for recoverable graphics/capture failures. It does not change the application version, publish a release, or replace existing APKs.

## Enablement

- API 33 or newer is required. Miuix 0.9.3's `isRuntimeShaderSupported()` itself checks only SDK level.
- `ActivityManager.isLowRamDevice()` disables runtime effects. Missing application context/service fails closed.
- Home starts with classic controls, then checks the actual attached capture view's hardware acceleration before constructing liquid controls and enabling capture.
- Settings hides the liquid switch when unsupported. Home, its children, padding, and capture setup use the gated accessor instead of the raw preference.
- A graphics failure disables effects immediately for the current process. Listener callbacks are posted to the main queue to restore classic controls outside drawing. The stored user choice is preserved across unrelated preference saves.

## Failure containment

- RuntimeException, LinkageError, and OutOfMemoryError from guarded effect operations trigger fallback. Unknown fatal errors and ordinary application-content drawing errors propagate.
- Graphics-only children isolate backdrop drawing from interactive content. Canvas state is restored before same-frame fallback drawing.
- Backdrop nodes attach with effects disabled, then enable via a guarded update after Compose attachment completes. This avoids swallowing an exception from an incomplete delegated attachment lifecycle.
- Whole blur/vibrancy/lens callbacks are guarded, including snapshot-driven updates. Background shader setup, animation updates, interactive highlights, and owned inner-shadow operations are guarded.
- Recording guards bypass effect recording once disabled and preserve the original application-content exception rather than classifying it as a graphics failure.

## Capture

- Capture requires compatibility support and an accelerated incoming window canvas.
- The 16 MiB budget applies to owned intermediate/strip buffers and retained cached software bitmap copies. It is not a cap on total process or GPU memory, or on previously delivered bitmaps still held by Compose.
- Bitmap conversion occurs inside the restoration boundary. Every pending restoration is attempted, and the re-entry flag is reset in a finally block.
- Allocation, conversion, software drawing, or delivery failure stops capture and reports the process-local failure.
- Disable/detach cancels scheduled work, invalidates stale delivery, and clears owned references. Delivered bitmaps are not recycled while consumers may reference them.
- Cache accounting measures retained values rather than lifetime allocations, preventing premature disablement during long feeds.

## Verification results

- Focused `:mastodon:testDebugUnitTest`: **169 executions, 0 failures, 0 errors, 0 skipped** across 15 suites.
- `:mastodon:assembleDebug` passed; generated `mastodon/build/outputs/apk/debug/mastodon-debug.apk`.
- `:mastodon:compileReleaseKotlin`, `:mastodon:compileReleaseJavaWithJavac`, and `:mastodon:processReleaseManifest` passed. No signed Release APK was produced.
- `git diff --check` passed.
- Build emitted existing Gradle deprecation/flatDir and D8 Kotlin-metadata rewrite warnings; they did not fail the build.

## Verification boundaries

Focused tests cover API 26–35 preference behavior, low-RAM gating, preserved saved choices, deferred notifications, guarded fallback pixels, disabled-first attachment/update cleanup, removal/recreation, recording/content-error behavior, capture coordinates, memory bounds, conversion/restoration failures, and navigation/menu regressions.

Robolectric bitmap tests use native software graphics. The capture suite simulates an accelerated incoming canvas; it is not a real GPU test. Lifecycle fallback pixels are read from synchronous View.draw rather than PixelCopy, whose window redraw callback times out in this host's Robolectric environment. Multi-SDK preference tests require a 1 GiB test heap in this environment; the repository's 512 MiB default exhausted the test JVM while loading Android framework resources.

No connected device or usable desktop emulator was available. Real vendor GPU/ART compatibility and visual acceptance remain unverified. The Miuix blur minSdk-33 manifest override remains an upstream boundary for the API-26 app. Native driver/RenderThread crashes cannot be caught by these guards. Opaque library-node detach/resource-release exceptions are reported and rethrown: public Compose APIs cannot safely unlink a child whose detach lifecycle failed, so the implementation does not pretend to recover by swallowing the error.
