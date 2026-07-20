package org.joinmastodon.android.ui.utils;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.displayitems.MediaGridStatusDisplayItem;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class MediaAttachmentViewControllerTest{
	@Test
	public void loadedImageReplacesPlaceholderAfterInitialBind() throws Exception{
		Context context=ApplicationProvider.getApplicationContext();
		Bitmap placeholderBitmap=solidBitmap(Color.RED);
		Bitmap imageBitmap=solidBitmap(Color.BLUE);
		Attachment attachment=new Attachment();
		attachment.type=Attachment.Type.IMAGE;
		attachment.blurhashPlaceholder=new BitmapDrawable(context.getResources(), placeholderBitmap);
		Status status=new Status();
		status.mediaAttachments=new ArrayList<>();
		status.mediaAttachments.add(attachment);
		MediaAttachmentViewController[] controller=new MediaAttachmentViewController[1];
		InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
			controller[0]=new MediaAttachmentViewController(context, MediaGridStatusDisplayItem.GridItemType.PHOTO);
			controller[0].bind(attachment, status);
			controller[0].setImage(new BitmapDrawable(context.getResources(), imageBitmap));
		});
		Thread.sleep(350);

		Bitmap rendered=Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
			controller[0].photo.layout(0, 0, 2, 2);
			controller[0].photo.draw(new Canvas(rendered));
		});
		assertEquals(Color.BLUE, rendered.getPixel(0, 0));
	}

	private static Bitmap solidBitmap(int color){
		Bitmap bitmap=Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(color);
		return bitmap;
	}
}
