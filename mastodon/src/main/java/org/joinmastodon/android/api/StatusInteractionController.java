package org.joinmastodon.android.api;

import android.os.Looper;

import org.joinmastodon.android.E;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.statuses.SetStatusBookmarked;
import org.joinmastodon.android.api.requests.statuses.SetStatusFavorited;
import org.joinmastodon.android.api.requests.statuses.SetStatusReblogged;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.EmojiReactionsUpdatedEvent;
import org.joinmastodon.android.events.StatusCountersUpdatedEvent;
import org.joinmastodon.android.model.Emoji;
import org.joinmastodon.android.model.EmojiCategory;
import org.joinmastodon.android.model.EmojiReaction;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

public class StatusInteractionController{
	private final String accountID;

	// MOSHIDON: for remote interactions
	private final boolean updateCounters;

	private final HashMap<String, MastodonAPIRequest<?>> runningFavoriteRequests=new HashMap<>();
	private final HashMap<String, SetStatusReblogged> runningReblogRequests=new HashMap<>();
	private final HashMap<String, MastodonAPIRequest<?>> runningBookmarkRequests=new HashMap<>();

	public StatusInteractionController(String accountID, /* MOSHIDON */ boolean updateCounters){
		this.accountID=accountID;
		// MOSHIDON:
		this.updateCounters=updateCounters;
	}

	// MOSHIDON: may the overflow helps us all
	public StatusInteractionController(String accountID){
		this(accountID, true);
	}

	// MOSHIDON:
	public void setFavorited(Status status, boolean favorited){
		setFavorited(status, favorited, r->{});
	}


	public void setFavorited(Status status, boolean favorited, Consumer<Status> cb){
		setFavorited(status, favorited, cb, MastodonApp.context);
	}

	public void setFavorited(Status status, boolean favorited, Consumer<Status> cb, android.content.Context context){
		if(!Looper.getMainLooper().isCurrentThread())
			throw new IllegalStateException("Can only be called from main thread");

		// MOSHIDON:
		AccountSession session=AccountSessionManager.get(accountID);
		Instance instance=session.getInstance().get();

		if(!supportsInteraction(status, context)) return;
		if(runningFavoriteRequests.containsKey(status.id)) return;
		final boolean oldFavorited=status.favourited;
		final long oldCount=status.favouritesCount;
		Callback<Status> callback=new Callback<>(){
					private boolean completed;
					@Override
					public void onSuccess(Status result){
						if(completed) return;
						completed=true;
						runningFavoriteRequests.remove(status.id);
						status.favourited=result.favourited;
						status.favouritesCount=result.favouritesCount;

						// MOSHIDON:
						cb.accept(result);
						if(updateCounters) E.post(new StatusCountersUpdatedEvent(result, StatusCountersUpdatedEvent.CounterType.FAVORITES));
						if(instance.isIceshrimpJs()) E.post(new EmojiReactionsUpdatedEvent(status.id, result.reactions, false, null));
					}

					@Override
					public void onError(ErrorResponse error){
						if(completed) return;
						completed=true;
						runningFavoriteRequests.remove(status.id);
						status.favourited=oldFavorited;
						status.favouritesCount=oldCount;
						if(error instanceof MastodonErrorResponse.NBWBindingRequired binding) binding.accountID=accountID;
						error.showToast(context);

						// MOSHIDON:
						cb.accept(status);
						if(updateCounters) E.post(new StatusCountersUpdatedEvent(status, StatusCountersUpdatedEvent.CounterType.FAVORITES));
						if(instance.isIceshrimpJs()) E.post(new EmojiReactionsUpdatedEvent(status.id, status.reactions, false, null));
					}
				};
		MastodonAPIRequest<?> req=interactionRequest(status, true, favorited, callback);
		runningFavoriteRequests.put(status.id, req);
		status.favourited=favorited;
		status.favouritesCount=Math.max(0, oldCount+(oldFavorited==favorited ? 0 : favorited ? 1 : -1));

		// MOSHIDON:
		if(updateCounters) E.post(new StatusCountersUpdatedEvent(status, StatusCountersUpdatedEvent.CounterType.FAVORITES));
		req.exec(accountID);

		// MOSHIDON: all below is for the emojis
		if(instance.configuration==null || instance.configuration.reactions==null)
			return;

		String defaultReactionEmojiRaw=instance.configuration.reactions.defaultReaction;
		if(!instance.isIceshrimpJs() || defaultReactionEmojiRaw==null)
			return;

		boolean reactionIsCustom=defaultReactionEmojiRaw.startsWith(":");
		String defaultReactionEmoji=reactionIsCustom ? defaultReactionEmojiRaw.substring(1, defaultReactionEmojiRaw.length()-1) : defaultReactionEmojiRaw;
		ArrayList<EmojiReaction> reactions=new ArrayList<>(status.reactions.size());
		for(EmojiReaction reaction:status.reactions){
			reactions.add(reaction.copy());
		}
		Optional<EmojiReaction> existingReaction=reactions.stream().filter(r->r.me).findFirst();
		Optional<EmojiReaction> existingDefaultReaction=reactions.stream().filter(r->r.name.equals(defaultReactionEmoji)).findFirst();
		if(existingReaction.isPresent() && !favorited){
			existingReaction.get().me=false;
			existingReaction.get().count--;
			existingReaction.get().pendingChange=true;
		}else if(existingDefaultReaction.isPresent() && favorited){
			existingDefaultReaction.get().count++;
			existingDefaultReaction.get().me=true;
			existingDefaultReaction.get().pendingChange=true;
		}else if(favorited){
			EmojiReaction reaction=null;
			if(reactionIsCustom){
				List<EmojiCategory> customEmojis=AccountSessionManager.getInstance().getCustomEmojis(session.domain);
				for(EmojiCategory category:customEmojis){
					for(Emoji emoji:category.emojis){
						if(emoji.shortcode.equals(defaultReactionEmoji)){
							reaction=EmojiReaction.of(emoji, session.self);
							break;
						}
					}
				}
				if(reaction==null)
					reaction=EmojiReaction.of(defaultReactionEmoji, session.self);
			}else{
				reaction=EmojiReaction.of(defaultReactionEmoji, session.self);
			}
			reaction.pendingChange=true;
			reactions.add(reaction);
		}
		E.post(new EmojiReactionsUpdatedEvent(status.id, reactions, false, null));
	}

	// MOSHIDON:
	public String getAccountID() {
		return accountID;
	}

	// MOSHIDON: we use more parameters
	public void setReblogged(Status status, boolean reblogged, StatusPrivacy visibility, Consumer<Status> cb){
		if(!Looper.getMainLooper().isCurrentThread())
			throw new IllegalStateException("Can only be called from main thread");

		SetStatusReblogged current=runningReblogRequests.remove(status.id);
		if(current!=null){
			current.cancel();
		}
		// MOSHIDON: more parameters
		SetStatusReblogged req=(SetStatusReblogged) new SetStatusReblogged(status.id, reblogged, visibility)
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(Status result){
						runningReblogRequests.remove(status.id);

						// MOSHIDON:
						cb.accept(result);
						if(updateCounters) E.post(new StatusCountersUpdatedEvent(result, StatusCountersUpdatedEvent.CounterType.REBLOGS));
					}

					@Override
					public void onError(ErrorResponse error){
						runningReblogRequests.remove(status.id);
						error.showToast(MastodonApp.context);
						status.reblogged=!reblogged;
						if(reblogged)
							status.reblogsCount--;
						else
							status.reblogsCount++;

						// MOSHIDON
						cb.accept(status);

						E.post(new StatusCountersUpdatedEvent(status, StatusCountersUpdatedEvent.CounterType.REBLOGS));
					}
				})
				.exec(accountID);
		runningReblogRequests.put(status.id, req);
		status.reblogged=reblogged;
		if(reblogged)
			status.reblogsCount++;
		else
			status.reblogsCount--;

		// MOSHIDON:
		if(updateCounters) E.post(new StatusCountersUpdatedEvent(status, StatusCountersUpdatedEvent.CounterType.REBLOGS));
	}

	public void setBookmarked(Status status, boolean bookmarked){
		setBookmarked(status, bookmarked, MastodonApp.context);
	}

	public void setBookmarked(Status status, boolean bookmarked, android.content.Context context){
		if(!Looper.getMainLooper().isCurrentThread())
			throw new IllegalStateException("Can only be called from main thread");

		if(!supportsInteraction(status, context)) return;
		if(runningBookmarkRequests.containsKey(status.id)) return;
		final boolean oldBookmarked=status.bookmarked;
		final long oldCount=status.bookmarksCount;
		Callback<Status> callback=new Callback<>(){
					private boolean completed;
					@Override
					public void onSuccess(Status result){
						if(completed) return;
						completed=true;
						runningBookmarkRequests.remove(status.id);
						status.bookmarked=result.bookmarked;
						status.bookmarksCount=result.bookmarksCount;
						E.post(new StatusCountersUpdatedEvent(result, StatusCountersUpdatedEvent.CounterType.BOOKMARKS));
					}

					@Override
					public void onError(ErrorResponse error){
						if(completed) return;
						completed=true;
						runningBookmarkRequests.remove(status.id);
						status.bookmarked=oldBookmarked;
						status.bookmarksCount=oldCount;
						if(error instanceof MastodonErrorResponse.NBWBindingRequired binding) binding.accountID=accountID;
						error.showToast(context);
						E.post(new StatusCountersUpdatedEvent(status, StatusCountersUpdatedEvent.CounterType.BOOKMARKS));
					}
				};
		MastodonAPIRequest<?> req=interactionRequest(status, false, bookmarked, callback);
		runningBookmarkRequests.put(status.id, req);
		status.bookmarked=bookmarked;
		status.bookmarksCount=Math.max(0, oldCount+(oldBookmarked==bookmarked ? 0 : bookmarked ? 1 : -1));

		// MOSHIDON:
		if(updateCounters) E.post(new StatusCountersUpdatedEvent(status, StatusCountersUpdatedEvent.CounterType.BOOKMARKS));
		req.exec(accountID);
	}

	private boolean supportsInteraction(Status status, android.content.Context context){
		if(status.id.startsWith("nbw_") && (!org.joinmastodon.android.api.requests.statuses.NBWStatusInteraction.supportsID(status.id) || status.inReplyToId!=null)){
			android.widget.Toast.makeText(context, org.joinmastodon.android.R.string.nbw_reply_interaction_unsupported, android.widget.Toast.LENGTH_LONG).show();
			return false;
		}
		return true;
	}

	private MastodonAPIRequest<?> interactionRequest(Status status, boolean favorite, boolean enabled, Callback<Status> callback){
		if(status.id.startsWith("nbw_")){
			return new org.joinmastodon.android.api.requests.statuses.NBWStatusInteraction(status.id, favorite, enabled)
					.setCallback(new Callback<>(){
						@Override public void onSuccess(org.joinmastodon.android.api.requests.statuses.NBWStatusInteraction.Result result){ callback.onSuccess(result.merge(status)); }
						@Override public void onError(ErrorResponse error){ callback.onError(error); }
					});
		}
		return favorite ? new SetStatusFavorited(status.id, enabled).setCallback(callback)
				: new SetStatusBookmarked(status.id, enabled).setCallback(callback);
	}
}
