package org.joinmastodon.android.ui.utils;

import android.net.Uri;

import org.joinmastodon.android.api.NBWUrlResolver;
import org.joinmastodon.android.api.session.NBWEndpointsRepository;

import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;

/** Resolve at the image-loader sink, not when a cached model/display item was constructed. */
public final class NBWImageRequests{
	private NBWImageRequests(){}
	public static ImageLoaderRequest current(ImageLoaderRequest original){
		if(!(original instanceof UrlImageLoaderRequest url) || !NBWUrlResolver.isTrusted(url.uri.toString())) return original;
		var snapshot=NBWEndpointsRepository.getInstance().getSnapshot();
		var session=org.joinmastodon.android.api.session.AccountSessionManager.getInstance().getLastActiveAccount();
		if(!snapshot.canRead() || session!=null && !session.getNBWForumOptionsRepository().getSnapshot().canRead()) return null; // Cold/unknown configuration must not fetch the old primary.
		String resolved=NBWUrlResolver.resolve(url.uri.toString(),snapshot.endpoints,true);
		return new ScopedRequest(original,resolved,snapshot,session);
	}
	public static boolean accepts(ImageLoaderRequest request){ return !(request instanceof ScopedRequest scoped) || scoped.current(); }
	public static void load(android.widget.ImageView view,android.graphics.drawable.Drawable placeholder,ImageLoaderRequest request){
		if(request==null){ view.setImageDrawable(null); return; }
		if(!(request instanceof ScopedRequest)){ me.grishka.appkit.imageloader.ViewImageLoader.loadWithoutAnimation(view,placeholder,request); return; }
		me.grishka.appkit.imageloader.ViewImageLoader.load(new me.grishka.appkit.imageloader.ViewImageLoader.Target(){
			@Override public android.view.View getView(){ return view; }
			@Override public void setImageDrawable(android.graphics.drawable.Drawable drawable){ if(accepts(request)) view.setImageDrawable(drawable); }
		},placeholder,request,false);
	}
	private static final class ScopedRequest extends UrlImageLoaderRequest{
		private final long generation, sessionGeneration;
		private final org.joinmastodon.android.api.session.AccountSession session;
		private final org.joinmastodon.android.model.Token token;
		private final String accessToken, suffix, diskCacheKey;
		ScopedRequest(ImageLoaderRequest original,String resolved,NBWEndpointsRepository.Snapshot routing,org.joinmastodon.android.api.session.AccountSession session){
			super(original.desiredConfig,original.desiredMaxWidth,original.desiredMaxHeight,original.processingSteps,Uri.parse(resolved));
			this.session=session; token=session==null ? null : session.token; accessToken=token==null ? null : token.accessToken;
			generation=routing.generation; sessionGeneration=session==null ? -1 : session.getNBWForumOptionsRepository().getSnapshot().generation;
			suffix="/nbw-"+generation+"-"+routing.endpoints.revision+"-"+System.identityHashCode(session)+"-"+sessionGeneration;
			// DiskLruCache treats its key as a file name: hash the full scope, never append a path separator.
			// Cache naming only, not authentication. Okio uses a local MessageDigest, unlike AppKit's shared digest.
			diskCacheKey=okio.ByteString.encodeUtf8(super.getDiskCacheKey()+suffix).md5().hex();
		}
		boolean current(){
			var routing=NBWEndpointsRepository.getInstance().getSnapshot();
			var active=org.joinmastodon.android.api.session.AccountSessionManager.getInstance().getLastActiveAccount();
			return routing.canRead() && routing.generation==generation && active==session && (session==null
					|| session.token==token && java.util.Objects.equals(accessToken,token==null ? null : token.accessToken)
					&& session.getNBWForumOptionsRepository().getSnapshot().generation==sessionGeneration && session.getNBWForumOptionsRepository().getSnapshot().canRead());
		}
		private boolean sameScope(ScopedRequest other){
			return generation==other.generation && sessionGeneration==other.sessionGeneration && session==other.session && token==other.token
					&& java.util.Objects.equals(accessToken,other.accessToken) && suffix.equals(other.suffix);
		}
		@Override public boolean equals(Object other){ return other instanceof ScopedRequest scoped && sameScope(scoped) && super.equals(other); }
		@Override public int hashCode(){ return java.util.Objects.hash(super.hashCode(),generation,sessionGeneration,System.identityHashCode(session),System.identityHashCode(token),accessToken,suffix); }
		@Override public boolean sourcesEqual(ImageLoaderRequest other){ return other instanceof ScopedRequest scoped && sameScope(scoped) && super.sourcesEqual(other); }
		@Override public String getMemoryCacheKey(){ return super.getMemoryCacheKey()+suffix; }
		@Override public String getDiskCacheKey(){ return diskCacheKey; }
	}
}
