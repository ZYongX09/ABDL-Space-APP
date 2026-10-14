package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Decorative backgrounds fill the measured content; they must not size a wrap-content card. */
final class MapGlassContainer extends FrameLayout{
	private final View material;

	MapGlassContainer(Context context,View material){
		super(context); this.material=material;
		addView(material,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
	}

	@Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
		int width=0,height=0,state=0;
		for(int i=0;i<getChildCount();i++){
			View child=getChildAt(i);
			if(child==material || child.getVisibility()==GONE) continue;
			measureChildWithMargins(child,widthMeasureSpec,0,heightMeasureSpec,0);
			LayoutParams params=(LayoutParams)child.getLayoutParams();
			width=Math.max(width,child.getMeasuredWidth()+params.leftMargin+params.rightMargin);
			height=Math.max(height,child.getMeasuredHeight()+params.topMargin+params.bottomMargin);
			state=combineMeasuredStates(state,child.getMeasuredState());
		}
		width=Math.max(getSuggestedMinimumWidth(),width+getPaddingLeft()+getPaddingRight());
		height=Math.max(getSuggestedMinimumHeight(),height+getPaddingTop()+getPaddingBottom());
		setMeasuredDimension(resolveSizeAndState(width,widthMeasureSpec,state),resolveSizeAndState(height,heightMeasureSpec,state<<MEASURED_HEIGHT_STATE_SHIFT));
		for(int i=0;i<getChildCount();i++){
			View child=getChildAt(i);
			if(child.getVisibility()==GONE) continue;
			LayoutParams params=(LayoutParams)child.getLayoutParams();
			int innerWidth=Math.max(0,getMeasuredWidth()-getPaddingLeft()-getPaddingRight()-params.leftMargin-params.rightMargin);
			int innerHeight=Math.max(0,getMeasuredHeight()-getPaddingTop()-getPaddingBottom()-params.topMargin-params.bottomMargin);
			int childWidth=child==material || params.width==ViewGroup.LayoutParams.MATCH_PARENT
					? MeasureSpec.makeMeasureSpec(innerWidth,MeasureSpec.EXACTLY)
					: getChildMeasureSpec(MeasureSpec.makeMeasureSpec(getMeasuredWidth(),MeasureSpec.EXACTLY),getPaddingLeft()+getPaddingRight()+params.leftMargin+params.rightMargin,params.width);
			int childHeight=child==material || params.height==ViewGroup.LayoutParams.MATCH_PARENT
					? MeasureSpec.makeMeasureSpec(innerHeight,MeasureSpec.EXACTLY)
					: getChildMeasureSpec(MeasureSpec.makeMeasureSpec(getMeasuredHeight(),MeasureSpec.EXACTLY),getPaddingTop()+getPaddingBottom()+params.topMargin+params.bottomMargin,params.height);
			child.measure(childWidth,childHeight);
		}
	}
}
