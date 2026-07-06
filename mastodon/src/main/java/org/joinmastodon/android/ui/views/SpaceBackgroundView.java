package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 宇宙星空背景 View
 * 渐变背景 + 光晕 + 星星闪烁 + 星球 + 尘埃
 * 支持深色/浅色模式
 */
public class SpaceBackgroundView extends View {

    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint planetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dustPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean isDarkMode = false;
    private float time = 0f;

    // 深色模式颜色
    private int darkTop = Color.parseColor("#0B0F1A");
    private int darkMid = Color.parseColor("#101B34");
    private int darkBot = Color.parseColor("#1A1628");

    // 浅色模式颜色
    private int lightTop = Color.parseColor("#F4F7FF");
    private int lightMid = Color.parseColor("#EAF1FF");
    private int lightBot = Color.parseColor("#FFFFFF");

    private boolean initialized = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            time += 0.033f;
            invalidate();
            postDelayed(this, 33); // ~30fps
        }
    };

    private final List<float[]> dusts = new ArrayList<>();

    public SpaceBackgroundView(Context context) { this(context, null); }
    public SpaceBackgroundView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public SpaceBackgroundView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        glowPaint.setStyle(Paint.Style.FILL);
        planetPaint.setStyle(Paint.Style.FILL);
        dustPaint.setStyle(Paint.Style.FILL);
    }

    public void setDarkMode(boolean dark) {
        this.isDarkMode = dark;
        initialized = false;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        initialized = false;
    }

    private void initStars(int w, int h) {
        dusts.clear();
        Random r = new Random(42);
        for (int i = 0; i < 80; i++) {
            dusts.add(new float[]{r.nextFloat() * w, r.nextFloat() * h, 3 + r.nextInt(6)});
        }
        initialized = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (!initialized) initStars(w, h);

        // 1. 渐变背景
        int topColor = isDarkMode ? darkTop : lightTop;
        int midColor = isDarkMode ? darkMid : lightMid;
        int botColor = isDarkMode ? darkBot : lightBot;
        gradientPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{topColor, midColor, botColor}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, gradientPaint);

        // 2. 光晕（呼吸效果）
        float breathAlpha = 0.7f + 0.3f * (float) Math.sin(time * 0.8);
        int glowAlpha1 = (int) (isDarkMode ? 50 * breathAlpha : 30 * breathAlpha);
        int glowAlpha2 = (int) (isDarkMode ? 35 * breathAlpha : 20 * breathAlpha);
        int glowAlpha3 = (int) (isDarkMode ? 30 * breathAlpha : 18 * breathAlpha);

        drawGlow(canvas, w / 2f, dp(180), dp(200), isDarkMode ? 0x226688FF : 0x1888BBFF, glowAlpha1);
        drawGlow(canvas, dp(60), dp(100), dp(150), isDarkMode ? 0x185577EE : 0x1288AADD, glowAlpha2);
        drawGlow(canvas, w - dp(80), h - dp(200), dp(170), isDarkMode ? 0x18446688 : 0x1099BBCC, glowAlpha3);

        // 3. 星球装饰
        if (isDarkMode) {
            planetPaint.setColor(Color.parseColor("#1A2540"));
            planetPaint.setAlpha(40);
            canvas.drawCircle(dp(120), dp(160), dp(40), planetPaint);
            planetPaint.setAlpha(25);
            canvas.drawCircle(w - dp(90), h - dp(250), dp(55), planetPaint);
            // 星球环
            Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(2));
            ringPaint.setColor(Color.parseColor("#33557788"));
            ringPaint.setAlpha(30);
            canvas.drawOval(w - dp(140), h - dp(280), w - dp(40), h - dp(220), ringPaint);
        } else {
            planetPaint.setColor(Color.parseColor("#D8E8F8"));
            planetPaint.setAlpha(15);
            canvas.drawCircle(dp(100), dp(140), dp(35), planetPaint);
            planetPaint.setAlpha(10);
            canvas.drawCircle(w - dp(70), h - dp(220), dp(45), planetPaint);
        }

        // 4. 宇宙尘埃（仅深色模式）
        if (isDarkMode) {
            int dustAlpha = 15;
            dustPaint.setColor(Color.WHITE);
            for (float[] d : dusts) {
                dustPaint.setAlpha(dustAlpha);
                canvas.drawCircle(d[0], d[1], d[2], dustPaint);
            }
        }
    }

    private void drawGlow(Canvas canvas, float x, float y, float radius, int color, int alpha) {
        RadialGradient g = new RadialGradient(x, y, radius,
                new int[]{alpha << 24 | (color & 0x00FFFFFF), Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        glowPaint.setShader(g);
        canvas.drawCircle(x, y, radius, glowPaint);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
