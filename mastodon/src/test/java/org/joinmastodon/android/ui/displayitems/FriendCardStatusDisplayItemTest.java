package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Filter;
import org.joinmastodon.android.model.FilterResult;
import org.joinmastodon.android.model.FriendCard;
import org.joinmastodon.android.model.FriendCardAttachment;
import org.joinmastodon.android.model.FriendRequest;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.views.FlowLayout;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
public class FriendCardStatusDisplayItemTest{
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
	}

	private Status post(){
		Status status=Status.ofFake("p_101", "<p>Compatibility body</p>", Instant.parse("2026-10-18T12:30:00Z"));
		Account account=new Account(); account.id="42"; account.username=account.acct="owner";
		account.displayName="Owner"; account.emojis=List.of(); status.account=account;
		FriendCard card=new FriendCard(); card.id="card-1"; card.schemaVersion=1; card.state="active";
		card.title="A two-line declaration for new friends"; card.gender="Public gender"; card.school="PRIVATE SCHOOL";
		card.lookingFor="People who enjoy games"; card.interests=List.of("Reading", "Music", "Travel", "Photography");
		card.games=List.of("honor-of-kings", "stardew-valley");
		card.publicFields=List.of("gender", "looking_for", "interests", "games");
		FriendCardAttachment extension=new FriendCardAttachment(); extension.schemaVersion=1; extension.id=card.id;
		extension.mode="publication"; extension.available=true; extension.title=card.title; extension.card=card;
		status.friendCard=extension; return status;
	}

	private List<StatusDisplayItem> items(Status status, int flags){
		return StatusDisplayItem.buildItems(null, MastodonApp.context, status, "offline", status, Map.of(), flags);
	}
	private List<StatusDisplayItem> body(Status status){ return items(status, StatusDisplayItem.FLAG_NO_HEADER | StatusDisplayItem.FLAG_NO_FOOTER); }

	@Test public void publicationReplacesOnlyBodyRetainsStandardHeaderFooterAndAudio(){
		Status status=post(); status.friendRequest=new FriendRequest(); // Never take the legacy early return.
		Attachment audio=new Attachment(); audio.id="audio"; audio.type=Attachment.Type.AUDIO; audio.url="https://offline.example.test/audio";
		audio.previewUrl="https://offline.example.test/audio-cover";
		status.mediaAttachments=List.of(audio);
		List<StatusDisplayItem> items=items(status, 0);
		assertEquals(List.of(StatusDisplayItem.Type.HEADER, StatusDisplayItem.Type.FRIEND_CARD, StatusDisplayItem.Type.AUDIO, StatusDisplayItem.Type.FOOTER), items.stream().map(StatusDisplayItem::getType).toList());
		assertEquals("p_101", items.get(1).parentID);
		status.friendCard.card=null;
		assertEquals(StatusDisplayItem.Type.TEXT, body(status).get(0).getType());
	}

	@Test public void cwAndFilterKeepTheCardInsideTheExistingConcealmentChain(){
		Status status=post(); status.spoilerText="Warning"; status.revealedSpoilers.clear();
		Filter filter=new Filter(); filter.title="Matched filter";
		FilterResult result=new FilterResult(); result.filter=filter; status.filtered=List.of(result);
		List<StatusDisplayItem> items=items(status, 0);
		assertEquals(3, items.size()); assertEquals(StatusDisplayItem.Type.HEADER, items.get(0).getType());
		SpoilerStatusDisplayItem filterItem=(SpoilerStatusDisplayItem)items.get(1);
		assertEquals(StatusDisplayItem.Type.FILTER_SPOILER, filterItem.getType());
		assertEquals(1, filterItem.contentItems.size());
		SpoilerStatusDisplayItem cw=(SpoilerStatusDisplayItem)filterItem.contentItems.get(0);
		assertEquals(StatusDisplayItem.Type.SPOILER, cw.getType());
		assertEquals(1, cw.contentItems.size()); assertEquals(StatusDisplayItem.Type.FRIEND_CARD, cw.contentItems.get(0).getType());
		assertEquals(StatusDisplayItem.Type.FOOTER, items.get(2).getType());
	}

	@Test public void htmlFallbackIsOnlyAnExactRawSuffixRemovedOnceWithoutTrimmingUserBody(){
		Status status=post(); FriendCardAttachment link=status.friendCard; link.mode="link";
		String suffix="<p>【扩列卡】<a href=\"https://abdl-space.top/forum/101\">Title &amp; friends</a></p>";
		link.fallbackSuffix=suffix;
		String original="<p>User text &amp; spaces </p>\n  ";
		assertEquals(original, FriendCardStatusDisplayItem.contentWithoutFallback(original+suffix, link));
		assertEquals(original+suffix, FriendCardStatusDisplayItem.contentWithoutFallback(original+suffix+suffix, link));
		assertEquals(original+suffix+" ", FriendCardStatusDisplayItem.contentWithoutFallback(original+suffix+" ", link));
		assertEquals(original+suffix+"<p>User tail</p>", FriendCardStatusDisplayItem.contentWithoutFallback(original+suffix+"<p>User tail</p>", link));
		assertEquals(original+suffix.replace("&amp;", "&"), FriendCardStatusDisplayItem.contentWithoutFallback(original+suffix.replace("&amp;", "&"), link));
		link.schemaVersion=2;
		assertEquals(original+suffix, FriendCardStatusDisplayItem.contentWithoutFallback(original+suffix, link));
	}

	@Test public void tagsRespectPublicFieldsAndResolveStableGameIdsWithoutMutatingWireData(){
		FriendCard card=post().friendCard.card;
		assertFalse(FriendCardStatusDisplayItem.about(card).contains("PRIVATE"));
		assertEquals("Public gender", FriendCardStatusDisplayItem.about(card));
		assertTrue(FriendCardStatusDisplayItem.interestTags(card).contains("王者荣耀"));
		assertTrue(FriendCardStatusDisplayItem.interestTags(card).contains("星露谷物语"));
		assertTrue(FriendCardStatusDisplayItem.interests(card).endsWith("+2"));
		assertEquals(List.of("honor-of-kings", "stardew-valley"), card.games);
		card.publicFields=List.of();
		assertTrue(FriendCardStatusDisplayItem.interestTags(card).isEmpty()); assertEquals("", FriendCardStatusDisplayItem.about(card));
	}

	@Test public void successfulCoverUsesLocalBottomGradientAndOverlayTitleThenNoCoverStaysCompact(){
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
			Activity activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
			Status status=post(); status.friendCard.card.coverUrl="https://api.abdl-space.top/api/media/generic/owned-cover";
			FriendCardStatusDisplayItem.Holder holder=new FriendCardStatusDisplayItem.Holder(activity, new FrameLayout(activity));
			holder.bind(item(activity, status, false));
			LinearLayout card=(LinearLayout)((FrameLayout)holder.itemView).getChildAt(0);
			FrameLayout coverPanel=(FrameLayout)card.getChildAt(0);
			ImageView cover=(ImageView)coverPanel.getChildAt(0);
			View shade=coverPanel.getChildAt(1);
			TextView overlayTitle=(TextView)coverPanel.getChildAt(2);
			LinearLayout details=(LinearLayout)card.getChildAt(1);
			TextView compactTitle=(TextView)details.getChildAt(0);
			assertEquals(2, compactTitle.getMaxLines()); assertEquals(2, overlayTitle.getMaxLines());
			assertEquals(View.GONE, coverPanel.getVisibility()); assertEquals(View.VISIBLE, compactTitle.getVisibility());
			measure(holder.itemView); int compactHeight=holder.itemView.getMeasuredHeight();
			holder.setImage(0, bitmap(activity, 880,400)); measure(holder.itemView);
			int withCover=holder.itemView.getMeasuredHeight();
			assertTrue(withCover>compactHeight);
			assertEquals(View.VISIBLE, coverPanel.getVisibility()); assertEquals(View.GONE,compactTitle.getVisibility());
			assertSame(coverPanel,overlayTitle.getParent()); assertEquals(status.friendCard.card.title,overlayTitle.getText().toString());
			assertEquals(Color.WHITE,overlayTitle.getCurrentTextColor());
			assertEquals(Gravity.BOTTOM,((FrameLayout.LayoutParams)overlayTitle.getLayoutParams()).gravity);
			assertEquals(Math.round(coverPanel.getMeasuredWidth()/2.2f), coverPanel.getMeasuredHeight());
			assertEquals(coverPanel.getMeasuredHeight(),cover.getMeasuredHeight());
			assertEquals(ImageView.ScaleType.MATRIX, cover.getScaleType());
			assertNull("The full image must not receive a tint or dark background",cover.getBackground());
			assertNull(cover.getColorFilter());
			assertTrue(shade.getBackground() instanceof GradientDrawable);
			GradientDrawable gradient=(GradientDrawable)shade.getBackground();
			assertEquals(GradientDrawable.Orientation.TOP_BOTTOM,gradient.getOrientation());
			assertEquals(Color.TRANSPARENT,gradient.getColors()[0]);
			assertTrue(Color.alpha(gradient.getColors()[1])>0);
			assertEquals(Math.round(coverPanel.getMeasuredHeight()*.6f),shade.getHeight());
			assertTrue("Only the lower image region is shaded",shade.getTop()>0);
			assertEquals(coverPanel.getHeight(),shade.getBottom());
			assertEquals(coverPanel.getHeight()-V.dp(12),overlayTitle.getBottom());
			assertTrue(overlayTitle.getTop()>=shade.getTop());
			FlowLayout tags=(FlowLayout)details.getChildAt(4); assertEquals(5, tags.getChildCount());
			assertEquals("+2",chipTexts(tags).get(4));
			status.friendCard.card.coverUrl=null; holder.bind(item(activity, status, false)); measure(holder.itemView);
			assertEquals(View.GONE, coverPanel.getVisibility()); assertEquals(View.VISIBLE,compactTitle.getVisibility());
			assertEquals(compactHeight,holder.itemView.getMeasuredHeight());
			status.friendCard.mode="link"; holder.bind(item(activity, status, false)); measure(holder.itemView);
			assertEquals(1, compactTitle.getMaxLines());
			for(int i=1; i<details.getChildCount(); i++) assertEquals(View.GONE, details.getChildAt(i).getVisibility());
			assertEquals(View.GONE, card.getChildAt(2).getVisibility()); assertTrue(card.isClickable());
			status.friendCard.available=false; holder.bind(item(activity, status, false));
			assertEquals(activity.getString(R.string.friend_card_post_unavailable), compactTitle.getText().toString());
			assertFalse(card.isClickable()); assertEquals(View.GONE, coverPanel.getVisibility());
		}
	}

	@Test public void failedOrClearedImageRestoresCompactTitleAndRetryCanShowCover(){
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
			Activity activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
			Status status=post(); status.friendCard.card.coverUrl="https://api.abdl-space.top/owned-cover";
			FriendCardStatusDisplayItem.Holder holder=new FriendCardStatusDisplayItem.Holder(activity,new FrameLayout(activity));
			holder.bind(item(activity,status,false));
			LinearLayout card=(LinearLayout)((FrameLayout)holder.itemView).getChildAt(0);
			FrameLayout panel=(FrameLayout)card.getChildAt(0);
			TextView title=(TextView)((LinearLayout)card.getChildAt(1)).getChildAt(0);
			measure(holder.itemView); int compactHeight=holder.itemView.getMeasuredHeight();
			holder.onImageLoadingFailed(0,new java.io.IOException("offline")); measure(holder.itemView);
			assertEquals(compactHeight,holder.itemView.getMeasuredHeight()); assertEquals(View.GONE,panel.getVisibility());
			holder.setImage(0,bitmap(activity,880,400)); measure(holder.itemView);
			assertEquals(View.VISIBLE,panel.getVisibility()); assertEquals(View.GONE,title.getVisibility());
			holder.onImageLoadingFailed(0,new java.io.IOException("retry failed")); measure(holder.itemView);
			assertEquals(compactHeight,holder.itemView.getMeasuredHeight()); assertEquals(View.VISIBLE,title.getVisibility());
			assertEquals(View.GONE,panel.getVisibility()); assertNull(((ImageView)panel.getChildAt(0)).getDrawable());
			holder.setImage(0,bitmap(activity,880,400)); measure(holder.itemView);
			assertEquals(View.VISIBLE,panel.getVisibility());
			holder.clearImage(0); measure(holder.itemView); assertEquals(compactHeight,holder.itemView.getMeasuredHeight());
			holder.setImage(0,null); assertEquals(View.GONE,panel.getVisibility());
		}
	}

	@Test public void actualBitmapMatrixHonorsBothAxesOfNormalizedFocus(){
		Activity activity=Robolectric.buildActivity(Activity.class).get();
		FriendCardStatusDisplayItem.FocusCoverView view=new FriendCardStatusDisplayItem.FocusCoverView(activity);
		view.layout(0,0,220,100);
		view.setImageDrawable(new BitmapDrawable(activity.getResources(), Bitmap.createBitmap(400,100, Bitmap.Config.ARGB_8888)));
		float[] values=new float[9];
		view.setFocus(0,.4f); view.getImageMatrix().getValues(values); assertEquals(0, values[Matrix.MTRANS_X], .01f);
		view.setFocus(1,.4f); view.getImageMatrix().getValues(values); assertEquals(-180, values[Matrix.MTRANS_X], .01f);
		view.setImageDrawable(new BitmapDrawable(activity.getResources(), Bitmap.createBitmap(220,400, Bitmap.Config.ARGB_8888)));
		view.setFocus(.5f,0); view.getImageMatrix().getValues(values); assertEquals(0, values[Matrix.MTRANS_Y], .01f);
		view.setFocus(.5f,1); view.getImageMatrix().getValues(values); assertEquals(-300, values[Matrix.MTRANS_Y], .01f);
	}

	@Test public void aboutChipsAreFourSelectedFieldsPlusOverflowAndExcludeSchoolAndHabitsEvenIfPublic(){
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
			Activity activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
			Status status=post(); FriendCard data=status.friendCard.card;
			data.gender="Gender"; data.age="Age"; data.region="Region"; data.mbti="MBTI"; data.zodiac="Zodiac";
			data.school="PRIVATE SCHOOL"; data.interactionHabits="PRIVATE HABITS";
			data.publicFields=List.of("gender","age","region","mbti","zodiac","school","interaction_habits","looking_for","games");
			FriendCardStatusDisplayItem.Holder holder=new FriendCardStatusDisplayItem.Holder(activity,new FrameLayout(activity));
			holder.bind(item(activity,status,false));
			LinearLayout card=(LinearLayout)((FrameLayout)holder.itemView).getChildAt(0);
			LinearLayout details=(LinearLayout)card.getChildAt(1);
			FlowLayout about=(FlowLayout)details.getChildAt(2);
			assertEquals(List.of("Gender","Age","Region","MBTI","Zodiac"),FriendCardStatusDisplayItem.aboutTags(data));
			assertEquals(List.of("Gender","Age","Region","MBTI","+1"),chipTexts(about));
			assertEquals(activity.getString(R.string.friend_card_post_about),((TextView)details.getChildAt(1)).getText().toString());
			assertEquals(2,((TextView)details.getChildAt(5)).getMaxLines());
			assertTrue(allText(holder.itemView).contains(data.lookingFor));
			assertFalse(allText(holder.itemView).contains("PRIVATE SCHOOL"));
			assertFalse(allText(holder.itemView).contains("PRIVATE HABITS"));
			data.publicFields=List.of("school","interaction_habits"); holder.bind(item(activity,status,false));
			assertEquals(View.GONE,details.getChildAt(1).getVisibility()); assertEquals(View.GONE,about.getVisibility());
			assertTrue(FriendCardStatusDisplayItem.aboutTags(data).isEmpty());
			assertFalse(allText(holder.itemView).contains("PRIVATE SCHOOL")); assertFalse(allText(holder.itemView).contains("PRIVATE HABITS"));
		}
	}

	@Test public void floatRoundedScaleNeverProducesPositiveTranslationOnEitherAxis(){
		Activity activity=Robolectric.buildActivity(Activity.class).get();
		FriendCardStatusDisplayItem.FocusCoverView view=new FriendCardStatusDisplayItem.FocusCoverView(activity);
		float[] values=new float[9];
		for(int[] size:new int[][]{{100,45,4000,45},{220,100,400,100},{213,97,511,233},{301,137,997,457}}){
			view.layout(0,0,size[0],size[1]);
			view.setImageDrawable(bitmap(activity,size[2],size[3]));
			for(float focus:new float[]{0,.4f,.5f,1}){
				view.setFocus(focus,focus); view.getImageMatrix().getValues(values);
				assertTrue("X must never shift right from floating-point underfill",values[Matrix.MTRANS_X]<=0f);
				assertTrue("Y must never shift down from floating-point underfill",values[Matrix.MTRANS_Y]<=0f);
			}
		}
	}

	@Test public void unavailableOrForceHiddenCoverHasNoRequestAndRejectsLateImage(){
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class)){
			Activity activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
			Status status=post(); status.friendCard.card.coverUrl="https://api.abdl-space.top/owned-cover";
			FriendCardStatusDisplayItem.Holder holder=new FriendCardStatusDisplayItem.Holder(activity, new FrameLayout(activity));
			for(int mode=0; mode<3; mode++){
				status.friendCard.available=mode!=1; status.sensitive=mode==2;
				FriendCardStatusDisplayItem item=item(activity, status, mode==0);
				assertEquals(0, item.getImageCount()); assertNull(item.getImageRequest(0)); holder.bind(item);
				FrameLayout panel=(FrameLayout)((LinearLayout)((FrameLayout)holder.itemView).getChildAt(0)).getChildAt(0);
				ImageView cover=(ImageView)panel.getChildAt(0);
				holder.setImage(0,new BitmapDrawable(activity.getResources(), Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888)));
				assertNull(cover.getDrawable()); assertEquals(View.GONE, panel.getVisibility());
			}
		}
	}

	private static BitmapDrawable bitmap(Activity activity,int width,int height){
		return new BitmapDrawable(activity.getResources(),Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888));
	}
	private static List<String> chipTexts(FlowLayout chips){
		List<String> values=new java.util.ArrayList<>();
		for(int i=0;i<chips.getChildCount();i++) values.add(((TextView)((FrameLayout)chips.getChildAt(i)).getChildAt(0)).getText().toString());
		return values;
	}
	private static String allText(View view){
		StringBuilder value=new StringBuilder();
		if(view instanceof TextView text) value.append(text.getText());
		if(view instanceof ViewGroup group)
			for(int i=0;i<group.getChildCount();i++) value.append(allText(group.getChildAt(i)));
		return value.toString();
	}

	private FriendCardStatusDisplayItem item(Activity activity, Status status, boolean hidden){
		return new FriendCardStatusDisplayItem(status.id, new StatusDisplayItem.NoOpCallbacks(activity), activity, status, "offline", hidden);
	}
	private void measure(View view){
		view.measure(View.MeasureSpec.makeMeasureSpec(V.dp(380), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
		view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());
	}
}
