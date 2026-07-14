# In-App Photo and Video Camera Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unstable external system-camera flow with an in-process, Telegram-inspired full-screen Camera2 page for photos and videos.

**Architecture:** A focused `MediaCameraController` owns Camera2, `ImageReader`, `MediaRecorder`, orientation, focus, zoom, flash, and lifecycle state. A separate `MediaCameraActivity` owns the full-screen UI, gestures, recording timer, review state, temporary-file cleanup, and a unified result contract consumed by the three existing media-picker callers.

**Tech Stack:** Java 17, Android Camera2, `TextureView`, `ImageReader`, `MediaRecorder`, AppKit navigation/result patterns, Android instrumentation tests, Gradle 8.5.

---

## File Map

- Create `mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraContract.java`: intent/result constants and pure orientation helpers.
- Create `mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraController.java`: all Camera2 and recording resource ownership.
- Create `mastodon/src/main/java/org/joinmastodon/android/ui/MediaCameraActivity.java`: full-screen capture/review UI and state machine.
- Create `mastodon/src/main/java/org/joinmastodon/android/ui/views/MediaCameraShutterView.java`: Telegram-style shutter, hold gesture, and recording progress drawing.
- Create `mastodon/src/main/res/layout/activity_media_camera.xml`: capture and review layers.
- Create `mastodon/src/main/res/drawable/bg_media_camera_control.xml`: translucent circular control background.
- Modify `mastodon/src/main/AndroidManifest.xml`: non-exported camera Activity and audio permission.
- Modify `mastodon/src/main/res/xml/fileprovider_paths.xml`: expose cache videos.
- Modify `mastodon/src/main/res/values/strings.xml`: camera and review labels.
- Modify `mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java`: launch and consume photo/video results.
- Modify `mastodon/src/main/java/org/joinmastodon/android/fragments/ProfileQrCodeFragment.java`: launch image-only camera and decode returned image.
- Modify `mastodon/src/main/java/org/joinmastodon/android/ui/MLKitBarcodeScannerActivity.java`: launch image-only camera and decode internally.
- Modify `mastodon/src/main/java/org/joinmastodon/android/ui/sheets/MediaPickerSheet.java`: retain a static camera entry only.
- Delete `mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraPreviewView.java` after proving it has no references.
- Create `mastodon/src/androidTest/java/org/joinmastodon/android/ui/media/MediaCameraContractTest.java`: orientation and result-contract tests.
- Create `mastodon/src/androidTest/java/org/joinmastodon/android/ui/MediaCameraActivityTest.java`: image-only mode, review controls, and cancellation cleanup tests.

### Task 1: Stable Camera Contract and Orientation Math

**Covers:** [S1, S4, S6, S8]

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraContract.java`
- Create: `mastodon/src/androidTest/java/org/joinmastodon/android/ui/media/MediaCameraContractTest.java`

- [ ] **Step 1: Write failing orientation and result-contract tests**

Create instrumentation tests that assert these exact cases:

```java
@Test public void backCameraOrientationUsesSensorMinusDisplay(){
	assertEquals(90, MediaCameraContract.jpegOrientation(90, Surface.ROTATION_0, false));
	assertEquals(0, MediaCameraContract.jpegOrientation(90, Surface.ROTATION_90, false));
}

@Test public void frontCameraOrientationUsesSensorPlusDisplay(){
	assertEquals(270, MediaCameraContract.jpegOrientation(270, Surface.ROTATION_0, true));
	assertEquals(0, MediaCameraContract.jpegOrientation(270, Surface.ROTATION_90, true));
}

@Test public void resultRoundTripsMediaMetadata(){
	Uri uri=Uri.parse("content://camera/test.jpg");
	Intent result=MediaCameraContract.createResult(uri, false, "image/jpeg");
	assertEquals(uri, MediaCameraContract.getUri(result));
	assertFalse(MediaCameraContract.isVideo(result));
	assertEquals("image/jpeg", MediaCameraContract.getMimeType(result));
}
```

- [ ] **Step 2: Run the test and confirm RED**

Run: `./gradlew :mastodon:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.joinmastodon.android.ui.media.MediaCameraContractTest --no-daemon`

Expected: compilation failure because `MediaCameraContract` does not exist.

- [ ] **Step 3: Implement the minimal contract**

Define `EXTRA_ALLOW_VIDEO`, `EXTRA_MEDIA_URI`, `EXTRA_MEDIA_IS_VIDEO`, and `EXTRA_MEDIA_MIME_TYPE`; implement `createIntent(Context, boolean)`, `createResult(Uri, boolean, String)`, getters, and `jpegOrientation(int sensorOrientation, int displayRotation, boolean frontFacing)`. Convert display rotation to degrees with `{0, 90, 180, 270}` and normalize to `[0, 360)`.

- [ ] **Step 4: Run tests and compile**

Run: `./gradlew :mastodon:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.joinmastodon.android.ui.media.MediaCameraContractTest :mastodon:compileDebugJavaWithJavac --no-daemon`

Expected: contract tests PASS and Java compilation succeeds.

- [ ] **Step 5: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraContract.java mastodon/src/androidTest/java/org/joinmastodon/android/ui/media/MediaCameraContractTest.java
git commit -m "feat: add in-app camera result contract"
```

### Task 2: Camera2 Photo Controller

**Covers:** [S2, S4, S5, S6, S7, S8]

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraController.java`
- Modify: `mastodon/src/androidTest/java/org/joinmastodon/android/ui/media/MediaCameraContractTest.java`

- [ ] **Step 1: Add failing deterministic size-selection tests**

Add tests for a package-private static `chooseSize(Size[] choices, int maxWidth, int maxHeight, float targetRatio)`:

```java
@Test public void chooseSizePrefersLargestMatchingRatioWithinBounds(){
	Size result=MediaCameraController.chooseSize(new Size[]{new Size(4000, 3000), new Size(1920, 1080), new Size(1280, 720)}, 1920, 1080, 16f/9f);
	assertEquals(new Size(1920, 1080), result);
}

@Test public void chooseSizeFallsBackToLargestBoundedSize(){
	Size result=MediaCameraController.chooseSize(new Size[]{new Size(1600, 1200), new Size(1280, 960)}, 1280, 960, 16f/9f);
	assertEquals(new Size(1280, 960), result);
}
```

- [ ] **Step 2: Run and confirm RED**

Run the contract test command from Task 1. Expected: failure because `MediaCameraController` is missing.

- [ ] **Step 3: Implement controller lifecycle and photo capture**

Implement one controller with:

```java
public interface Callback{
	void onCameraReady(boolean frontFacing, boolean flashAvailable, boolean switchAvailable);
	void onPhotoCaptured(File file);
	void onVideoRecorded(File file);
	void onError(int messageRes);
}

public enum State{ CLOSED, OPENING, PREVIEW, CAPTURING, RECORDING, CLOSING }
```

Use a dedicated `HandlerThread`; enumerate front/back camera IDs; select bounded preview/JPEG sizes; configure one preview session with `TextureView` and `ImageReader`; write JPEG bytes to the supplied cache file; set `JPEG_ORIENTATION` through `MediaCameraContract`; expose `open(TextureView)`, `close()`, `takePhoto(File)`, `switchCamera()`, `cycleFlashMode()`, `setZoom(float)`, and `focus(float x, float y)`. Guard callbacks with an incrementing generation integer and reject actions unless state is `PREVIEW`.

- [ ] **Step 4: Run tests and compile**

Run: `./gradlew :mastodon:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.joinmastodon.android.ui.media.MediaCameraContractTest :mastodon:compileDebugJavaWithJavac --no-daemon`

Expected: tests PASS and compilation succeeds.

- [ ] **Step 5: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraController.java mastodon/src/androidTest/java/org/joinmastodon/android/ui/media/MediaCameraContractTest.java
git commit -m "feat: add Camera2 photo controller"
```

### Task 3: Full-Screen Photo Capture and Review UI

**Covers:** [S3, S5, S6, S7, S8, S10]

**Files:**
- Create: `mastodon/src/main/java/org/joinmastodon/android/ui/MediaCameraActivity.java`
- Create: `mastodon/src/main/java/org/joinmastodon/android/ui/views/MediaCameraShutterView.java`
- Create: `mastodon/src/main/res/layout/activity_media_camera.xml`
- Create: `mastodon/src/main/res/drawable/bg_media_camera_control.xml`
- Modify: `mastodon/src/main/AndroidManifest.xml`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Create: `mastodon/src/androidTest/java/org/joinmastodon/android/ui/MediaCameraActivityTest.java`

- [ ] **Step 1: Write failing Activity contract tests**

Launch `MediaCameraActivity` with `EXTRA_ALLOW_VIDEO=false` and assert `recording_timer` is hidden, `camera_shutter` and `camera_switch` exist, and Back returns `RESULT_CANCELED`. Add a test that launches with saved `review_file`, presses `camera_retake`, and verifies the file is deleted and `camera_preview` is visible.

- [ ] **Step 2: Run and confirm RED**

Run: `./gradlew :mastodon:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.joinmastodon.android.ui.MediaCameraActivityTest --no-daemon`

Expected: compilation failure because Activity/resources do not exist.

- [ ] **Step 3: Build the capture UI**

Create a full-screen black `FrameLayout` with `TextureView` ID `camera_preview`, review `ImageView`, review `VideoView`, top controls (`camera_back`, `camera_flash`, `recording_timer`), bottom controls (`camera_gallery`, custom `camera_shutter`, `camera_switch`), focus indicator, and review actions (`camera_retake`, `camera_use`). Register the Activity as non-exported with `screenOrientation="portrait"`, translucent system bars, and no system camera intent filters.

- [ ] **Step 4: Implement photo flow and gestures**

Wire Activity lifecycle to `MediaCameraController`; single tap shutter calls `takePhoto`; switch and flash buttons call controller methods; `ScaleGestureDetector` maps pinch to `[1, maxDigitalZoom]`; touch-up without scaling calls focus and animates the focus ring. On photo callback, switch to review UI and decode a sampled bitmap off the main thread. `camera_retake` deletes the file and restores preview; `camera_use` returns `MediaCameraContract.createResult(...)`.

- [ ] **Step 5: Run tests and compile**

Run: `./gradlew :mastodon:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.joinmastodon.android.ui.MediaCameraActivityTest :mastodon:compileDebugJavaWithJavac --no-daemon`

Expected: Activity tests PASS and compilation succeeds.

- [ ] **Step 6: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ui/MediaCameraActivity.java mastodon/src/main/java/org/joinmastodon/android/ui/views/MediaCameraShutterView.java mastodon/src/main/res/layout/activity_media_camera.xml mastodon/src/main/res/drawable/bg_media_camera_control.xml mastodon/src/main/AndroidManifest.xml mastodon/src/main/res/values/strings.xml mastodon/src/androidTest/java/org/joinmastodon/android/ui/MediaCameraActivityTest.java
git commit -m "feat: add full-screen in-app photo camera"
```

### Task 4: Video Recording and Review

**Covers:** [S2, S3, S5, S6, S7, S8]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraController.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ui/MediaCameraActivity.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ui/views/MediaCameraShutterView.java`
- Modify: `mastodon/src/main/res/layout/activity_media_camera.xml`
- Modify: `mastodon/src/main/AndroidManifest.xml`
- Modify: `mastodon/src/main/res/xml/fileprovider_paths.xml`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Modify: `mastodon/src/androidTest/java/org/joinmastodon/android/ui/MediaCameraActivityTest.java`

- [ ] **Step 1: Add failing video-mode UI and cleanup tests**

Assert that `EXTRA_ALLOW_VIDEO=true` enables hold recording, `RECORD_AUDIO` denial leaves photo capture enabled, and an Activity restored with a `.mp4` `review_file` shows `camera_review_video` plus “使用视频”. Add `<cache-path name="video_cache" path="videos/"/>` expectation through `FileProvider.getUriForFile()`.

- [ ] **Step 2: Run and confirm RED**

Run the Activity test command. Expected: video assertions fail because recording is not implemented and the provider lacks a video path.

- [ ] **Step 3: Add MediaRecorder session support**

Add `startRecording(File)` and `stopRecording(boolean keep)` to the controller. Configure H.264 MP4, AAC audio, 30 fps, supported 720p/1080p size, 60-second max duration, device orientation hint, and a capture session containing preview plus recorder surfaces. On stop, reset/release `MediaRecorder`, rebuild the preview session, delete invalid/empty output, and invoke `onVideoRecorded` only for a valid file.

- [ ] **Step 4: Add Telegram-style hold recording**

`MediaCameraShutterView` sends `onTap`, `onHoldStart`, and `onHoldEnd`; draw a white photo ring, red recording center, and a 0-1 progress arc. Activity requests `RECORD_AUDIO` on first hold, starts the one-second-updating timer, stops on release or 60 seconds, and switches to muted looping `VideoView` review. Back or pause while recording calls `stopRecording(false)`.

- [ ] **Step 5: Run tests and compile**

Run: `./gradlew :mastodon:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.joinmastodon.android.ui.MediaCameraActivityTest :mastodon:compileDebugJavaWithJavac --no-daemon`

Expected: tests PASS and compilation succeeds.

- [ ] **Step 6: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraController.java mastodon/src/main/java/org/joinmastodon/android/ui/MediaCameraActivity.java mastodon/src/main/java/org/joinmastodon/android/ui/views/MediaCameraShutterView.java mastodon/src/main/res/layout/activity_media_camera.xml mastodon/src/main/AndroidManifest.xml mastodon/src/main/res/xml/fileprovider_paths.xml mastodon/src/main/res/values/strings.xml mastodon/src/androidTest/java/org/joinmastodon/android/ui/MediaCameraActivityTest.java
git commit -m "feat: add in-app video recording"
```

### Task 5: Replace All External Camera Callers

**Covers:** [S2, S4, S6, S7]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ProfileQrCodeFragment.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ui/MLKitBarcodeScannerActivity.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/discover/DiscoverFragment.java`
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ui/googleservices/barcodescanner/BarcodeScanner.java`

- [ ] **Step 1: Add failing result-routing tests or assertions**

Extend instrumentation coverage to assert Compose camera intent uses `allow_video=true`, QR callers use `allow_video=false`, and scanner image selection returns a decoded `barcode_result`, never a raw `content://` string.

- [ ] **Step 2: Run and confirm RED**

Run all connected camera tests. Expected: caller assertions fail because external `ACTION_IMAGE_CAPTURE` remains.

- [ ] **Step 3: Replace Compose external camera flow**

Remove `pendingCameraUri` camera creation and `ACTION_IMAGE_CAPTURE`; launch `MediaCameraContract.createIntent(getActivity(), true)`. On success, read URI and MIME type and call `mediaViewController.addMediaAttachment(uri, null)` for both images and videos. Preserve existing platform gallery result handling.

- [ ] **Step 4: Replace QR camera flows and normalize scanner result**

Launch image-only Activity from both QR callers. `ProfileQrCodeFragment` passes the returned URI to `decodeQrFromUri`. `MLKitBarcodeScannerActivity` decodes selected/captured images inside the scanner Activity and returns only the decoded `barcode_result`; remove caller-specific `barcode_image_uri` branching from `DiscoverFragment` and keep `BarcodeScanner.isValidResult()` unchanged.

- [ ] **Step 5: Run tests and compile**

Run: `./gradlew :mastodon:connectedDebugAndroidTest :mastodon:compileDebugJavaWithJavac --no-daemon`

Expected: all instrumentation tests PASS and Java compilation succeeds.

- [ ] **Step 6: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/fragments/ComposeFragment.java mastodon/src/main/java/org/joinmastodon/android/fragments/ProfileQrCodeFragment.java mastodon/src/main/java/org/joinmastodon/android/ui/MLKitBarcodeScannerActivity.java mastodon/src/main/java/org/joinmastodon/android/fragments/discover/DiscoverFragment.java mastodon/src/main/java/org/joinmastodon/android/ui/googleservices/barcodescanner/BarcodeScanner.java
git commit -m "fix: route all media capture through in-app camera"
```

### Task 6: Remove Obsolete Preview and Verify UI Themes

**Covers:** [S3, S7, S9, S10]

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/ui/sheets/MediaPickerSheet.java`
- Delete: `mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraPreviewView.java`
- Modify: `mastodon/src/main/res/layout/activity_media_camera.xml`
- Modify: `mastodon/src/main/res/drawable/bg_media_camera_control.xml`

- [ ] **Step 1: Prove the old preview is unreferenced**

Run: `rg "MediaCameraPreviewView|ACTION_IMAGE_CAPTURE" mastodon/src/main/java mastodon/src/main/res`

Expected before cleanup: only the obsolete class itself remains; no `ACTION_IMAGE_CAPTURE` call remains.

- [ ] **Step 2: Delete obsolete preview and finish static camera tile**

Delete `MediaCameraPreviewView.java`. Ensure the first media-grid cell uses a dark neutral background plus the existing white camera icon and contains no `TextureView` or Camera2 owner.

- [ ] **Step 3: Verify light/dark visual contrast**

Use device theme commands and screenshots:

```bash
adb shell cmd uimode night no
adb shell cmd uimode night yes
```

Expected in both modes: black camera canvas, visible white controls, readable timer, visible “重拍/使用” actions, and transparent status/navigation bars with light icons.

- [ ] **Step 4: Build and run full test suite**

Run: `./gradlew :mastodon:connectedDebugAndroidTest :mastodon:assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL`, zero failed instrumentation tests.

- [ ] **Step 5: Commit**

```bash
git add mastodon/src/main/java/org/joinmastodon/android/ui/sheets/MediaPickerSheet.java mastodon/src/main/java/org/joinmastodon/android/ui/media/MediaCameraPreviewView.java mastodon/src/main/res/layout/activity_media_camera.xml mastodon/src/main/res/drawable/bg_media_camera_control.xml
git commit -m "refactor: remove obsolete media camera preview"
```

### Task 7: On-Device Regression Verification

**Covers:** [S9]

**Files:**
- No production file changes expected.

- [ ] **Step 1: Install a fresh debug APK and clear logs**

```bash
adb install -r mastodon/build/outputs/apk/debug/mastodon-debug.apk
adb logcat -c
```

- [ ] **Step 2: Verify photo capture repeatedly**

Record the app PID, then perform five cycles: open picker, open camera, capture, retake once, capture again, use photo. Repeat with front camera and each supported flash mode.

Run after cycles: `adb shell pidof top.abdl_space.app.debug`

Expected: PID unchanged; photos have correct orientation and attach successfully.

- [ ] **Step 3: Verify video capture**

Record one short video, one front-camera video, and one 60-second auto-stop video. Confirm review playback, audio, retake deletion, and Compose attachment acceptance.

- [ ] **Step 4: Verify QR image-only callers**

Open both QR entry points, confirm no video recording gesture is enabled, capture a QR image, and confirm decoded navigation/result behavior.

- [ ] **Step 5: Inspect logs for regressions**

```bash
adb logcat -d -v threadtime | rg "top\.abdl_space\.app\.debug|camopt_killer|AndroidRuntime|ANR|OutOfMemoryError"
```

Expected: no kill line targeting ABDL Space, no `FATAL EXCEPTION`, no ANR, no OOM, and no external `OneShotImageCapture` Activity launch.

- [ ] **Step 6: Final build and status check**

Run: `./gradlew :mastodon:assembleDebug --no-daemon && git status --short --branch`

Expected: `BUILD SUCCESSFUL`; only previously existing unrelated untracked compose files may remain.
