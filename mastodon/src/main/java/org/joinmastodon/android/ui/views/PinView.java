package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/**
 * 6位验证码输入框组件（美团风格）
 */
public class PinView extends EditText {

    private static final int PIN_LENGTH = 6;
    private static final int CHAR_SIZE_DP = 44;
    private static final int CHAR_GAP_DP = 10;
    private static final int CHAR_RADIUS_DP = 12;
    private static final int PADDING_DP = 16;

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int charSize;
    private int charGap;
    private float charRadius;
    private int boxColor = 0xFFF5F5F5;
    private int boxActiveColor = 0xFFE8E8E8;
    private int boxStrokeColor = 0xFFA1D9F7;
    private int textColor = 0xFF1A1A1A;
    private int cursorColor = 0xFF4FC3F7;

    private OnPinCompleteListener onPinCompleteListener;

    public interface OnPinCompleteListener {
        void onPinComplete(String pin);
    }

    public PinView(Context context) { this(context, null); }
    public PinView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public PinView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        charSize = dpToPx(CHAR_SIZE_DP);
        charGap = dpToPx(CHAR_GAP_DP);
        charRadius = dpToPx(CHAR_RADIUS_DP);

        setFilters(new InputFilter[]{ new InputFilter.LengthFilter(PIN_LENGTH) });
        setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_NORMAL);
        setImeOptions(EditorInfo.IME_ACTION_DONE);
        setCursorVisible(false);
        setTextSize(0);

        boxPaint.setStyle(Paint.Style.FILL);
        boxPaint.setColor(boxColor);

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(textColor);
        textPaint.setTextSize(dpToPx(22));
        textPaint.setTextAlign(Paint.Align.CENTER);

        boxStrokePaint.setStyle(Paint.Style.STROKE);
        boxStrokePaint.setStrokeWidth(dpToPx(1.5f));
        boxStrokePaint.setColor(boxStrokeColor);

        cursorPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setColor(cursorColor);

        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                invalidate();
                if (s.length() == PIN_LENGTH && onPinCompleteListener != null) {
                    onPinCompleteListener.onPinComplete(s.toString());
                }
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int length = getText().length();
        int totalWidth = PIN_LENGTH * charSize + (PIN_LENGTH - 1) * charGap;
        float startX = (getWidth() - totalWidth) / 2f;
        float centerY = getHeight() / 2f;

        for (int i = 0; i < PIN_LENGTH; i++) {
            float x = startX + i * (charSize + charGap);
            RectF rect = new RectF(x, centerY - charSize / 2f, x + charSize, centerY + charSize / 2f);

            boxPaint.setColor(i == length ? boxActiveColor : boxColor);
            canvas.drawRoundRect(rect, charRadius, charRadius, boxPaint);
            // 蓝色描边
            canvas.drawRoundRect(rect, charRadius, charRadius, boxStrokePaint);

            // Cursor
            if (i == length && hasFocus()) {
                float cw = dpToPx(2);
                canvas.drawRoundRect(
                    new RectF(x + charSize / 2f - cw / 2, centerY - charSize / 3f,
                              x + charSize / 2f + cw / 2, centerY + charSize / 3f),
                    cw / 2, cw / 2, cursorPaint);
            }

            // Text
            if (i < length) {
                String ch = String.valueOf(getText().charAt(i));
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textY = centerY - (fm.ascent + fm.descent) / 2;
                canvas.drawText(ch, x + charSize / 2f, textY, textPaint);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int totalWidth = PIN_LENGTH * charSize + (PIN_LENGTH - 1) * charGap + dpToPx(PADDING_DP) * 2;
        int height = charSize + dpToPx(PADDING_DP) * 2;
        setMeasuredDimension(totalWidth, height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            requestFocus();
            showKeyboard();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (focused) {
            setSelection(getText().length());
            showKeyboard();
        }
        invalidate();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            postDelayed(() -> imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT), 100);
        }
    }

    public void setOnPinCompleteListener(OnPinCompleteListener listener) {
        this.onPinCompleteListener = listener;
    }

    public void clearPin() {
        setText("");
        invalidate();
    }

    private int dpToPx(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
