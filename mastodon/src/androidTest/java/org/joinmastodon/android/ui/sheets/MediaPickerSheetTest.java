package org.joinmastodon.android.ui.sheets;

import static org.junit.Assert.assertNotNull;

import android.view.MotionEvent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.joinmastodon.android.ui.media.MediaPickerConfig;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class MediaPickerSheetTest{
	@Test
	public void draggingHeaderHasAttachedSheetContent(){
		try(ActivityScenario<MediaPickerSheetTestActivity> scenario=ActivityScenario.launch(MediaPickerSheetTestActivity.class)){
			scenario.onActivity(activity->{
				MediaPickerSheet sheet=new MediaPickerSheet(activity, new MediaPickerConfig(), new MediaPickerSheet.Listener(){
					@Override public void onMediaSelected(ArrayList<android.net.Uri> uris){}
					@Override public void onCameraRequested(){}
				});
				sheet.show();
				try{
					Field rootField=MediaPickerSheet.class.getDeclaredField("root");
					rootField.setAccessible(true);
					android.view.View root=(android.view.View)rootField.get(sheet);
					assertNotNull(root);
					MotionEvent down=MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 10, 0);
					root.dispatchTouchEvent(down);
					down.recycle();
				}catch(ReflectiveOperationException e){
					throw new AssertionError(e);
				}
				sheet.dismiss();
			});
		}
	}
}
