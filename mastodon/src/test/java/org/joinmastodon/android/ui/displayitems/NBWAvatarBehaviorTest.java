package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.viewmodel.AccountViewModel;
import org.joinmastodon.android.ui.text.AvatarSpan;
import org.joinmastodon.android.ui.utils.NBWAvatarImageDownloader;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.ImageLoaderCallback;
import me.grishka.appkit.imageloader.processing.ImageProcessingStep;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NBWAvatarBehaviorTest{
	@Before public void setup(){ MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context); }
	private Account user(String id,String avatar,String still) throws Exception{
		Account user=new Account(); user.id=id; user.username=user.acct=user.displayName="Author";
		user.url="https://www.newbabyworld.top/?4";
		user.avatar=avatar; user.avatarStatic=still; user.postprocess(); return user;
	}

	@Test public void nullAndBlankUseNodpiOriginalWhileRealAvatarsAndLocalAccountsArePreserved() throws Exception{
		for(String missing:new String[]{null,""," \n\t"}){
			Account remote=user("nbw_123",missing,missing);
			assertEquals(Account.NBW_DEFAULT_AVATAR,remote.avatar); assertEquals(remote.avatar,remote.avatarStatic);
			remote.postprocess(); assertEquals(Account.NBW_DEFAULT_AVATAR,remote.avatar);
		}
		for(String real:List.of("https://nbw.example/avatar.png","https://nbw.example/uc_server/avatar.php?uid=123&size=middle")){
			Account animated=user("nbw_123",real,""); assertEquals(real,animated.avatar); assertEquals(real,animated.avatarStatic);
			Account still=user("nbw_123",null,real); assertEquals(real,still.avatar); assertEquals(real,still.avatarStatic);
		}
		Account local=user("42",null,null); local.nbwUsername="linked"; local.postprocess(); assertEquals("",local.avatar); assertNull(local.avatarStatic);
		assertEquals("",user("nbw_quote_123",null,null).avatar);
		assertFalse(Account.isNBWAccountID("nbw_0001")); assertTrue(Account.isNBWAccountID("nbw_9223372036854775807"));
		assertFalse(Account.isNBWAccountID("nbw_9223372036854775808"));
		Bitmap original;
		try(InputStream input=MastodonApp.context.getResources().openRawResource(R.drawable.nbw_default_avatar)){
			BitmapFactory.Options options=new BitmapFactory.Options(); options.inPreferredConfig=Bitmap.Config.ARGB_8888; options.inScaled=false;
			original=BitmapFactory.decodeStream(input,null,options);
		}
		assertNotNull(original); assertEquals(1254,original.getWidth()); assertEquals(1254,original.getHeight());
		// AAPT may losslessly recompress PNG bytes. Golden derived from the user's source with PIL:
		// SHA256 of row-major bytes (0xFF,R,G,B) from Image.open(nbw_headimg.png).convert("RGBA").
		// The unchanged drawable-nodpi source file separately retains SHA256 d54a0475...193a3b5.
		assertEquals("a0ba8c91cae610657ffb50f4e86d805bd1b6aefe32b70fc9f12dfa4dd2588bff",pixelHash(original));
		original.recycle();
	}

	@Test public void registeredImageCacheReallyDecodesBundledAvatarForHeaderListReplyAndProfileRequests() throws Exception{
		Account author=user("nbw_123",null,""); Status reply=Status.ofFake("nbw_reply_9","",Instant.now()); reply.account=author; reply.inReplyToId="nbw_8";
		HeaderStatusDisplayItem header=new HeaderStatusDisplayItem(reply.id,author,reply.createdAt,new StatusDisplayItem.NoOpCallbacks(MastodonApp.context),MastodonApp.context,"selected",reply,null);
		CompactHeaderStatusDisplayItem compact=new CompactHeaderStatusDisplayItem(reply.id,author,reply.createdAt,new StatusDisplayItem.NoOpCallbacks(MastodonApp.context),MastodonApp.context,"selected",reply);
		boolean gifs=GlobalUserPreferences.playGifs;
		try{
			ImageCache cache=ImageCache.getInstance(MastodonApp.context);
			ImageCache.registerDownloader(new NBWAvatarImageDownloader(cache,MastodonApp.context));
			// Robolectric's native Skia cannot allocate HARDWARE; establish its actual copy result independently.
			Bitmap probe=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
			Bitmap hardwareProbe=probe.copy(Bitmap.Config.HARDWARE,false);
			Bitmap.Config hardwareResult=hardwareProbe.getConfig();
			assertEquals(Bitmap.Config.ARGB_8888,hardwareResult);
			hardwareProbe.recycle(); probe.recycle();
			for(boolean animate:new boolean[]{false,true}){
				GlobalUserPreferences.playGifs=animate;
				AccountViewModel list=new AccountViewModel(author,"selected",false,MastodonApp.context);
				UrlImageLoaderRequest profile=new UrlImageLoaderRequest(animate ? author.avatar : author.avatarStatic,100,100);
				for(ImageLoaderRequest request:List.of(header.getImageRequest(0),compact.getImageRequest(0),list.avaRequest,new AvatarSpan(author).createImageLoaderRequest(),profile)){
					assertEquals(Account.NBW_DEFAULT_AVATAR,((UrlImageLoaderRequest)request).uri.toString());
					Drawable loaded=load(cache,request);
					assertTrue(loaded instanceof BitmapDrawable);
					Bitmap bitmap=((BitmapDrawable)loaded).getBitmap();
					assertEquals(request.desiredConfig==Bitmap.Config.HARDWARE ? hardwareResult : request.desiredConfig,bitmap.getConfig());
					int size=Math.min(request.desiredMaxWidth,request.desiredMaxHeight);
					assertEquals(size,bitmap.getWidth()); assertEquals(size,bitmap.getHeight());
				}
			}
			// Also verify raw pixels through the registered cache, without URI/provider shadows or cache seeding.
			Bitmap full=((BitmapDrawable)load(cache,new UrlImageLoaderRequest(Bitmap.Config.ARGB_8888,0,0,List.of(),Uri.parse(Account.NBW_DEFAULT_AVATAR)))).getBitmap();
			assertEquals(1254,full.getWidth()); assertEquals(1254,full.getHeight());
			assertEquals("a0ba8c91cae610657ffb50f4e86d805bd1b6aefe32b70fc9f12dfa4dd2588bff",pixelHash(full));
			List<Integer> order=new java.util.ArrayList<>();
			ImageProcessingStep first=new ImageProcessingStep(){
				@Override public Drawable processDrawable(Drawable input){
					Bitmap bitmap=((BitmapDrawable)input).getBitmap();
					assertEquals(128,bitmap.getWidth()); assertEquals(128,bitmap.getHeight()); assertEquals(Bitmap.Config.ARGB_8888,bitmap.getConfig());
					order.add(1); return input;
				}
				@Override public String getMemoryCacheKey(){ return "nbw-step-1"; }
			};
			ImageProcessingStep second=new ImageProcessingStep(){
				@Override public Drawable processDrawable(Drawable input){ assertEquals(List.of(1),order); order.add(2); return input; }
				@Override public String getMemoryCacheKey(){ return "nbw-step-2"; }
			};
			load(cache,new UrlImageLoaderRequest(Bitmap.Config.ARGB_8888,128,192,List.of(first,second),Uri.parse(Account.NBW_DEFAULT_AVATAR)));
			assertEquals(List.of(1,2),order);
			NBWAvatarImageDownloader downloader=new NBWAvatarImageDownloader(cache,MastodonApp.context);
			assertFalse(downloader.canHandleRequest(new UrlImageLoaderRequest(Account.NBW_DEFAULT_AVATAR+"?other=1")));
			assertFalse(downloader.canHandleRequest(new UrlImageLoaderRequest("https://nbw.example/avatar.png")));
		}finally{ GlobalUserPreferences.playGifs=gifs; }
	}

	private static Drawable load(ImageCache cache,ImageLoaderRequest request) throws Exception{
		AtomicReference<Drawable> loaded=new AtomicReference<>(); AtomicReference<Throwable> failure=new AtomicReference<>();
		cache.get(request,null,new ImageLoaderCallback(){
			@Override public void onImageLoaded(ImageLoaderRequest req,Drawable image){ loaded.set(image); }
			@Override public void onImageLoadingFailed(ImageLoaderRequest req,Throwable error){ failure.set(error); }
		},true);
		long end=System.nanoTime()+5_000_000_000L;
		while(loaded.get()==null && failure.get()==null && System.nanoTime()<end){ ShadowLooper.idleMainLooper(); Thread.sleep(10); }
		if(failure.get()!=null) throw new AssertionError("Registered NBW ImageCache decode failed for "+request,failure.get());
		assertNotNull("Registered ImageCache must really decode the bundled NBW avatar: "+request,loaded.get());
		return loaded.get();
	}

	private static String pixelHash(Bitmap bitmap) throws Exception{
		MessageDigest digest=MessageDigest.getInstance("SHA-256"); int[] row=new int[bitmap.getWidth()]; byte[] bytes=new byte[row.length*4];
		for(int y=0;y<bitmap.getHeight();y++){
			bitmap.getPixels(row,0,row.length,0,y,row.length,1);
			for(int x=0;x<row.length;x++){
				int argb=row[x],offset=x*4;
				bytes[offset]=(byte)(argb>>>24); bytes[offset+1]=(byte)(argb>>>16); bytes[offset+2]=(byte)(argb>>>8); bytes[offset+3]=(byte)argb;
			}
			digest.update(bytes);
		}
		StringBuilder hash=new StringBuilder(); for(byte b:digest.digest()) hash.append(String.format("%02x",b&255)); return hash.toString();
	}
}
