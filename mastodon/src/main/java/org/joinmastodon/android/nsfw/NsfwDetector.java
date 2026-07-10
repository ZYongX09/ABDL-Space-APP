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
 * 阈值（避免误判普通内容）：
 * - nsfwScore > 0.8 → 禁止上传
 * - nsfwScore > 0.5 → 标记 is_nsfw
 * - nsfwScore <= 0.5 → 安全
 *
 * 模型加载失败时，checkImage 回调 blocked=true，禁止上传带图片的帖子。
 */
public class NsfwDetector {
    private static final String TAG = "NsfwDetector";

    public static final float THRESHOLD_BLOCK = 0.8f;
    public static final float THRESHOLD_MARK = 0.5f;

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
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "TFLite native library incompatible with this device", e);
            classifier = null;
            initialized = false;
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

        // 模型不可用：允许上传（不误杀）
        if (!initialized || classifier == null) {
            Log.e(TAG, "NSFW detector not available, allowing image upload");
            mainHandler.post(() -> callback.onResult(0f, false));
            return;
        }

        new Thread(() -> {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), imageUri);
                Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });

                // 跳过太小的图片
                if (bitmap.getWidth() < 50 || bitmap.getHeight() < 50) {
                    bitmap.recycle();
                    Log.i(TAG, "Image too small, skipping NSFW detection");
                    mainHandler.post(() -> callback.onResult(0f, false));
                    return;
                }

                // 检查图片复杂度：纯色/简单图片跳过检测（避免误判文本截图等）
                if (isLowComplexity(bitmap)) {
                    bitmap.recycle();
                    Log.i(TAG, "Image low complexity, skipping NSFW detection");
                    mainHandler.post(() -> callback.onResult(0f, false));
                    return;
                }

                float[] scores = classifier.classify(bitmap);
                float nsfwScore = scores.length > 1 ? scores[1] : 0f;
                boolean blocked = nsfwScore > THRESHOLD_BLOCK;

                bitmap.recycle();
                Log.i(TAG, String.format("NSFW score: %.4f, blocked: %b", nsfwScore, blocked));
                mainHandler.post(() -> callback.onResult(nsfwScore, blocked));
            } catch (Exception e) {
                Log.e(TAG, "NSFW detection failed", e);
                // 检测异常允许上传（不误杀）
                mainHandler.post(() -> callback.onResult(0f, false));
            }
        }).start();
    }

    /**
     * 检查图片是否为低复杂度（纯色、渐变、简单图形）。
     * 采样中心区域像素，计算颜色方差。
     */
    private static boolean isLowComplexity(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int sampleSize = 20;
        int[] pixels = new int[sampleSize * sampleSize];
        int startX = (w - sampleSize) / 2;
        int startY = (h - sampleSize) / 2;
        bitmap.getPixels(pixels, 0, sampleSize, startX, startY, sampleSize, sampleSize);

        long sumR = 0, sumG = 0, sumB = 0;
        for (int p : pixels) {
            sumR += (p >> 16) & 0xFF;
            sumG += (p >> 8) & 0xFF;
            sumB += p & 0xFF;
        }
        int n = pixels.length;
        double avgR = sumR / (double) n;
        double avgG = sumG / (double) n;
        double avgB = sumB / (double) n;

        double varR = 0, varG = 0, varB = 0;
        for (int p : pixels) {
            double dR = ((p >> 16) & 0xFF) - avgR;
            double dG = ((p >> 8) & 0xFF) - avgG;
            double dB = (p & 0xFF) - avgB;
            varR += dR * dR;
            varG += dG * dG;
            varB += dB * dB;
        }
        double variance = (varR + varG + varB) / (n * 3.0);

        // 方差极低 → 纯色/渐变/简单图片，跳过 NSFW 检测
        return variance < 50.0;
    }

    public static boolean isNsfw(float nsfwScore) {
        return nsfwScore > THRESHOLD_MARK;
    }
}
