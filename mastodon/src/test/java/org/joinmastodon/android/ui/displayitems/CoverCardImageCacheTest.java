package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.LinearLayoutManager;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.FriendCard;
import org.joinmastodon.android.model.FriendCardAttachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.albums.AlbumModels.AlbumUpdate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.ImageLoaderRecyclerAdapter;
import me.grishka.appkit.imageloader.ImageLoaderViewHolder;
import me.grishka.appkit.imageloader.ListImageLoader;
import me.grishka.appkit.imageloader.ListImageLoaderWrapper;
import me.grishka.appkit.imageloader.downloaders.ImageDownloader;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.BindableViewHolder;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.UsableRecyclerView;

/** Real AppKit memory cache and loader; every image is synthetic and no network is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
public class CoverCardImageCacheTest{
	private enum Kind{ ALBUM, PUBLICATION, LINK }
	private ActivityController<Activity> lifecycle;
	private Activity activity;
	private ImageCache cache;

	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
		lifecycle=Robolectric.buildActivity(Activity.class);
		activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
		cache=ImageCache.getInstance(activity); cache.clearTopLevel();
		ImageCache.registerDownloader(0, new ImageDownloader(){
			@Override public boolean needsDiskCache(){ return false; }
			@Override public boolean canHandleRequest(ImageLoaderRequest request){
				return request instanceof UrlImageLoaderRequest url && "offline.example.test".equals(url.uri.getHost());
			}
			@Override public Drawable getDrawable(ImageLoaderRequest request, boolean decode, ImageCache.ImageDownloadInfo info) throws IOException{
				throw new IOException("Unconfigured synthetic cover: tests must never use HTTP");
			}
		});
	}

	@After public void cleanup(){ cache.clearTopLevel(); lifecycle.close(); }

	@Test public void realTopCacheRestoresDuringBindWithoutWaitingForAnActiveWrapper(){
		for(Kind kind:Kind.values()){
			Status status=post(kind, url());
			StatusDisplayItem model=model(kind, status, "account-a", status.id, false, new RetryCallbacks());
			StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind);
			BitmapDrawable image=bitmap();
			cache.put(model.getImageRequest(0), image);
			holder.bind(model);
			assertSame(kind.name(), image, imageView(kind, holder).getDrawable());
			assertVisible(kind, holder);
			assertNull(model.getImageRequest(1));
			cache.clearTopLevel();
		}
	}

	@Test public void cwRevealOnExistingModelRequestsAndRestoresOnlyAfterReveal(){
		for(Kind kind:Kind.values()) for(String warning:List.of("Hidden content warning", " ")){
			Status status=post(kind, url()); RetryCallbacks callbacks=new RetryCallbacks();
			StatusDisplayItem publicModel=model(kind, status, "account-a", status.id, false, callbacks);
			BitmapDrawable image=bitmap(); cache.put(publicModel.getImageRequest(0), image);
			status.spoilerText=warning;
			StatusDisplayItem cw=model(kind, status, "account-a", status.id, false, callbacks);
			StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind);
			assertEquals(0, cw.getImageCount()); assertNull(cw.getImageRequest(0)); holder.bind(cw); assertEmpty(kind, holder);
			// Production expands SpoilerStatusDisplayItem.contentItems; it does not rebuild the model.
			status.revealedSpoilers.add(Status.SpoilerType.CONTENT_WARNING);
			assertEquals(1, cw.getImageCount()); assertNotNull(cw.getImageRequest(0)); holder.bind(cw);
			assertSame(image, imageView(kind, holder).getDrawable());
			status.revealedSpoilers.remove(Status.SpoilerType.CONTENT_WARNING); holder.bind(cw);
			assertEquals(0, cw.getImageCount()); assertEmpty(kind, holder);
			((ImageLoaderViewHolder)holder).setImage(0, image); assertEmpty(kind, holder);
			cache.clearTopLevel();
		}
	}

	@Test public void sameBindingAndRebindRetainOnlyDisplayedLiveBitmapWhileExplicitClearWins(){
		for(Kind kind:Kind.values()){
			Status status=post(kind, url()); RetryCallbacks callbacks=new RetryCallbacks();
			StatusDisplayItem first=model(kind, status, "account-a", status.id, false, callbacks);
			StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind);
			ImageLoaderViewHolder images=(ImageLoaderViewHolder)holder;
			BitmapDrawable image=bitmap(); cache.put(first.getImageRequest(0), image); holder.bind(first);
			cache.remove(first.getImageRequest(0));
			holder.bind(model(kind, status, "account-a", status.id, false, callbacks));
			assertSame(image, imageView(kind, holder).getDrawable());
			assertSame("The following wrapper bind must see the same top-cache image", image, cache.getFromTop(first.getImageRequest(0)));
			cache.remove(first.getImageRequest(0)); holder.rebind();
			assertSame(image, imageView(kind, holder).getDrawable());
			images.clearImage(0); assertEmpty(kind, holder);
			cache.remove(first.getImageRequest(0)); holder.rebind(); assertEmpty(kind, holder);
			cache.put(first.getImageRequest(0), image); holder.rebind();
			cache.remove(first.getImageRequest(0)); image.getBitmap().recycle(); holder.rebind();
			assertEmpty(kind, holder); assertNull(cache.getFromTop(first.getImageRequest(0)));
		}
	}

	@Test public void changedAccountParentEntityUrlOrPresentationCannotRetainOldBitmap(){
		for(Kind kind:Kind.values()) for(int change=0; change<5; change++){
			Status a=post(kind, url()); RetryCallbacks callbacks=new RetryCallbacks();
			StatusDisplayItem original=model(kind, a, "account-a", a.id, false, callbacks);
			StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind);
			BitmapDrawable image=bitmap(); cache.put(original.getImageRequest(0), image); holder.bind(original);
			cache.remove(original.getImageRequest(0));
			Status b=post(kind, coverUrl(kind, a));
			if(change==2){ if(kind==Kind.ALBUM) b.albumUpdate.albumId="other-album"; else { b.friendCard.id="other-card"; b.friendCard.card.id="other-card"; } }
			if(change==3) setCoverUrl(kind, b, url());
			Kind nextKind=kind;
			if(change==4){
				if(kind==Kind.ALBUM){ b.albumUpdate.downloadProtected=true; }
				else { nextKind=kind==Kind.LINK ? Kind.PUBLICATION : Kind.LINK; b.friendCard.mode=nextKind==Kind.LINK ? "link" : "publication"; }
			}
			holder.bind(model(nextKind, b, change==0 ? "account-b" : "account-a", change==1 ? "other-post" : a.id, false, callbacks));
			if(kind==Kind.ALBUM && change==4) assertNotSame(image, imageView(kind, holder).getDrawable());
			else { assertNull(publicationCover(holder, kind).getDrawable()); if(kind!=Kind.ALBUM) assertNull(linkCover(holder).getDrawable()); }
		}
	}

	@Test public void wrongSizeCacheEntryMissesAndNewAccountOnlyRestoresItsCurrentlyPermittedPublicProjection(){
		for(Kind kind:Kind.values()){
			Status status=post(kind, url()); RetryCallbacks callbacks=new RetryCallbacks();
			StatusDisplayItem a=model(kind, status, "account-a", status.id, false, callbacks);
			UrlImageLoaderRequest exact=(UrlImageLoaderRequest)a.getImageRequest(0);
			cache.put(new UrlImageLoaderRequest(exact.uri, exact.desiredMaxWidth+1, exact.desiredMaxHeight), bitmap());
			StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind); holder.bind(a); assertEmpty(kind, holder);
			BitmapDrawable permitted=bitmap(); cache.put(exact, permitted);
			StatusDisplayItem b=model(kind, status, "account-b", status.id, false, callbacks);
			holder.bind(b); assertSame(permitted, imageView(kind, holder).getDrawable());
			// AppKit's existing public URL top cache is shared; permission still comes from B's model.
			status.visibility=StatusPrivacy.PRIVATE;
			holder.bind(model(kind, status, "account-b", status.id, false, callbacks));
			assertEmpty(kind, holder); assertEquals(0, holder.getItem().getImageCount());
			((ImageLoaderViewHolder)holder).setImage(0, permitted); assertEmpty(kind, holder);
			cache.clearTopLevel();
		}
	}

	@Test public void warmPublicToRestrictedMissingOrUnavailableClearsAndRejectsLateImagesAndRetries(){
		for(Kind kind:Kind.values()) for(int block=0; block<8; block++){
			Status allowed=post(kind, url()); RetryCallbacks callbacks=new RetryCallbacks();
			StatusDisplayItem original=model(kind, allowed, "account-a", allowed.id, false, callbacks);
			StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind);
			BitmapDrawable image=bitmap(); cache.put(original.getImageRequest(0), image); holder.bind(original);
			Status blocked=post(kind, coverUrl(kind, allowed));
			switch(block){
				case 0 -> blocked.sensitive=true;
				case 1 -> blocked.spoilerText="Hidden CW";
				case 2 -> blocked.visibility=StatusPrivacy.PRIVATE;
				case 3 -> blocked.visibility=StatusPrivacy.DIRECT;
				case 4 -> setCoverUrl(kind, blocked, null);
				case 5 -> { if(kind==Kind.ALBUM) blocked.albumUpdate.downloadProtected=true; else blocked.friendCard.available=false; }
				case 6 -> { if(kind==Kind.ALBUM) blocked.albumUpdate.photoCount=0; else blocked.friendCard.schemaVersion=2; }
				case 7 -> { if(kind==Kind.ALBUM) blocked.albumUpdate.coverUrl="file:///not-public"; }
			}
			StatusDisplayItem restricted=model(kind, blocked, "account-a", blocked.id, kind!=Kind.ALBUM && block==7, callbacks);
			assertEquals(kind+" blocked "+block, 0, restricted.getImageCount()); assertNull(restricted.getImageRequest(0));
			holder.bind(restricted);
			Drawable placeholder=imageView(kind, holder).getDrawable();
			if(kind==Kind.ALBUM && block==5) assertNotNull(placeholder); else assertEmpty(kind, holder);
			ImageLoaderViewHolder images=(ImageLoaderViewHolder)holder;
			images.setImage(0, image); images.setImage(1, image);
			if(kind==Kind.ALBUM && block==5) assertNotSame(image, imageView(kind, holder).getDrawable()); else assertEmpty(kind, holder);
			images.onImageLoadingFailed(0, new IOException("blocked")); images.onImageLoadingFailed(1, new IOException("wrong index"));
			assertEquals(0, callbacks.schedules);
			cache.clearTopLevel();
		}
	}

	@Test public void inPlacePrivacyChangeWithoutRebindCannotAcceptImageOrScheduleRetry(){
		for(Kind kind:Kind.values()){
			Status status=post(kind, url()); RetryCallbacks callbacks=new RetryCallbacks();
			StatusDisplayItem model=model(kind, status, "account-a", status.id, false, callbacks);
			StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind);
			cache.put(model.getImageRequest(0), bitmap()); holder.bind(model); status.sensitive=true;
			ImageLoaderViewHolder images=(ImageLoaderViewHolder)holder;
			images.setImage(0, bitmap()); assertEmpty(kind, holder);
			images.onImageLoadingFailed(0, new IOException("late")); assertEquals(0, callbacks.schedules);
		}
	}

	@Test public void replacedAttachmentSnapshotWithoutRebindCannotAcceptImageOrScheduleRetry(){
		for(Kind kind:Kind.values()){
			Status status=post(kind, url()); RetryCallbacks callbacks=new RetryCallbacks();
			StatusDisplayItem model=model(kind, status, "account-a", status.id, false, callbacks);
			StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind);
			cache.put(model.getImageRequest(0), bitmap()); holder.bind(model);
			Status replacement=post(kind, coverUrl(kind, status));
			if(kind==Kind.ALBUM){ replacement.albumUpdate.downloadProtected=true; status.albumUpdate=replacement.albumUpdate; }
			else { replacement.friendCard.available=false; status.friendCard=replacement.friendCard; }
			assertEquals(0, model.getImageCount()); assertNull(model.getImageRequest(0));
			ImageLoaderViewHolder images=(ImageLoaderViewHolder)holder;
			images.setImage(0, bitmap()); assertEmpty(kind, holder);
			images.onImageLoadingFailed(0, new IOException("old snapshot")); assertEquals(0, callbacks.schedules);
		}
	}

	@Test public void retryBudgetSurvivesNewModelsRebindSuccessAndReturnToSameIdentity(){
		for(Kind kind:Kind.values()){
			RetryCallbacks callbacks=new RetryCallbacks(); Status a=post(kind, url());
			StatusDisplayItem.Holder<StatusDisplayItem> holder=holder(kind);
			ImageLoaderViewHolder images=(ImageLoaderViewHolder)holder;
			for(int attempt=0; attempt<8; attempt++){
				holder.bind(model(kind, a, "account-a", a.id, false, callbacks)); holder.rebind();
				images.onImageLoadingFailed(0, new IOException("offline")); assertEmpty(kind, holder);
			}
			assertEquals(2, callbacks.schedules);
			images.setImage(0, bitmap()); images.onImageLoadingFailed(0, new IOException("permanent"));
			assertEquals("A success callback must not restart an automatic failure loop", 2, callbacks.schedules);
			Status b=post(kind, url());
			holder.bind(model(kind, b, "account-a", b.id, false, callbacks)); images.onImageLoadingFailed(0, new IOException("new URL"));
			assertEquals(3, callbacks.schedules);
			holder.bind(model(kind, a, "account-a", a.id, false, callbacks)); images.onImageLoadingFailed(0, null);
			assertEquals("A -> B -> A must not replenish A's failure budget", 3, callbacks.schedules);
			images.onImageLoadingFailed(1, null); assertEquals(3, callbacks.schedules);
		}
	}

	@Test public void bindingFailureBudgetIsBoundedAndMismatchedKeyDoesNotConsumeIt(){
		CoverImageBinding binding=new CoverImageBinding();
		ImageLoaderRequest request=new UrlImageLoaderRequest(url(), 100, 100);
		CoverImageBinding.Key first=new CoverImageBinding.Key("account", "post", "album", "album", request.getMemoryCacheKey());
		binding.bind(activity, first, request);
		assertFalse(binding.shouldRetry(new CoverImageBinding.Key("other-account", "post", "album", "album", request.getMemoryCacheKey())));
		assertTrue(binding.shouldRetry(first)); assertTrue(binding.shouldRetry(first)); assertFalse(binding.shouldRetry(first));
		for(int index=0; index<64; index++){
			CoverImageBinding.Key next=new CoverImageBinding.Key("account", "post-"+index, "album", "album", request.getMemoryCacheKey());
			binding.bind(activity, next, request); assertTrue(binding.shouldRetry(next));
		}
		binding.bind(activity, first, request);
		assertTrue("An old, evicted feed identity may retry when revisited much later", binding.shouldRetry(first));
	}

	@Test public void realWrapperRebindDoesNotClearSameRequestAfterTopEvictionButExplicitClearStillWorks() throws Exception{
		for(Kind kind:Kind.values()){
			RetryCallbacks callbacks=new RetryCallbacks(); Status status=post(kind, url());
			StatusDisplayItem model=model(kind, status, "account-a", status.id, false, callbacks);
			BitmapDrawable image=bitmap(); cache.put(model.getImageRequest(0), image);
			try(LoaderFixture fixture=new LoaderFixture(kind, model, callbacks)){
				assertSame(image, imageView(kind, fixture.boundHolder()).getDrawable());
				cache.remove(model.getImageRequest(0));
				fixture.bind(model(kind, status, "account-a", status.id, false, callbacks));
				assertSame(image, imageView(kind, fixture.boundHolder()).getDrawable());
				((ImageLoaderViewHolder)fixture.boundHolder()).clearImage(0); assertEmpty(kind, fixture.boundHolder());
				cache.remove(model.getImageRequest(0)); fixture.bind(model); assertEmpty(kind, fixture.boundHolder());
				// Inactive wrappers do not refill: model.bind itself must restore a warm top hit.
				fixture.wrapper.deactivate(); cache.put(model.getImageRequest(0), image); fixture.bind(model);
				assertSame(image, imageView(kind, fixture.boundHolder()).getDrawable());
			}
		}
	}

	@Test public void realStickyFailureSchedulesRetryAndExistingLoaderRecoversWithoutAnotherBind() throws Exception{
		for(Kind kind:Kind.values()){
			String cover=url(); RetryCallbacks callbacks=new RetryCallbacks(); Status status=post(kind, cover);
			ControlledDownloader downloader=new ControlledDownloader(cover, 1, false);
			ImageCache.registerDownloader(0, downloader);
			try(LoaderFixture fixture=new LoaderFixture(kind, model(kind, status, "account-a", status.id, false, callbacks), callbacks)){
				fixture.wrapper.forceUpdateImages(); await(()->callbacks.schedules==1);
				assertEquals(1, downloader.attempts.get()); assertEmpty(kind, fixture.boundHolder());
				fixture.wrapper.forceUpdateImages(); fixture.wrapper.forceUpdateImages();
				assertEquals("Ordinary updates skip AppKit's sticky failed position", 1, downloader.attempts.get());
				int binds=fixture.adapter.binds;
				callbacks.runRetry(); await(()->fixture.list.loaded>0);
				assertEquals(2, downloader.attempts.get()); assertEquals(1, callbacks.schedules);
				assertEquals("Retry must not require an unrelated RecyclerView bind", binds, fixture.adapter.binds);
				assertSame(downloader.image, imageView(kind, fixture.boundHolder()).getDrawable()); assertVisible(kind, fixture.boundHolder());
			}
		}
	}

	@Test public void realPermanentFailureStopsAtTwoScheduledRetriesEvenWhenWrapperRebindsFailedPosition() throws Exception{
		for(Kind kind:Kind.values()){
			String cover=url(); RetryCallbacks callbacks=new RetryCallbacks(); Status status=post(kind, cover);
			ControlledDownloader downloader=new ControlledDownloader(cover, Integer.MAX_VALUE, false); ImageCache.registerDownloader(0, downloader);
			try(LoaderFixture fixture=new LoaderFixture(kind, model(kind, status, "account-a", status.id, false, callbacks), callbacks)){
				fixture.wrapper.forceUpdateImages(); await(()->callbacks.schedules==1);
				callbacks.runRetry(); await(()->callbacks.schedules==2);
				callbacks.runRetry(); await(()->fixture.list.failed==3);
				for(int rebind=0; rebind<6; rebind++) fixture.bind(model(kind, status, "account-a", status.id, false, callbacks));
				assertEquals(3, downloader.attempts.get()); assertEquals(2, callbacks.schedules); assertFalse(callbacks.pending);
				assertEmpty(kind, fixture.boundHolder());
			}
		}
	}

	@Test public void realLoaderDropsInFlightOldRequestAfterRecycleToDifferentPublicEntityAndUrl() throws Exception{
		for(Kind kind:Kind.values()){
			String oldUrl=url(); Status a=post(kind, oldUrl); RetryCallbacks callbacks=new RetryCallbacks();
			ControlledDownloader downloader=new ControlledDownloader(oldUrl, 0, true); ImageCache.registerDownloader(0, downloader);
			try(LoaderFixture fixture=new LoaderFixture(kind, model(kind, a, "account-a", a.id, false, callbacks), callbacks)){
				fixture.wrapper.forceUpdateImages(); await(()->downloader.entered.getCount()==0);
				Status b=post(kind, url()); b.id="p_other";
				if(kind==Kind.ALBUM) b.albumUpdate.albumId="album-other"; else { b.friendCard.id="card-other"; b.friendCard.card.id="card-other"; }
				fixture.bind(model(kind, b, "account-a", b.id, false, callbacks)); assertEmpty(kind, fixture.boundHolder());
				downloader.release.countDown(); await(()->!fixture.engine.isLoading(0, 0));
				assertEquals("Real RunnableTask must reject the old request before adapter.imageLoaded", 0, fixture.list.loaded);
				assertEmpty(kind, fixture.boundHolder());
			}finally{ downloader.release.countDown(); }
		}
	}

	@Test public void realLoaderDropsInFlightCoverAfterSameUrlBecomesProtectedOrUnavailable() throws Exception{
		for(Kind kind:Kind.values()){
			String cover=url(); Status status=post(kind, cover); RetryCallbacks callbacks=new RetryCallbacks();
			ControlledDownloader downloader=new ControlledDownloader(cover, 0, true); ImageCache.registerDownloader(0, downloader);
			try(LoaderFixture fixture=new LoaderFixture(kind, model(kind, status, "account-a", status.id, false, callbacks), callbacks)){
				fixture.wrapper.forceUpdateImages(); await(()->downloader.entered.getCount()==0);
				if(kind==Kind.ALBUM) status.albumUpdate.downloadProtected=true; else status.friendCard.available=false;
				fixture.bind(model(kind, status, "account-a", status.id, false, callbacks));
				downloader.release.countDown(); await(()->!fixture.engine.isLoading(0, 0));
				assertEquals(0, fixture.list.loaded); assertEquals(0, callbacks.schedules);
				if(kind==Kind.ALBUM) assertNotSame(downloader.image, imageView(kind, fixture.boundHolder()).getDrawable());
				else assertEmpty(kind, fixture.boundHolder());
			}finally{ downloader.release.countDown(); }
		}
	}

	private final class RetryCallbacks extends StatusDisplayItem.NoOpCallbacks{
		int schedules; boolean pending; Runnable retry;
		RetryCallbacks(){ super(activity); }
		@Override public void scheduleRetryFailedImages(){ schedules++; pending=true; }
		void runRetry(){ assertTrue(pending); pending=false; retry.run(); }
	}

	private final class LoaderFixture implements AutoCloseable{
		final RecordingList list=new RecordingList();
		final ListImageLoaderWrapper wrapper;
		final CardAdapter adapter;
		final ListImageLoader engine;
		LoaderFixture(Kind kind, StatusDisplayItem model, RetryCallbacks callbacks) throws Exception{
			list.setLayoutManager(new LinearLayoutManager(activity)); list.setItemAnimator(null);
			wrapper=new ListImageLoaderWrapper(activity, list, list, null);
			adapter=new CardAdapter(kind, model, wrapper); list.setAdapter(adapter); activity.setContentView(list);
			list.measure(View.MeasureSpec.makeMeasureSpec(V.dp(380), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(V.dp(800), View.MeasureSpec.EXACTLY));
			list.layout(0, 0, list.getMeasuredWidth(), list.getMeasuredHeight());
			Field field=ListImageLoaderWrapper.class.getDeclaredField("imgLoader"); field.setAccessible(true); engine=(ListImageLoader)field.get(wrapper);
			callbacks.retry=wrapper::retryFailedRequests;
			assertNotNull(boundHolder());
		}
		@SuppressWarnings("unchecked") StatusDisplayItem.Holder<StatusDisplayItem> boundHolder(){ return (StatusDisplayItem.Holder<StatusDisplayItem>)list.findViewHolderForAdapterPosition(0); }
		void bind(StatusDisplayItem model){
			adapter.model=model;
			// Same synchronous order as DisplayItemsAdapter: model.bind then the real wrapper.
			// Do not notify/cancel in-flight tasks: this also exercises RunnableTask's own guard.
			adapter.onBindViewHolder(boundHolder(), 0);
		}
		@Override public void close(){ wrapper.deactivate(); list.setAdapter(null); }
	}

	private final class RecordingList extends UsableRecyclerView{
		int loaded, failed;
		RecordingList(){ super(activity); }
		@Override public void imageLoaded(int position, int image, Drawable drawable){ loaded++; super.imageLoaded(position, image, drawable); }
		@Override public void imageLoadingFailed(int position, int image, Throwable error){ failed++; super.imageLoadingFailed(position, image, error); }
	}

	private final class CardAdapter extends UsableRecyclerView.Adapter<BindableViewHolder<StatusDisplayItem>> implements ImageLoaderRecyclerAdapter{
		final Kind kind; StatusDisplayItem model; int binds;
		CardAdapter(Kind kind, StatusDisplayItem model, ListImageLoaderWrapper wrapper){ super(wrapper); this.kind=kind; this.model=model; }
		@Override public BindableViewHolder<StatusDisplayItem> onCreateViewHolder(ViewGroup parent, int type){ return holder(kind); }
		@Override public void onBindViewHolder(BindableViewHolder<StatusDisplayItem> holder, int position){ binds++; holder.bind(model); super.onBindViewHolder(holder, position); }
		@Override public int getItemCount(){ return 1; }
		@Override public int getImageCountForItem(int position){ return model.getImageCount(); }
		@Override public ImageLoaderRequest getImageRequest(int position, int image){ return model.getImageRequest(image); }
	}

	private final class ControlledDownloader extends ImageDownloader{
		final String url; final int failures; final boolean gated;
		final AtomicInteger attempts=new AtomicInteger();
		final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
		final BitmapDrawable image=bitmap();
		ControlledDownloader(String url, int failures, boolean gated){ this.url=url; this.failures=failures; this.gated=gated; }
		@Override public boolean needsDiskCache(){ return false; }
		@Override public boolean canHandleRequest(ImageLoaderRequest request){ return request instanceof UrlImageLoaderRequest u && url.equals(u.uri.toString()); }
		@Override public Drawable getDrawable(ImageLoaderRequest request, boolean decode, ImageCache.ImageDownloadInfo info) throws IOException{
			int attempt=attempts.incrementAndGet(); entered.countDown();
			if(gated) try{ if(!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test gate timed out"); }
			catch(InterruptedException error){ Thread.currentThread().interrupt(); throw new IOException(error); }
			if(attempt<=failures) throw new IOException("Synthetic offline/permanent failure");
			return image;
		}
	}

	private static void await(BooleanSupplier condition) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		do{
			ShadowLooper.idleMainLooper();
			if(condition.getAsBoolean()){ ShadowLooper.idleMainLooper(); return; }
			Thread.sleep(10);
		}while(System.nanoTime()<deadline);
		fail("Timed out waiting for the real AppKit image pipeline");
	}

	private Status post(Kind kind, String cover){
		Status status=Status.ofFake("p_card", "", Instant.parse("2026-10-27T00:00:00Z")); status.visibility=StatusPrivacy.PUBLIC;
		if(kind==Kind.ALBUM){
			AlbumUpdate album=new AlbumUpdate(); album.albumId="album"; album.albumName="Album"; album.photoCount=3; album.width=1200; album.height=1600; album.coverUrl=cover;
			status.albumUpdate=album;
		}else{
			FriendCard card=new FriendCard(); card.schemaVersion=1; card.id="card"; card.state="active"; card.title="Friends"; card.coverUrl=cover; card.publicFields=List.of();
			FriendCardAttachment attachment=new FriendCardAttachment(); attachment.schemaVersion=1; attachment.id=card.id; attachment.available=true;
			attachment.title=card.title; attachment.mode=kind==Kind.LINK ? "link" : "publication"; attachment.card=card;
			FriendCardAttachment.LinkPreview preview=new FriendCardAttachment.LinkPreview(); preview.coverUrl=cover; preview.ownerName="Owner"; attachment.linkPreview=preview;
			status.friendCard=attachment;
		}
		return status;
	}

	private StatusDisplayItem model(Kind kind, Status status, String account, String parent, boolean hidden, RetryCallbacks callbacks){
		return kind==Kind.ALBUM ? new AlbumUpdateStatusDisplayItem(parent, callbacks, activity, status, account)
				: new FriendCardStatusDisplayItem(parent, callbacks, activity, status, account, hidden);
	}
	@SuppressWarnings("unchecked") private StatusDisplayItem.Holder<StatusDisplayItem> holder(Kind kind){
		return (StatusDisplayItem.Holder<StatusDisplayItem>)(StatusDisplayItem.Holder<?>)(kind==Kind.ALBUM
				? new AlbumUpdateStatusDisplayItem.Holder(activity, new FrameLayout(activity)) : new FriendCardStatusDisplayItem.Holder(activity, new FrameLayout(activity)));
	}
	private static ImageView publicationCover(StatusDisplayItem.Holder<?> holder, Kind kind){
		LinearLayout card=(LinearLayout)((FrameLayout)holder.itemView).getChildAt(0);
		return kind==Kind.ALBUM ? (ImageView)card.getChildAt(0) : (ImageView)((FrameLayout)card.getChildAt(0)).getChildAt(0);
	}
	private static ImageView linkCover(StatusDisplayItem.Holder<?> holder){
		LinearLayout row=(LinearLayout)((FrameLayout)holder.itemView).getChildAt(1);
		return (ImageView)((FrameLayout)((FrameLayout)row.getChildAt(0)).getChildAt(0)).getChildAt(0);
	}
	private static ImageView imageView(Kind kind, StatusDisplayItem.Holder<?> holder){ return kind==Kind.LINK ? linkCover(holder) : publicationCover(holder, kind); }
	private static void assertEmpty(Kind kind, StatusDisplayItem.Holder<?> holder){
		assertNull(kind.name(), imageView(kind, holder).getDrawable());
		if(kind==Kind.PUBLICATION) assertEquals(View.GONE, ((View)imageView(kind, holder).getParent()).getVisibility());
		if(kind==Kind.LINK){ assertEquals(View.GONE, linkCover(holder).getVisibility()); assertEquals(View.VISIBLE, ((ViewGroup)linkCover(holder).getParent()).getChildAt(1).getVisibility()); }
	}
	private static void assertVisible(Kind kind, StatusDisplayItem.Holder<?> holder){
		if(kind==Kind.PUBLICATION) assertEquals(View.VISIBLE, ((View)imageView(kind, holder).getParent()).getVisibility());
		else assertEquals(View.VISIBLE, imageView(kind, holder).getVisibility());
	}
	private static String coverUrl(Kind kind, Status status){ return kind==Kind.ALBUM ? status.albumUpdate.coverUrl : kind==Kind.LINK ? status.friendCard.linkPreview.coverUrl : status.friendCard.card.coverUrl; }
	private static void setCoverUrl(Kind kind, Status status, String url){ if(kind==Kind.ALBUM) status.albumUpdate.coverUrl=url; else { status.friendCard.card.coverUrl=url; status.friendCard.linkPreview.coverUrl=url; } }
	private static String url(){ return "https://offline.example.test/card-cache/"+UUID.randomUUID(); }
	private BitmapDrawable bitmap(){ return new BitmapDrawable(activity.getResources(), Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888)); }
}
