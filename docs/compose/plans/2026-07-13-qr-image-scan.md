# QR Image Scan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a QR-code image icon to the profile QR scanner page, use the reusable local image picker, and decode a selected image locally.

**Architecture:** `ProfileQrCodeFragment` will launch the existing `MediaAlbumPickerFragment` with images-only and a one-image limit. A small local decoder method will open the returned Uri, downsample it to a safe bitmap size, decode it with the existing JourneyApps ZXing dependency, and route successful HTTP(S) results through the existing `MainActivity.handleURL` flow.

**Tech Stack:** Java 17, AppKit `Nav.goForResult`, existing local media picker, Android `ContentResolver`, JourneyApps ZXing.

---

### Task 1: Add Image Picker Entry

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ProfileQrCodeFragment.java`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Modify: `mastodon/src/main/res/values-zh-rCN/strings.xml`

- [ ] Add a distinct result code and import `MediaAlbumPickerFragment`, `MediaPickerConfig`, and `MediaPickerResult`.
- [ ] Add an always-visible toolbar action using the existing QR/image-picker icon style and label it as selecting a QR image.
- [ ] Launch `MediaAlbumPickerFragment` with `allowImages=true`, `allowVideos=false`, and `maxCount=1`.
- [ ] Handle the picker result in `onFragmentResult`, read the first Uri, and start decoding without blocking the UI.

### Task 2: Decode Selected Image Locally

**Files:**
- Modify: `mastodon/src/main/java/org/joinmastodon/android/fragments/ProfileQrCodeFragment.java`
- Modify: `mastodon/src/main/res/values/strings.xml`
- Modify: `mastodon/src/main/res/values-zh-rCN/strings.xml`

- [ ] Read the selected Uri with `ContentResolver.openInputStream` and decode bounds before loading pixels.
- [ ] Downsample very large images to a bounded bitmap, then create a ZXing `BinaryBitmap` with `HybridBinarizer` and `MultiFormatReader` restricted to `BarcodeFormat.QR_CODE`.
- [ ] Post decode results back to the main thread; recycle the bitmap and close the input stream on all paths.
- [ ] On success, apply the existing behavior: open `http://`/`https://` through `MainActivity.handleURL`, otherwise show the existing unsupported-link message and keep the scanner page open.
- [ ] Show a localized failure message when no QR code or an unreadable image is found.

### Task 3: Verify

**Files:**
- Test: existing Android build and manual QR flows

- [ ] Run `./gradlew :mastodon:assembleDebug --no-daemon` and confirm Java/resource compilation succeeds.
- [ ] Run `./gradlew :mastodon:testDebugUnitTest --no-daemon`; record `NO-SOURCE` if no unit tests exist.
- [ ] Run `git diff --check`.
- [ ] Manually verify toolbar icon, picker limited to one image, valid URL QR, non-URL QR, image without QR, cancel, dark theme, and camera scanning regression.
