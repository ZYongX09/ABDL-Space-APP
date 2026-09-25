package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import androidx.annotation.Nullable;
import me.grishka.appkit.utils.V;

public class BabyVerificationProgressView extends View{
	public static final int STAGE_INFORMATION=1;
	public static final int STAGE_PHOTO=2;
	public static final int STAGE_REVIEW=3;

	private final Paint fillPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint strokePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint numberPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
	private final String[] labels=new String[3];
	private int stage=STAGE_INFORMATION;
	private boolean complete;

	public BabyVerificationProgressView(Context context){
		this(context, null);
	}

	public BabyVerificationProgressView(Context context, @Nullable AttributeSet attrs){
		super(context, attrs);
		labels[0]=context.getString(R.string.verification_step_information);
		labels[1]=context.getString(R.string.verification_step_photo);
		labels[2]=context.getString(R.string.verification_step_review);
		strokePaint.setStyle(Paint.Style.STROKE);
		strokePaint.setStrokeWidth(V.dp(1));
		numberPaint.setTextAlign(Paint.Align.CENTER);
		numberPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
		numberPaint.setTextSize(sp(12));
		labelPaint.setTextAlign(Paint.Align.CENTER);
		labelPaint.setTextSize(sp(12));
		setMinimumHeight(V.dp(74));
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
		updateAccessibilityDescription();
	}

	public void setStage(int stage, boolean complete){
		this.stage=Math.max(STAGE_INFORMATION, Math.min(STAGE_REVIEW, stage));
		this.complete=complete;
		updateAccessibilityDescription();
		invalidate();
	}

	public int getStage(){
		return stage;
	}

	public boolean isComplete(){
		return complete;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
		int availableWidth=MeasureSpec.getSize(widthMeasureSpec);
		int desired=isVertical(availableWidth) ? V.dp(168) : V.dp(74);
		int height=resolveSize(desired, heightMeasureSpec);
		setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec), height);
	}

	@Override
	protected void onDraw(Canvas canvas){
		super.onDraw(canvas);
		int width=getWidth();
		if(width<=0) return;
		if(isVertical(width)) drawVertical(canvas, width);
		else drawHorizontal(canvas, width);
	}

	private void drawHorizontal(Canvas canvas, int width){
		float cy=V.dp(25);
		float radius=V.dp(13);
		float[] centers={width/6f, width/2f, width*5f/6f};
		int primary=primary();
		int inactiveOutline=inactiveOutline();
		fillPaint.setColor(inactiveOutline);
		for(int i=0;i<2;i++){
			float start=centers[i]+radius;
			float end=centers[i+1]-radius;
			canvas.drawRoundRect(start, cy-V.dp(1.5f), end, cy+V.dp(1.5f), V.dp(1.5f), V.dp(1.5f), fillPaint);
			if(complete || stage>i+1){
				fillPaint.setColor(primary);
				canvas.drawRoundRect(start, cy-V.dp(1.5f), end, cy+V.dp(1.5f), V.dp(1.5f), V.dp(1.5f), fillPaint);
				fillPaint.setColor(inactiveOutline);
			}
		}
		Paint.FontMetrics labelMetrics=labelPaint.getFontMetrics();
		float labelBaseline=V.dp(60)-labelMetrics.descent;
		for(int i=0;i<3;i++) drawStep(canvas, centers[i], cy, centers[i], labelBaseline, i, true);
	}

	private void drawVertical(Canvas canvas, int width){
		float radius=V.dp(13);
		float cx=V.dp(27);
		float[] centers={V.dp(28), V.dp(82), V.dp(136)};
		int primary=primary();
		int inactiveOutline=inactiveOutline();
		fillPaint.setColor(inactiveOutline);
		for(int i=0;i<2;i++){
			float start=centers[i]+radius;
			float end=centers[i+1]-radius;
			canvas.drawRoundRect(cx-V.dp(1.5f), start, cx+V.dp(1.5f), end, V.dp(1.5f), V.dp(1.5f), fillPaint);
			if(complete || stage>i+1){
				fillPaint.setColor(primary);
				canvas.drawRoundRect(cx-V.dp(1.5f), start, cx+V.dp(1.5f), end, V.dp(1.5f), V.dp(1.5f), fillPaint);
				fillPaint.setColor(inactiveOutline);
			}
		}
		labelPaint.setTextAlign(Paint.Align.LEFT);
		Paint.FontMetrics labelMetrics=labelPaint.getFontMetrics();
		for(int i=0;i<3;i++){
			float labelBaseline=centers[i]-(labelMetrics.ascent+labelMetrics.descent)/2f;
			drawStep(canvas, cx, centers[i], V.dp(54), labelBaseline, i, false);
		}
		labelPaint.setTextAlign(Paint.Align.CENTER);
	}

	private void drawStep(Canvas canvas, float circleX, float circleY, float labelX, float labelBaseline, int index, boolean centeredLabel){
		boolean reached=complete || index+1<=stage;
		boolean current=!complete && index+1==stage;
		fillPaint.setColor(reached ? primary() : inactive());
		canvas.drawCircle(circleX, circleY, V.dp(13), fillPaint);
		if(!reached){
			strokePaint.setColor(inactiveOutline());
			canvas.drawCircle(circleX, circleY, V.dp(12.5f), strokePaint);
		}
		Paint.FontMetrics numberMetrics=numberPaint.getFontMetrics();
		numberPaint.setColor(reached ? onPrimary() : secondary());
		canvas.drawText(String.valueOf(index+1), circleX, circleY-(numberMetrics.ascent+numberMetrics.descent)/2f, numberPaint);
		labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, current || complete ? Typeface.BOLD : Typeface.NORMAL));
		labelPaint.setColor(reached ? primary() : secondary());
		canvas.drawText(labels[index], labelX, labelBaseline, labelPaint);
	}

	private boolean isVertical(int width){
		return width<V.dp(328) || getResources().getConfiguration().fontScale>=1.3f;
	}

	private int primary(){ return UiUtils.getThemeColor(getContext(), R.attr.colorVerificationPrimary); }
	private int onPrimary(){ return UiUtils.getThemeColor(getContext(), R.attr.colorVerificationOnPrimary); }
	private int inactive(){ return UiUtils.getThemeColor(getContext(), R.attr.colorVerificationStepInactive); }
	private int inactiveOutline(){ return UiUtils.getThemeColor(getContext(), R.attr.colorVerificationStepInactiveOutline); }
	private int secondary(){ return UiUtils.getThemeColor(getContext(), R.attr.colorVerificationTextSecondary); }

	private void updateAccessibilityDescription(){
		setContentDescription(complete
				? getContext().getString(R.string.verification_progress_complete)
				: getContext().getString(R.string.verification_progress_current, stage, labels[stage-1]));
	}

	private float sp(float value){
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
	}
}
