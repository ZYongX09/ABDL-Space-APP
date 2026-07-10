package org.joinmastodon.android.nsfw;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.caverock.androidsvg.SVG;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * SVG → Bitmap → WebP 缓存，用于深色模式启动页图标。
 * 首次启动渲染并缓存，后续直接读取缓存。
 */
public class SplashRenderer {
    private static final String TAG = "SplashRenderer";
    private static final String CACHE_FILE = "splash_dark.webp";
    private static final String SVG_FILE = "ic_splash_dark.svg";

    public interface Callback {
        void onReady(File cachedFile);
    }

    public static File getCachedFile(Context context) {
        return new File(context.getCacheDir(), CACHE_FILE);
    }

    public static boolean hasCache(Context context) {
        File f = getCachedFile(context);
        return f.exists() && f.length() > 0;
    }

    /**
     * 后台渲染 SVG → WebP 缓存。完成后回调。
     */
    public static void renderInBackground(Context context, Callback callback) {
        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();

                // 读取 SVG
                InputStream is = context.getAssets().open(SVG_FILE);
                SVG svg = SVG.getFromInputStream(is);
                is.close();

                // 按屏幕密度计算渲染尺寸：288dp
                float density = context.getResources().getDisplayMetrics().density;
                int size = (int) (288 * density);

                // 渲染到 Bitmap
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                svg.renderToCanvas(canvas);

                // 压缩为 WebP 写入缓存
                File cacheFile = getCachedFile(context);
                FileOutputStream fos = new FileOutputStream(cacheFile);
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, fos);
                fos.flush();
                fos.close();
                bitmap.recycle();

                long elapsed = System.currentTimeMillis() - start;
                Log.i(TAG, String.format("SVG rendered to WebP in %dms, size=%d bytes", elapsed, cacheFile.length()));

                new Handler(Looper.getMainLooper()).post(() -> callback.onReady(cacheFile));
            } catch (Exception e) {
                Log.e(TAG, "Failed to render SVG", e);
                // 渲染失败不回调，使用品牌 icon 兜底
            }
        }).start();
    }
}
