package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 简单的流式布局，支持子View自动换行
 */
public class FlowLayout extends ViewGroup {

	public FlowLayout(Context context) {
		super(context);
	}

	public FlowLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public FlowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int widthSize = MeasureSpec.getSize(widthMeasureSpec);
		int widthMode = MeasureSpec.getMode(widthMeasureSpec);

		int paddingLeft = getPaddingLeft();
		int paddingRight = getPaddingRight();
		int paddingTop = getPaddingTop();
		int paddingBottom = getPaddingBottom();

		int childLeft = paddingLeft;
		int childTop = paddingTop;
		int rowHeight = 0;
		int maxWidth = 0;

		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == GONE) continue;

			measureChild(child, widthMeasureSpec, heightMeasureSpec);
			int childWidth = child.getMeasuredWidth();
			int childHeight = child.getMeasuredHeight();

			if (childLeft + childWidth > widthSize - paddingRight) {
				childLeft = paddingLeft;
				childTop += rowHeight;
				rowHeight = 0;
			}

			childLeft += childWidth;
			rowHeight = Math.max(rowHeight, childHeight);
			maxWidth = Math.max(maxWidth, childLeft);
		}

		int desiredWidth = Math.max(maxWidth, getSuggestedMinimumWidth());
		int desiredHeight = childTop + rowHeight + paddingBottom;

		setMeasuredDimension(
			resolveSize(desiredWidth, widthMeasureSpec),
			resolveSize(desiredHeight, heightMeasureSpec)
		);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int paddingLeft = getPaddingLeft();
		int paddingRight = getPaddingRight();
		int paddingTop = getPaddingTop();

		int childLeft = paddingLeft;
		int childTop = paddingTop;
		int rowHeight = 0;
		int parentWidth = r - l;

		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == GONE) continue;

			int childWidth = child.getMeasuredWidth();
			int childHeight = child.getMeasuredHeight();

			if (childLeft + childWidth > parentWidth - paddingRight) {
				childLeft = paddingLeft;
				childTop += rowHeight;
				rowHeight = 0;
			}

			child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
			childLeft += childWidth;
			rowHeight = Math.max(rowHeight, childHeight);
		}
	}
}
