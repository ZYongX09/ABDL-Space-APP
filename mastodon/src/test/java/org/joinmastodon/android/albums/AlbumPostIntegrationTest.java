package org.joinmastodon.android.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.FieldNamingPolicy;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.albums.AlbumModels.AlbumUpdate;
import org.joinmastodon.android.ui.displayitems.AlbumUpdateStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.TextStatusDisplayItem;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class AlbumPostIntegrationTest{
	private static final String FALLBACK="【宝宝相册】当前渠道不支持查看此内容，请下载最新版ABDL Space APP查看详情";

	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
	}

	private Status post(){
		Status status=Status.ofFake("p_101", FALLBACK, Instant.parse("2026-10-05T12:30:00Z"));
		status.albumUpdate=new AlbumUpdate();
		status.albumUpdate.albumId="album-test";
		status.albumUpdate.albumName="成长足迹";
		status.albumUpdate.description="<b>描述按纯文本显示</b> & 小记";
		status.albumUpdate.photoCount=3;
		status.albumUpdate.width=1200;
		status.albumUpdate.height=1600;
		return status;
	}

	private List<StatusDisplayItem> items(Status status){
		return StatusDisplayItem.buildItems(null, MastodonApp.context, status, "offline", status, Map.of(),
				StatusDisplayItem.FLAG_NO_HEADER | StatusDisplayItem.FLAG_NO_FOOTER);
	}

	private static CharSequence text(TextStatusDisplayItem item) throws Exception{
		Field field=TextStatusDisplayItem.class.getDeclaredField("text");
		field.setAccessible(true);
		return (CharSequence)field.get(item);
	}

	@Test public void supportedPostUsesDescriptionAndSingleBoundedCard() throws Exception{
		List<StatusDisplayItem> items=items(post());
		assertEquals(2, items.size());
		assertEquals("<b>描述按纯文本显示</b> & 小记", text((TextStatusDisplayItem)items.get(0)).toString());
		assertTrue(items.get(1) instanceof AlbumUpdateStatusDisplayItem);
	}

	@Test public void emptyDescriptionOmitsCompatibilityMessageForSupportedPost(){
		Status status=post(); status.albumUpdate.description="";
		List<StatusDisplayItem> items=items(status);
		assertEquals(1, items.size());
		assertEquals(StatusDisplayItem.Type.ALBUM_UPDATE, items.get(0).getType());
	}

	@Test public void missingOrInvalidPayloadKeepsOldClientText() throws Exception{
		Status status=post(); status.albumUpdate=null;
		assertEquals(FALLBACK, text((TextStatusDisplayItem)items(status).get(0)).toString());
		status.albumUpdate=post().albumUpdate; status.albumUpdate.albumId="";
		assertEquals(1, items(status).size());
		assertEquals(FALLBACK, text((TextStatusDisplayItem)items(status).get(0)).toString());
	}

	@Test public void snakeCaseAlbumMetadataDeserializesWithoutChangingStandardContent(){
		Gson gson=org.joinmastodon.android.api.MastodonAPIController.gson;
		Status status=gson.fromJson("{\"content\":\"old-channel-message\",\"album_update\":{\"album_id\":\"a\",\"album_name\":\"纪念\",\"photo_count\":2,\"width\":640,\"height\":480}}", Status.class);
		assertEquals("old-channel-message", status.content);
		assertEquals("a", status.albumUpdate.albumId);
		assertEquals(2, status.albumUpdate.photoCount);
		assertTrue(AlbumUpdateStatusDisplayItem.supported(status.albumUpdate));
	}

	@Test public void cardKeepsImageAspectFitAndThemeText(){
		for(int theme:new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}){
			try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
				Activity activity=lifecycle.get(); activity.setTheme(theme); lifecycle.setup();
				FrameLayout parent=new FrameLayout(activity);
				AlbumUpdateStatusDisplayItem.Holder holder=new AlbumUpdateStatusDisplayItem.Holder(activity, parent);
				Status status=post();
				AlbumUpdateStatusDisplayItem item=new AlbumUpdateStatusDisplayItem(status.id, new StatusDisplayItem.NoOpCallbacks(activity), activity, status, "offline");
				item.fullWidth=true;
				holder.onBind(item);
				FrameLayout wrapper=(FrameLayout)holder.itemView;
				LinearLayout card=(LinearLayout)wrapper.getChildAt(0);
				ImageView image=(ImageView)card.getChildAt(0);
				assertEquals(ImageView.ScaleType.FIT_CENTER, image.getScaleType());
				assertTrue(image.getLayoutParams().height<=V.dp(224));
				assertTrue(card.getLayoutParams().width<=V.dp(360));
				assertEquals(activity.getString(R.string.baby_albums_post_count, 3), ((TextView)card.getChildAt(1)).getText().toString());
				assertEquals(activity.getString(R.string.baby_albums_post_name, "成长足迹"), ((TextView)card.getChildAt(2)).getText().toString());
				assertEquals(activity.getString(R.string.baby_albums_brand), ((TextView)card.getChildAt(4)).getText().toString());
				BitmapDrawable drawable=new BitmapDrawable(activity.getResources(), Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888));
				holder.setImage(0, drawable); assertSame(drawable, image.getDrawable());
				holder.clearImage(0); assertNull(image.getDrawable());
				wrapper.measure(View.MeasureSpec.makeMeasureSpec(V.dp(380), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				assertTrue(wrapper.getMeasuredHeight()>image.getLayoutParams().height);
				assertTrue(image.getLayoutParams().height<=V.dp(224));
			}
		}
	}
}
