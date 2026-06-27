package org.joinmastodon.android.nsfw;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;

/**
 * NSFW 图片分类器 — 基于 Yahoo open_nsfw (VGG16) 的 TFLite 推理。
 * 返回 floatArrayOf(sfwScore, nsfwScore)，各 0~1。
 */
public class NsfwClassifier {

    private static final int INPUT_WIDTH = 224;
    private static final int INPUT_HEIGHT = 224;
    private static final float[] VGG_MEAN = {103.939f, 116.779f, 123.68f};

    private Interpreter interpreter;

    public void init(String modelPath) throws Exception {
        interpreter = new Interpreter(new FileInputStream(modelPath).getChannel().map(
            FileChannel.MapMode.READ_ONLY, 0, new java.io.File(modelPath).length()
        ));
    }

    public void initFromAssets(android.content.Context context) throws Exception {
        android.content.res.AssetFileDescriptor fd = context.getAssets().openFd("nsfw.tflite");
        interpreter = new Interpreter(
            new FileInputStream(fd.getFileDescriptor()).getChannel().map(
                FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength()
            )
        );
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }

    /**
     * 对 Bitmap 进行 NSFW 分类。
     * @return float[2] = {sfwScore, nsfwScore}，各 0~1
     */
    public float[] classify(Bitmap bitmap) {
        if (interpreter == null) throw new IllegalStateException("Classifier not initialized");

        float[][] output = new float[1][2];
        interpreter.run(bitmapToByteBuffer(bitmap), output);

        DecimalFormat df = new DecimalFormat("0.00000000");
        return new float[]{
            Float.parseFloat(df.format(output[0][0])),
            Float.parseFloat(df.format(output[0][1]))
        };
    }

    private ByteBuffer bitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 * INPUT_WIDTH * INPUT_HEIGHT * 3 * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.rewind();

        int[] pixels = new int[INPUT_WIDTH * INPUT_HEIGHT];
        bitmap.getPixels(pixels, 0, INPUT_WIDTH,
            Math.max((bitmap.getHeight() - INPUT_HEIGHT) / 2, 0),
            Math.max((bitmap.getWidth() - INPUT_WIDTH) / 2, 0),
            INPUT_WIDTH, INPUT_HEIGHT);

        for (int color : pixels) {
            buffer.putFloat(Color.blue(color) - VGG_MEAN[0]);
            buffer.putFloat(Color.green(color) - VGG_MEAN[1]);
            buffer.putFloat(Color.red(color) - VGG_MEAN[2]);
        }
        return buffer;
    }
}
