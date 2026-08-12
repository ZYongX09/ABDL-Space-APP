package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.joinmastodon.android.R;

import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

import androidx.annotation.IdRes;

public class TabBar extends LinearLayout{
	@IdRes
	private int selectedTabID;
	private IntConsumer listener;
	private IntPredicate longClickListener;

	public TabBar(Context context){
		this(context, null);
	}

	public TabBar(Context context, AttributeSet attrs){
		this(context, attrs, 0);
	}

	public TabBar(Context context, AttributeSet attrs, int defStyle){
		super(context, attrs, defStyle);
	}

	@Override
	public void onViewAdded(View child){
		super.onViewAdded(child);
		if(child.getId()!=0){
			if(selectedTabID==0){
				selectedTabID=child.getId();
				child.setSelected(true);
			}
			child.setOnClickListener(this::onChildClick);
			child.setOnLongClickListener(this::onChildLongClick);
		}
	}

	private void onChildClick(View v){
		vibrate();
		View icon=v instanceof FrameLayout ? ((FrameLayout)v).getChildAt(0) : null;
		if(icon instanceof ItshoverNavigationIconView animatedIcon){
			animatedIcon.playAnimation();
		}else if(icon instanceof ImageView){
			icon.animate().cancel();
			icon.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction(()->icon.animate().scaleX(1f).scaleY(1f).setDuration(180).start()).start();
		}
		listener.accept(v.getId());
		if(v.getId()==selectedTabID)
			return;
		findViewById(selectedTabID).setSelected(false);
		v.setSelected(true);
		selectedTabID=v.getId();
	}

	private boolean onChildLongClick(View v){
		return longClickListener.test(v.getId());
	}

	public void setListeners(IntConsumer listener, IntPredicate longClickListener){
		this.listener=listener;
		this.longClickListener=longClickListener;
	}

	public void selectTab(int id){
		findViewById(selectedTabID).setSelected(false);
		selectedTabID=id;
		findViewById(selectedTabID).setSelected(true);
	}

	private void vibrate(){
		try{
			View child = getChildAt(0);
			if(child != null) child.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
		}catch(Exception ignored){}
	}
}
