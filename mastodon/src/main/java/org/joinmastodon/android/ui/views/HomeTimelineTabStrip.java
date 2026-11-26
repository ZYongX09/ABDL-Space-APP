package org.joinmastodon.android.ui.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.List;
import java.util.function.IntConsumer;

import me.grishka.appkit.utils.V;

public final class HomeTimelineTabStrip extends LinearLayout{
	public static final int HEIGHT_DP=48;
	private static final int MIN_TAB_WIDTH_DP=56, OVERFLOW_EXTRA_DP=24, OVERFLOW_INSET_DP=8;
	private static final Typeface LABEL_TYPEFACE=Typeface.create("sans-serif", Typeface.BOLD);

	public record Tab(int timelineIndex, CharSequence title, @DrawableRes int icon, boolean tintIcon, boolean alwaysShowIcon){}

	private final TabRow row;
	private final HorizontalScrollView scroller;
	private final IntConsumer onTabClick;
	private final int selectedColor, unselectedColor, backgroundColor;
	private final Paint divider=new Paint();
	private int selectedPage=-1, laidOutViewportWidth=-1, laidOutDirection=-1;
	private boolean centerAfterLayout, animateCenterAfterLayout;

	public HomeTimelineTabStrip(Context context, IntConsumer onTabClick, Runnable onEditTimelines){
		super(context);
		this.onTabClick=onTabClick;
		selectedColor=UiUtils.getThemeColor(context, R.attr.colorM3OnSurface);
		unselectedColor=UiUtils.getThemeColor(context, R.attr.colorM3OnSurfaceVariant);
		backgroundColor=UiUtils.getThemeColor(context, R.attr.colorM3Background);
		setOrientation(HORIZONTAL);
		setBackgroundColor(backgroundColor);
		setWillNotDraw(false);
		divider.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OutlineVariant));
		divider.setAlpha(72);

		row=new TabRow(context);
		scroller=new HorizontalScrollView(context){
			@Override
			protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
				int viewport=MeasureSpec.getSize(widthMeasureSpec);
				if(row.viewportWidth!=viewport){
					row.viewportWidth=viewport;
					row.forceLayout();
				}
				super.onMeasure(widthMeasureSpec, heightMeasureSpec);
			}

			@Override
			public int getSolidColor(){ return backgroundColor; }
		};
		scroller.setHorizontalScrollBarEnabled(false);
		scroller.setHorizontalFadingEdgeEnabled(true);
		scroller.setFadingEdgeLength(V.dp(20));
		scroller.setOverScrollMode(OVER_SCROLL_NEVER);
		scroller.addView(row, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
		addView(scroller, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

		ImageButton edit=new ImageButton(context);
		edit.setImageResource(R.drawable.ic_fluent_add_24_regular);
		edit.setImageTintList(ColorStateList.valueOf(unselectedColor));
		edit.setScaleType(ImageView.ScaleType.FIT_CENTER);
		edit.setBackground(UiUtils.getThemeDrawable(context, android.R.attr.selectableItemBackground));
		edit.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(16));
		edit.setContentDescription(context.getText(R.string.sk_edit_timelines));
		edit.setTooltipText(edit.getContentDescription());
		edit.setOnClickListener(v->onEditTimelines.run());
		LayoutParams editParams=new LayoutParams(V.dp(48), LayoutParams.MATCH_PARENT);
		editParams.setMarginEnd(V.dp(4));
		addView(edit, editParams);
	}

	public void setTabs(List<Tab> tabs, int page){
		row.removeAllViews();
		for(Tab tab:tabs) row.addView(new TabView(getContext(), tab));
		selectedPage=-1;
		setSelectedPage(page, false);
		setScrollPosition(page);
	}

	public void setSelectedPage(int page, boolean animate){
		if(page<0 || page>=row.getChildCount() || page==selectedPage) return;
		boolean widthMayChange=selectedPage>=0 && ((TabView)row.getChildAt(selectedPage)).setTabSelected(false);
		selectedPage=page;
		widthMayChange|=((TabView)row.getChildAt(page)).setTabSelected(true);
		if(widthMayChange || getVisibility()!=VISIBLE || !isLaidOut() || isLayoutRequested() || row.isLayoutRequested()){
			centerAfterLayout=true;
			animateCenterAfterLayout=animate;
		}else{
			centerSelectedTab(animate);
		}
	}

	public void setScrollPosition(float page){
		row.page=page;
		row.invalidate();
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom){
		super.onLayout(changed, left, top, right, bottom);
		int viewport=scroller.getWidth(), direction=getLayoutDirection();
		if(centerAfterLayout || viewport!=laidOutViewportWidth || direction!=laidOutDirection){
			boolean animate=centerAfterLayout && animateCenterAfterLayout && viewport==laidOutViewportWidth && direction==laidOutDirection;
			centerAfterLayout=false;
			laidOutViewportWidth=viewport;
			laidOutDirection=direction;
			centerSelectedTab(animate);
		}
	}

	@Override
	protected void onDraw(Canvas canvas){
		super.onDraw(canvas);
		canvas.drawRect(0, getHeight()-V.dp(1), getWidth(), getHeight(), divider);
	}

	private void centerSelectedTab(boolean animate){
		if(selectedPage<0) return;
		TabView tab=(TabView)row.getChildAt(selectedPage);
		int viewport=scroller.getWidth();
		int target=(tab.getLeft()+tab.getRight()-viewport)/2;
		target=Math.max(0, Math.min(target, row.getWidth()-viewport));
		if(target==scroller.getScrollX()) return;
		if(animate && ValueAnimator.areAnimatorsEnabled()) scroller.smoothScrollTo(target, 0);
		else scroller.scrollTo(target, 0);
	}

	private final class TabRow extends ViewGroup{
		private final Paint indicator=new Paint(Paint.ANTI_ALIAS_FLAG);
		private int viewportWidth, leadingInset;
		private float page;

		TabRow(Context context){
			super(context);
			indicator.setColor(selectedColor);
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
			int count=getChildCount();
			int height=MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightMeasureSpec) : V.dp(HEIGHT_DP);
			int heightSpec=MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
			int minTabWidth=V.dp(MIN_TAB_WIDTH_DP), extra=V.dp(OVERFLOW_EXTRA_DP), inset=V.dp(OVERFLOW_INSET_DP);
			int maxTabWidth=Math.max(minTabWidth, viewportWidth-extra-inset);
			int total=0, inactiveTotal=0, largestIconDelta=0, widest=0;
			boolean hasSelectionIcons=false;
			for(int i=0;i<count;i++){
				TabView child=(TabView)getChildAt(i);
				int activeWidth=child.intrinsicWidth(true, maxTabWidth);
				int inactiveWidth=child.intrinsicWidth(false, maxTabWidth);
				int width=child.isSelected() ? activeWidth : inactiveWidth;
				total+=width;
				widest=Math.max(widest, width);
				inactiveTotal+=inactiveWidth;
				largestIconDelta=Math.max(largestIconDelta, activeWidth-inactiveWidth);
				hasSelectionIcons|=child.icon!=null && !child.alwaysShowIcon;
			}

			// Reserve the largest optional icon when deciding to scroll, so a change of
			// selection alone does not switch between fitting and overflowing.
			boolean overflow=inactiveTotal+largestIconDelta+extra*count>viewportWidth;
			leadingInset=overflow ? inset : 0;
			int spare=Math.max(0, viewportWidth-leadingInset-total-(overflow ? extra*count : 0));
			int each=count==0 ? 0 : spare/count;
			int remainder=count==0 ? 0 : spare%count;
			boolean equal=!overflow && !hasSelectionIcons && count>0 && widest<=viewportWidth/count;
			int width=leadingInset;
			for(int i=0;i<count;i++){
				TabView child=(TabView)getChildAt(i);
				int tabWidth=equal ? viewportWidth/count+(i<viewportWidth%count ? 1 : 0)
						: child.intrinsicWidth(child.isSelected(), maxTabWidth)+(overflow ? extra : 0)+each+(i<remainder ? 1 : 0);
				int gutter=overflow ? Math.min(extra/2, Math.max(0, (tabWidth-minTabWidth)/2)) : 0;
				if(child.getPaddingLeft()!=gutter) child.setPadding(gutter, 0, gutter, 0);
				child.measure(MeasureSpec.makeMeasureSpec(tabWidth, MeasureSpec.EXACTLY), heightSpec);
				width+=tabWidth;
			}
			setMeasuredDimension(Math.max(width, viewportWidth), height);
		}

		@Override
		protected void onLayout(boolean changed, int left, int top, int right, int bottom){
			boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;
			int x=rtl ? getWidth()-leadingInset : leadingInset;
			for(int i=0;i<getChildCount();i++){
				TabView child=(TabView)getChildAt(i);
				int width=child.getMeasuredWidth();
				int start=rtl ? x-width : x;
				child.layout(start, 0, start+width, getHeight());
				x+=rtl ? -width : width;
			}
		}

		@Override
		protected void dispatchDraw(Canvas canvas){
			super.dispatchDraw(canvas);
			int last=getChildCount()-1;
			if(last<0) return;
			float position=Math.max(0, Math.min(last, page));
			int fromIndex=(int)position;
			float fraction=position-fromIndex;
			TabView from=(TabView)getChildAt(fromIndex), to=(TabView)getChildAt(Math.min(last, fromIndex+1));
			float left=from.getLeft()+(to.getLeft()-from.getLeft())*fraction;
			float right=from.getRight()+(to.getRight()-from.getRight())*fraction;
			float height=V.dp(2), bottom=getHeight();
			canvas.drawRoundRect(left, bottom-height, right, bottom, height/2, height/2, indicator);
		}

		@Override
		public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
			super.onInitializeAccessibilityNodeInfo(info);
			info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(1, getChildCount(), false,
					AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
		}
	}

	private final class TabView extends FrameLayout{
		private final TextView label;
		private final Drawable icon;
		private final boolean tintIcon, alwaysShowIcon;

		TabView(Context context, Tab tab){
			super(context);
			setMinimumWidth(V.dp(MIN_TAB_WIDTH_DP));
			setBackground(UiUtils.getThemeDrawable(context, android.R.attr.selectableItemBackground));
			setContentDescription(tab.title());
			setFocusable(true);
			setOnClickListener(v->onTabClick.accept(tab.timelineIndex()));
			label=new TextView(context);
			label.setText(tab.title());
			label.setTextSize(15);
			label.setTypeface(LABEL_TYPEFACE);
			label.setGravity(Gravity.CENTER);
			label.setIncludeFontPadding(false);
			label.setSingleLine(true);
			label.setEllipsize(TextUtils.TruncateAt.END);
			label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
			tintIcon=tab.tintIcon();
			alwaysShowIcon=tab.alwaysShowIcon();
			icon=tab.icon()==0 ? null : context.getDrawable(tab.icon()).mutate();
			if(icon!=null){
				icon.setBounds(0, 0, V.dp(20), V.dp(20));
				label.setCompoundDrawablePadding(V.dp(6));
			}
			addView(label, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
			setTabSelected(false);
		}

		int intrinsicWidth(boolean selected, int maxWidth){
			int width=(int)Math.ceil(Layout.getDesiredWidth(label.getText(), label.getPaint()));
			if(icon!=null && (selected || alwaysShowIcon)) width+=icon.getBounds().width()+label.getCompoundDrawablePadding();
			return Math.min(maxWidth, Math.max(getMinimumWidth(), width));
		}

		boolean setTabSelected(boolean selected){
			setSelected(selected);
			int color=selected ? selectedColor : unselectedColor;
			label.setTextColor(color);
			if(icon==null) return false;
			if(tintIcon) icon.setTint(color);
			Drawable displayed=selected || alwaysShowIcon ? icon : null;
			if(label.getCompoundDrawablesRelative()[0]==displayed) return false;
			label.setCompoundDrawablesRelative(displayed, null, null, null);
			return true;
		}

		@Override
		public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
			super.onInitializeAccessibilityNodeInfo(info);
			info.setClassName(Button.class.getName());
			info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, row.indexOfChild(this), 1, false, isSelected()));
		}
	}
}
