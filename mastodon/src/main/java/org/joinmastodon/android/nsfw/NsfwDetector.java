package org.joinmastodon.android.nsfw;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * NSFW 图片检测封装类。
 * 使用 open_nsfw (Yahoo VGG16 TFLite 模型) 在本地离线检测图片敏感度。
 *
 * 阈值（宁可错杀不可放过）：
 * - nsfwScore > 0.3 → 禁止上传
 * - nsfwScore > 0.15 → 标记 is_nsfw
 * - nsfwScore <= 0.15 → 安全
 *
 * 模型加载失败时，checkImage 回调 blocked=true，禁止上传带图片的帖子。
 */
public class NsfwDetector {
    private static final String TAG = "NsfwDetector";

    public static final float THRESHOLD_BLOCK = 0.3f;
    public static final float THRESHOLD_MARK = 0.15f;

    private static NsfwClassifier classifier;
    private static boolean initialized = false;

    public interface Callback {
        void onResult(float nsfwScore, boolean blocked);
    }

    public static void init(Context context) {
        if (initialized) return;
        try {
            classifier = new NsfwClassifier();
            classifier.initFromAssets(context.getApplicationContext());
            initialized = true;
            Log.i(TAG, "NSFW detector initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init NSFW detector", e);
            classifier = null;
            initialized = false;
        }
    }

    public static boolean isAvailable() {
        return initialized && classifier != null;
    }

    public static void unInit() {
        if (classifier != null) {
            classifier.close();
            classifier = null;
        }
        initialized = false;
    }

    public static void checkImage(Context context, Uri imageUri, Callback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());

        if (!initialized) init(context);

        // 模型不可用：禁止上传
        if (!initialized || classifier == null) {
            Log.e(TAG, "NSFW detector not available, blocking image upload");
            mainHandler.post(() -> callback.onResult(1f, true));
            return;
        }

        new Thread(() -> {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), imageUri);
                Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });

                float[] scores = classifier.classify(bitmap);
                float nsfwScore = scores.length > 1 ? scores[1] : 0f;
                boolean blocked = nsfwScore > THRESHOLD_BLOCK;

                bitmap.recycle();
                Log.i(TAG, String.format("NSFW score: %.4f, blocked: %b", nsfwScore, blocked));
                mainHandler.post(() -> callback.onResult(nsfwScore, blocked));
            } catch (Exception e) {
                Log.e(TAG, "NSFW detection failed", e);
                // 检测异常也禁止上传
                mainHandler.post(() -> callback.onResult(1f, true));
            }
        }).start();
    }

    public static boolean isNsfw(float nsfwScore) {
        return nsfwScore > THRESHOLD_MARK;
    }
}
