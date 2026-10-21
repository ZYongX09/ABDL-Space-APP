package org.joinmastodon.android.friendcards;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Advertisement;
import org.joinmastodon.android.model.Emoji;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.sponsors.SponsorContrast;
import org.joinmastodon.android.ui.displayitems.CheckableHeaderStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.CompactHeaderStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.HeaderStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.text.BadgeSpan;
import org.joinmastodon.android.ui.text.CustomEmojiSpan;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import me.grishka.appkit.utils.V;

/** Real native Views/Canvas, with no HTTP, Compose painter, fixture images or generated UI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="w393dp-h852dp-mdpi", application=CompatibilityTestApplication.class,
		shadows=PublicFriendCardUiTest.NoNetwork.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PublicFriendCardUiTest{
	static final String CARD="f1d2e3a4-b5c6-4789-8abc-0123456789ab";
	private GlobalUserPreferences.ThemePreference previousTheme;
	private boolean previousEmoji;

	@Implements(MastodonAPIRequest.class) public static class NoNetwork{
		@Implementation public MastodonAPIRequest<?> exec(String account){
			throw new AssertionError("Header/mini binding must not make HTTP requests");
		}
	}

	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		previousTheme=GlobalUserPreferences.theme; previousEmoji=GlobalUserPreferences.customEmojiInNames;
		GlobalUserPreferences.customEmojiInNames=true;
	}
	@After public void cleanup(){ GlobalUserPreferences.theme=previousTheme; GlobalUserPreferences.customEmojiInNames=previousEmoji; }

	static Account user(){
		Account user=new Account(); user.id="42"; user.username=user.acct="little-deer";
		user.displayName="小鹿 :spark: 🌙 "+"一起分享温柔有趣的日常 ".repeat(12);
		user.emojis=List.of(new Emoji("spark", "https://offline.example.test/never-load", "https://offline.example.test/never-load"));
		user.publicFriendCard=new Account.PublicFriendCard(); user.publicFriendCard.schemaVersion=1; user.publicFriendCard.id=CARD;
		user.badge=new Account.Badge(); user.badge.name="温柔的小伙伴"; user.badge.color="#C4DDEB";
		return user;
	}
	static Status post(Account user){
		Status status=Status.ofFake("p_101", "<p>日常里的小确幸</p>", Instant.parse("2026-10-18T12:30:00Z"));
		status.account=user; return status;
	}

	@Test public void onlySupportedNativePublicReferencesAreEligible(){
		Account user=user(); Status status=post(user);
		assertTrue(PublicFriendCardUi.hasReference(user)); assertTrue(PublicFriendCardUi.hasHeaderReference(user,status));
		assertFalse(PublicFriendCardUi.hasReference(null));
		assertFalse(PublicFriendCardUi.hasHeaderReference(user,null));
		status.account=null; assertFalse(PublicFriendCardUi.hasHeaderReference(user,status)); status.account=user;
		status.advertisement=new Advertisement(); assertFalse(PublicFriendCardUi.hasHeaderReference(user,status)); status.advertisement=null;
		status.isRemote=true; assertFalse(PublicFriendCardUi.hasHeaderReference(user,status)); status.isRemote=false;
		for(String id:List.of("", "admin", "ad_1", "nbw_42", "-1", "unknown", "42@example.test")){
			user.id=id; assertFalse(id,PublicFriendCardUi.hasReference(user));
		}
		user.id="42"; user.suspended=true; assertFalse(PublicFriendCardUi.hasReference(user)); user.suspended=false;
		user.publicFriendCard.schemaVersion=2; assertFalse(PublicFriendCardUi.hasReference(user)); user.publicFriendCard.schemaVersion=1;
		for(String id:List.of("", "not-a-card", "../private", "https://offline.example.test/card")){
			user.publicFriendCard.id=id; assertFalse(id,PublicFriendCardUi.hasReference(user));
		}
		user.publicFriendCard.id=null; assertFalse(PublicFriendCardUi.hasReference(user));
		user.publicFriendCard=null; assertFalse(PublicFriendCardUi.hasReference(user)); // old server, paused, deleted or unavailable
	}

	@Test public void babyBlueUsesExactPaletteUnlessSameSponsorContrastCorrectionIsNecessary(){
		assertEquals(0xFF4E7394,PublicFriendCardUi.markerColor(false,Color.WHITE));
		assertEquals(0xFFB9D9F0,PublicFriendCardUi.markerColor(true,0xFF121212));
		for(int surface:new int[]{0xFF4E7394,0xFFB9D9F0,0xFF808080}) for(boolean dark:new boolean[]{false,true}){
			int expected=SponsorContrast.ensure(dark ? 0xFFB9D9F0 : 0xFF4E7394,surface);
			assertEquals(expected,PublicFriendCardUi.markerColor(dark,surface));
			assertTrue(SponsorContrast.ratio(expected,surface)>=4.5);
		}
	}

	@Test public void standardCompactAndCheckableRebindRetainEmojiSponsorAndSeparateTextBadge() throws Exception{
		for(boolean dark:new boolean[]{false,true}) try(ActivityController<Activity> lifecycle=host(dark)){
			Activity activity=lifecycle.get();
			for(int type=0;type<3;type++){
				LinearLayout root=root(activity);
				StatusDisplayItem.Holder<?> holder=holder(type,activity,root);
				root.addView(holder.itemView);
				Account user=user(); Status status=post(user);
				TextView name=holder.itemView.findViewById(R.id.name), badge=holder.itemView.findViewById(R.id.header_name_badge);
				int defaultUsernameColor=name.getCurrentTextColor();
				user.sponsor=new Account.PublicSponsor(); user.sponsor.active=user.sponsor.permanent=true;
				user.sponsor.colorLight="#783B65"; user.sponsor.colorDark="#EAC2DE";
				bind(type,holder,activity,status); measure(root);
				ImageView marker=holder.itemView.findViewById(R.id.header_friend_card_marker);
				assertEquals(View.VISIBLE,marker.getVisibility()); assertNotNull(marker.getDrawable());
				assertEquals(V.dp(16),marker.getWidth()); assertEquals(V.dp(16),marker.getHeight());
				assertEquals(activity.getString(R.string.friend_card_public_marker_description),marker.getContentDescription());
				assertFalse(marker.isClickable()); assertFalse(marker.isFocusable()); assertNull(marker.getTag());
				assertEquals(PublicFriendCardUi.markerColor(dark,UiUtils.getThemeColor(activity,R.attr.colorM3Surface)),marker.getImageTintList().getDefaultColor());
				assertTrue(name.getText() instanceof Spanned);
				CustomEmojiSpan[] spans=((Spanned)name.getText()).getSpans(0,name.length(),CustomEmojiSpan.class);
				assertEquals("Badge append must never stringify/flatten emoji spans",1,spans.length);
				assertTrue(badge.getText() instanceof Spanned);
				assertEquals(1,((Spanned)badge.getText()).getSpans(0,badge.length(),BadgeSpan.class).length);
				assertEquals(user.badge.name,badge.getContentDescription());
				assertOrder(name,marker,badge);
				assertTrue("The long name is ellipsized, not the marker",name.getLayout().getEllipsisCount(0)>0);
				assertMarkerInk(marker);
				int expectedUsername=type==1 ? defaultUsernameColor : SponsorContrast.ensure(dark ? 0xFFEAC2DE : 0xFF783B65,UiUtils.getThemeColor(activity,R.attr.colorM3Surface));
				assertEquals("Friend-card tint must not replace sponsor username color",expectedUsername,name.getCurrentTextColor());
				capture(root,(dark ? "dark-" : "light-")+type+"-active-emoji-long-name");

				user.badge.name="特别长的现有文字徽章用于确认不会挤走标志或溢出菜单";
				bind(type,holder,activity,status); measure(root); assertOrder(name,marker,badge);
				assertTrue(badge.getWidth()<=V.dp(96));
				capture(root,(dark ? "dark-" : "light-")+type+"-long-badge");

				marker.setTag("stale-card"); marker.setOnClickListener(v->{ throw new AssertionError("stale click"); });
				user.publicFriendCard=null; user.badge=null; user.sponsor=null;
				bind(type,holder,activity,status); measure(root);
				assertEquals(View.GONE,marker.getVisibility()); assertNull(marker.getDrawable()); assertNull(marker.getImageTintList());
				assertNull(marker.getTag()); assertNull(marker.getContentDescription()); assertFalse(marker.hasOnClickListeners());
				assertFalse(marker.isClickable()); assertFalse(marker.isFocusable());
				assertEquals(View.GONE,badge.getVisibility()); assertEquals("",badge.getText().toString());
				assertEquals(defaultUsernameColor,name.getCurrentTextColor());
				capture(root,(dark ? "dark-" : "light-")+type+"-paused-empty-rebind");

				Account other=user(); other.id="43"; other.displayName="另一位朋友"; other.emojis=List.of(); other.badge=null;
				bind(type,holder,activity,post(other)); measure(root);
				assertEquals(View.VISIBLE,marker.getVisibility()); assertEquals("另一位朋友",name.getText().toString());
				assertEquals(View.GONE,badge.getVisibility()); assertNull(marker.getTag());
			}
		}
	}

	@Test public void actualProfileLayoutMiniStartsGoneAndClearsPausedEditedUnknownAndReboundState() throws Exception{
		for(boolean dark:new boolean[]{false,true}) try(ActivityController<Activity> lifecycle=host(dark)){
			Activity activity=lifecycle.get(); View profile=LayoutInflater.from(activity).inflate(R.layout.fragment_profile,null,false);
			View mini=profile.findViewById(R.id.profile_friend_card_mini);
			assertEquals(View.GONE,mini.getVisibility());
			ViewGroup parent=(ViewGroup)mini.getParent();
			assertTrue(parent.indexOfChild(profile.findViewById(R.id.bio))<parent.indexOfChild(mini));
			assertTrue(parent.indexOfChild(mini)<parent.indexOfChild(profile.findViewById(R.id.badge_container)));
			LinearLayout root=root(activity);
			for(int id:new int[]{R.id.profile_header,R.id.bio,R.id.profile_friend_card_mini}){
				View child=profile.findViewById(id); ((ViewGroup)child.getParent()).removeView(child); root.addView(child);
			}
			((TextView)root.findViewById(R.id.name)).setText("小鹿 🌙");
			((TextView)root.findViewById(R.id.username)).setText("little-deer");
			((TextView)root.findViewById(R.id.bio)).setText("一起分享平凡日常里的小确幸，也欢迎认识温柔有趣的新朋友。");
			AtomicInteger clicks=new AtomicInteger(); Account user=user();
			PublicFriendCardUi.bindMini(mini,user,false,v->clicks.incrementAndGet()); measure(root);
			assertEquals(View.VISIBLE,mini.getVisibility()); assertTrue(mini.performClick()); assertEquals(1,clicks.get());
			assertTrue(mini.isFocusable()); assertTrue(mini.getHeight()>=V.dp(40));
			ImageView icon=mini.findViewById(R.id.profile_friend_card_icon); assertMarkerInk(icon);
			assertEquals("查看扩列卡",mini.getContentDescription());
			capture(root,(dark ? "dark-" : "light-")+"profile-mini-active");
			for(String state:List.of("paused","edited","missing","malformed")){
				Account current=user(); if(state.equals("paused") || state.equals("missing")) current.publicFriendCard=null;
				if(state.equals("malformed")) current.publicFriendCard.schemaVersion=2;
				mini.setTag(CARD);
				PublicFriendCardUi.bindMini(mini,current,state.equals("edited"),v->clicks.incrementAndGet()); measure(root);
				assertEquals(state,View.GONE,mini.getVisibility()); assertFalse(mini.isEnabled());
				assertFalse(mini.isClickable()); assertFalse(mini.isFocusable()); assertFalse(mini.hasOnClickListeners());
				assertNull(mini.getTag()); assertNull(mini.getContentDescription()); assertNull(icon.getDrawable()); assertNull(icon.getImageTintList());
				mini.performClick(); assertEquals(1,clicks.get());
				capture(root,(dark ? "dark-" : "light-")+"profile-mini-"+state);
			}
			PublicFriendCardUi.bindMini(mini,user(),false,v->clicks.addAndGet(10));
			assertTrue(mini.performClick()); assertEquals(11,clicks.get());
		}
	}

	private ActivityController<Activity> host(boolean dark){
		GlobalUserPreferences.theme=dark ? GlobalUserPreferences.ThemePreference.DARK : GlobalUserPreferences.ThemePreference.LIGHT;
		ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class);
		lifecycle.get().setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light); lifecycle.setup(); return lifecycle;
	}
	private static LinearLayout root(Activity activity){
		LinearLayout root=new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(UiUtils.getThemeColor(activity,R.attr.colorM3Surface)); return root;
	}
	private static StatusDisplayItem.Holder<?> holder(int type,Activity activity,ViewGroup parent){
		return switch(type){ case 1 -> new CompactHeaderStatusDisplayItem.Holder(activity,parent); case 2 -> new CheckableHeaderStatusDisplayItem.Holder(activity,parent); default -> new HeaderStatusDisplayItem.Holder(activity,parent); };
	}
	private static void bind(int type,StatusDisplayItem.Holder<?> holder,Activity activity,Status status){
		var callbacks=new StatusDisplayItem.NoOpCallbacks(activity);
		if(type==1) ((CompactHeaderStatusDisplayItem.Holder)holder).bind(new CompactHeaderStatusDisplayItem(status.id,status.account,status.createdAt,callbacks,activity,"offline",status));
		else ((HeaderStatusDisplayItem.Holder)holder).bind(type==2 ? new CheckableHeaderStatusDisplayItem(status.id,status.account,status.createdAt,callbacks,activity,"offline",status,null) : new HeaderStatusDisplayItem(status.id,status.account,status.createdAt,callbacks,activity,"offline",status,null));
	}
	private static void measure(View root){
		root.measure(View.MeasureSpec.makeMeasureSpec(V.dp(393),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
		root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
	}
	private static void assertOrder(TextView name,ImageView marker,TextView badge){
		assertSame(name.getParent(),marker.getParent()); assertSame(marker.getParent(),badge.getParent());
		assertTrue(name.getRight()<=marker.getLeft()); assertTrue(marker.getRight()<=badge.getLeft());
		assertTrue(badge.getRight()<=((View)badge.getParent()).getWidth()); assertTrue(name.getWidth()>0);
	}
	private static void assertMarkerInk(ImageView icon){
		Bitmap bitmap=Bitmap.createBitmap(icon.getWidth(),icon.getHeight(),Bitmap.Config.ARGB_8888); icon.draw(new Canvas(bitmap));
		int pixels=0; for(int y=0;y<bitmap.getHeight();y++) for(int x=0;x<bitmap.getWidth();x++) if(Color.alpha(bitmap.getPixel(x,y))>32) pixels++;
		assertTrue("Native resource icon cannot be a blank vector",pixels>20); bitmap.recycle();
	}
	private static void capture(View root,String name) throws Exception{
		Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
		int first=bitmap.getPixel(0,0),ink=0; for(int y=0;y<bitmap.getHeight();y++) for(int x=0;x<bitmap.getWidth();x++) if(bitmap.getPixel(x,y)!=first) ink++;
		assertTrue("Capture cannot be a blank surface",ink>50);
		File directory=new File(System.getProperty("publicFriendCardRenderDir","/tmp/abdl-friend-card-ui-renders/public-references"));
		assertTrue(directory.isDirectory() || directory.mkdirs());
		try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)); }
		bitmap.recycle();
	}
}
