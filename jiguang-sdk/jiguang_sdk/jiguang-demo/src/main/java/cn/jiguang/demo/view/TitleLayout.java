package cn.jiguang.demo.view;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;


import cn.jiguang.demo.R;
import cn.jiguang.demo.baselibrary.DensityUtils;
import cn.jiguang.demo.baselibrary.StatusBarUtil;


/**
 * Create by wangqingqing
 * On 2019/5/28 17:32
 * Copyright(c) 2018 极光
 * Description
 */
public class TitleLayout extends FrameLayout {

    private View dividerView;
    private TextView titleTv;
    private ImageView backIv;
    private LinearLayout layoutLeft;
    private LinearLayout layoutRight;
    private View statusBar;

    private ConstraintLayout layoutTitle;
    private View layoutRoot;

    private int dividerHeight = 1;
    private int dividerMarginLeft;
    private int dividerMarginRight;

    private OnClickListener onBackClickListener;
    private View onlyAlphaSelfLayout;

    private int titleLeftMarginLeft;
    private int titleRightMarginRight;
    private ConstraintSet leftConstraintSet = new ConstraintSet();
    private ConstraintSet centerConstraintSet = new ConstraintSet();
    private ConstraintSet rightConstraintSet = new ConstraintSet();

    public TitleLayout(@NonNull Context context) {
        this(context, null);
    }

    public TitleLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TitleLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
        initAttr(context, attrs);
    }

    private void init(Context context) {
       View v =  LayoutInflater.from(context).inflate(R.layout.d_title_layout, this);

        statusBar = v.findViewById(R.id.status_bar);
        onlyAlphaSelfLayout = v.findViewById(R.id.layout_bg);

        dividerView = v.findViewById(R.id.divider);

        layoutLeft = v.findViewById(R.id.layout_left);

        layoutRight = v.findViewById(R.id.layout_right);

        titleTv = v.findViewById(R.id.tv_title);

        backIv = v.findViewById(R.id.title_back);
        backIv.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onBackClickListener != null) {
                    onBackClickListener.onClick(v);
                }
            }
        });

        layoutTitle = v.findViewById(R.id.layout_title);
        layoutRoot = v.findViewById(R.id.layout_root);

        leftConstraintSet.clone(layoutTitle);
        centerConstraintSet.clone(layoutTitle);
        rightConstraintSet.clone(layoutTitle);
    }

    private void initAttr(Context context, AttributeSet attrs) {
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.TitleLayout);

        boolean hasStatusBar = typedArray.getBoolean(R.styleable.TitleLayout_has_status_bar, false);
        int statusHeight = (int) typedArray.getDimension(R.styleable.TitleLayout_status_bar_height, 0);
        ViewGroup.LayoutParams barLayoutParams = statusBar.getLayoutParams();
        if (hasStatusBar) {
            barLayoutParams.height = StatusBarUtil.getStatusBarHeight(context);
            if (statusHeight > 0) {
                barLayoutParams.height = statusHeight;
            }
        } else {
            barLayoutParams.height = 0;
        }
        statusBar.setLayoutParams(barLayoutParams);
        int statusBarBackground = typedArray.getColor(R.styleable.TitleLayout_status_bar_color, Color.TRANSPARENT);
        statusBar.setBackgroundColor(statusBarBackground);

        boolean showBackIv = typedArray.getBoolean(R.styleable.TitleLayout_show_back_iv, false);
        backIv.setVisibility(showBackIv ? View.VISIBLE : View.GONE);
        Drawable drawable = typedArray.getDrawable(R.styleable.TitleLayout_back_iv_drawable);
        if (drawable != null) {
            backIv.setImageDrawable(drawable);
        }

        int titleLayoutHeight = DensityUtils.dp2px(getContext(), 50);
        titleLayoutHeight = typedArray.getDimensionPixelSize(R.styleable.TitleLayout_title_bar_height, titleLayoutHeight);
        ViewGroup.LayoutParams titleLayoutParams = layoutTitle.getLayoutParams();
        titleLayoutParams.height = titleLayoutHeight;
        layoutTitle.setLayoutParams(titleLayoutParams);
        int titleBarBackground = typedArray.getColor(R.styleable.TitleLayout_title_bar_color, Color.TRANSPARENT);
        layoutTitle.setBackgroundColor(titleBarBackground);

        int layoutRootBackground = typedArray.getColor(R.styleable.TitleLayout_title_root_color, Color.TRANSPARENT);
        layoutRoot.setBackgroundColor(layoutRootBackground);

        String titleText = typedArray.getString(R.styleable.TitleLayout_title_text);
        if (!TextUtils.isEmpty(titleText)) {
            titleTv.setText(titleText);
        } else {
            String label = getActivityLabel();
            if (!TextUtils.isEmpty(label)) {
                titleTv.setText(label);
            }
        }
        int titleTextSize = typedArray.getDimensionPixelSize(R.styleable.TitleLayout_title_text_size, 14);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleTextSize);
        int titleTextColor = typedArray.getColor(R.styleable.TitleLayout_title_text_color, Color.BLACK);
        titleTv.setTextColor(titleTextColor);
        titleLeftMarginLeft = typedArray.getDimensionPixelOffset(R.styleable.TitleLayout_title_left_margin_left, 0);
        titleRightMarginRight = typedArray.getDimensionPixelOffset(R.styleable.TitleLayout_title_right_margin_right, 0);
        int gravity = typedArray.getInt(R.styleable.TitleLayout_title_gravity, 0);
        setLayoutTitleGravity(gravity);


        int layoutLeftPaddingLeft = typedArray.getDimensionPixelSize(R.styleable.TitleLayout_left_layout_padding_left, 0);
        layoutLeft.setPadding(layoutLeftPaddingLeft, 0, 0, 0);
        int layoutRightPaddingRight = typedArray.getDimensionPixelSize(R.styleable.TitleLayout_right_layout_padding_right, 0);
        layoutRight.setPadding(0, 0, layoutRightPaddingRight, 0);

        int dividerColor = typedArray.getColor(R.styleable.TitleLayout_divider_color, Color.BLACK);
        dividerView.setBackgroundColor(dividerColor);
        dividerHeight = typedArray.getDimensionPixelSize(R.styleable.TitleLayout_divider_height, dividerHeight);
        dividerMarginLeft = typedArray.getDimensionPixelSize(R.styleable.TitleLayout_divider_margin_left, dividerMarginLeft);
        dividerMarginRight = typedArray.getDimensionPixelSize(R.styleable.TitleLayout_divider_margin_right, dividerMarginRight);
        ConstraintLayout.LayoutParams dividerViewLayoutParams = (ConstraintLayout.LayoutParams) dividerView.getLayoutParams();
        dividerViewLayoutParams.height = dividerHeight;
        dividerViewLayoutParams.leftMargin = dividerMarginLeft;
        dividerViewLayoutParams.rightMargin = dividerMarginRight;
        dividerView.setLayoutParams(dividerViewLayoutParams);

        typedArray.recycle();
    }

    private void setLayoutTitleGravity(int gravity) {
        int tvId = titleTv.getId();
        if (gravity == 0) {
            centerConstraintSet.connect(tvId, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT, 0);
            centerConstraintSet.connect(tvId, ConstraintSet.RIGHT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT, 0);
            centerConstraintSet.connect(tvId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0);
            centerConstraintSet.connect(tvId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0);
            centerConstraintSet.applyTo(layoutTitle);
        } else if (gravity == 1) {
            leftConstraintSet.clear(tvId, ConstraintSet.RIGHT);
            leftConstraintSet.connect(tvId, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT, 0);
            leftConstraintSet.connect(tvId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0);
            leftConstraintSet.connect(tvId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0);
            leftConstraintSet.setMargin(tvId, ConstraintSet.LEFT, titleLeftMarginLeft);
            leftConstraintSet.applyTo(layoutTitle);
        } else if (gravity == 2) {
            leftConstraintSet.clear(tvId, ConstraintSet.LEFT);
            rightConstraintSet.connect(tvId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 0);
            rightConstraintSet.connect(tvId, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 0);
            rightConstraintSet.connect(tvId, ConstraintSet.RIGHT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT, 0);
            rightConstraintSet.setMargin(tvId, ConstraintSet.LEFT, titleRightMarginRight);
            rightConstraintSet.applyTo(layoutTitle);
        }


    }


    private String getActivityLabel() {
        Context context = getContext();
        if (context instanceof Activity) {
            PackageManager pm = context.getPackageManager();
            try {
                ActivityInfo activityInfo = pm.getActivityInfo((((Activity) context).getComponentName()), 0);
                return activityInfo.loadLabel(pm).toString();
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    public View getDividerView() {
        return dividerView;
    }

    public TextView getTitleTv() {
        return titleTv;
    }

    public ImageView getBackIv() {
        return backIv;
    }

    public View getStatusBar() {
        return statusBar;
    }

    public View getLayoutTitle() {
        return layoutTitle;
    }

    public View getLayoutRoot() {
        return layoutRoot;
    }

    public LinearLayout getLayoutLeft() {
        return layoutLeft;
    }

    public LinearLayout getLayoutRight() {
        return layoutRight;
    }

    public void setOnBackClickListener(OnClickListener listener) {
        this.onBackClickListener = listener;
    }


    public View getAlphaWithOutTitle(boolean b) {
        return b ? onlyAlphaSelfLayout : layoutRoot;
    }

    /**
     * 跟ScrollView、RecyclerView 滑动事件
     *
     * @param view             ScrollView、RecyclerView
     * @param setAlphaOnlySelf 是否只是控件自身变化而不涉及其他控件变化 true只是自身变化 false整个布局变化
     * @param color            最终要变化的颜色值
     * @param height           变化的高度
     * @param listener         监听
     */
    public void attachScrollView(View view, boolean setAlphaOnlySelf, final int color, final int height, final OnAlphaScrollListener listener) {
        final View alphaView = getAlphaWithOutTitle(setAlphaOnlySelf);
        alphaView.setAlpha(0);
        alphaView.setBackgroundColor(color);

        if (view instanceof NestedScrollView) {
            ((NestedScrollView) view).setOnScrollChangeListener(new NestedScrollView.OnScrollChangeListener() {
                @Override
                public void onScrollChange(NestedScrollView v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                    setScrollAlpha(alphaView, height, scrollY, listener);
                }
            });
        } else if (view instanceof RecyclerView) {
            ((RecyclerView) view).addOnScrollListener(new RecyclerView.OnScrollListener() {
                private int totalDy = 0;

                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    totalDy += dy;
                    setScrollAlpha(alphaView, height, totalDy, listener);
                }
            });
        }
    }

    private void setScrollAlpha(View v, final int height, int scrollY, OnAlphaScrollListener listener) {
        float alpha = Math.min(1, (float) scrollY / height);
        v.setAlpha(alpha);
        if (listener != null) {
            listener.onAlphaScroll(alpha, scrollY);
        }
    }

    public interface OnAlphaScrollListener {
        void onAlphaScroll(float alpha, int scrollY);
    }
}
