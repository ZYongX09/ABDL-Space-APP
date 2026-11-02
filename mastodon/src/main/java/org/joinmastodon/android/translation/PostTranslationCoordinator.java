package org.joinmastodon.android.translation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.statuses.GetStatusTranslation;
import org.joinmastodon.android.api.requests.statuses.TranslateStatus;
import org.joinmastodon.android.api.requests.translation.GetTranslationPreferences;
import org.joinmastodon.android.api.requests.translation.TranslationRequest.TranslationError;
import org.joinmastodon.android.api.requests.translation.UpdateTranslationPreferences;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.Translation;
import org.joinmastodon.android.model.TranslationPreferences;
import org.joinmastodon.android.sponsors.SponsorContrast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** Main-thread, account-scoped single-flight coordinator shared by every translation entry point. */
public final class PostTranslationCoordinator{
	public static final int MAX_ACTIVE=2, MAX_QUEUED=24;
	public static final long POLL_WINDOW_MS=20000;
	private static final Map<String, PostTranslationCoordinator> accounts=new HashMap<>();

	public interface Cancelable{ void cancel(); }
	public interface Environment{
		boolean current();
		String target();
		boolean sponsorActive();
		long now();
	}
	public interface Scheduler{ Cancelable later(Runnable action, long delay); }
	public interface Transport{
		Cancelable translate(Key key, boolean automatic, Callback<Translation> callback);
		Cancelable query(Key key, Callback<Translation> callback);
		Cancelable preferences(Callback<TranslationPreferences> callback);
		Cancelable savePreferences(String language, boolean automatic, String revision, Callback<TranslationPreferences> callback);
	}
	public interface Listener{
		/** Verify that the exact resource + local source version is still in this host's display tree. */
		boolean current(Key key);
		boolean visible();
		void changed();
		void error(ErrorResponse error);
	}
	public record Key(String accountID, String resourceID, String target, String sourceVersion){}
	public static final class Visit{
		private final Set<Key> suppressed=new HashSet<>(), automaticAttempts=new HashSet<>();
		private boolean active=true;
	}

	private final String accountID;
	private final Environment environment;
	private final Transport transport;
	private final Scheduler scheduler;
	private final Map<Key, Job> jobs=new LinkedHashMap<>();
	private final ArrayDeque<Job> queue=new ArrayDeque<>();
	private final Set<Key> queryOnly=new HashSet<>(), unknownOutcomes=new HashSet<>();
	private record Cached(Translation value, long validatedAt){}
	private final LinkedHashMap<Key, Cached> cache=new LinkedHashMap<>(16, .75f, true);
	public static final long CACHE_TTL_MS=60000;
	private final Set<Runnable> preferenceObservers=new LinkedHashSet<>();
	private TranslationPreferences preferences;
	private Cancelable preferenceCall;
	private long epoch, preferenceGeneration, lastPreferencesAttempt=Long.MIN_VALUE;
	private String target, attemptedLanguageSync;
	private int active, preferenceConflicts;
	private Boolean desiredAutomatic;
	private boolean preferencesBusy;
	private SharedPreferences accountPrefs;
	private SharedPreferences.OnSharedPreferenceChangeListener accountListener;

	/** Dependencies are explicit so queue, timeout and account fences can be behavior-tested offline. */
	public PostTranslationCoordinator(String accountID, Environment environment, Transport transport, Scheduler scheduler){
		this.accountID=accountID; this.environment=environment; this.transport=transport; this.scheduler=scheduler;
		target=PostTranslationSupport.normalizeLanguage(environment.target());
	}

	public static PostTranslationCoordinator get(Context context, String accountID){
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session==null) return null;
		PostTranslationCoordinator existing=accounts.get(accountID);
		if(existing!=null && existing.environment.current()) return existing;
		if(existing!=null) existing.dispose();
		Context app=context.getApplicationContext();
		Handler main=new Handler(Looper.getMainLooper());
		Environment environment=new Environment(){
			@Override public boolean current(){ return AccountSessionManager.getInstance().tryGetAccount(accountID)==session && Objects.equals(accountID, AccountSessionManager.getInstance().getLastActiveAccountID()); }
			@Override public String target(){ return PostTranslationSupport.target(app); }
			@Override public long now(){ return SystemClock.elapsedRealtime(); }
			@Override public boolean sponsorActive(){
				var sponsor=session.self==null ? null : session.self.sponsor;
				return sponsor!=null && SponsorContrast.active(sponsor.active, sponsor.permanent, sponsor.validUntil, System.currentTimeMillis()/1000);
			}
		};
		Transport transport=new Transport(){
			private <T> Cancelable execute(MastodonAPIRequest<T> request, Callback<T> callback){ request.setCallback(callback).exec(accountID); return request::cancel; }
			@Override public Cancelable translate(Key key, boolean automatic, Callback<Translation> cb){ return execute(new TranslateStatus(key.resourceID, key.target, automatic), cb); }
			@Override public Cancelable query(Key key, Callback<Translation> cb){ return execute(new GetStatusTranslation(key.resourceID, key.target), cb); }
			@Override public Cancelable preferences(Callback<TranslationPreferences> cb){ return execute(new GetTranslationPreferences(), cb); }
			@Override public Cancelable savePreferences(String language, boolean automatic, String revision, Callback<TranslationPreferences> cb){ return execute(new UpdateTranslationPreferences(language, automatic, revision), cb); }
		};
		PostTranslationCoordinator result=new PostTranslationCoordinator(accountID, environment, transport, (action, delay)->{ main.postDelayed(action, delay); return ()->main.removeCallbacks(action); });
		result.accountPrefs=app.getSharedPreferences("account_manager", Context.MODE_PRIVATE);
		result.accountListener=(prefs, key)->{ if("lastActiveAccount".equals(key) && !environment.current()) result.dispose(); };
		result.accountPrefs.registerOnSharedPreferenceChangeListener(result.accountListener);
		E.register(result); accounts.put(accountID, result); return result;
	}

	@Subscribe public void loggedOut(AccountLoggedOutEvent event){ if(accountID.equals(event.id)) dispose(); }
	public void dispose(){
		invalidate(); preferences=null; cache.clear(); queryOnly.clear(); unknownOutcomes.clear(); preferenceObservers.clear();
		if(accountPrefs!=null){ accountPrefs.unregisterOnSharedPreferenceChangeListener(accountListener); accountPrefs=null; E.unregister(this); }
		if(accounts.get(accountID)==this) accounts.remove(accountID);
	}
	private void invalidate(){
		epoch++; preferenceGeneration++;
		if(preferenceCall!=null){ preferenceCall.cancel(); preferenceCall=null; }
		preferencesBusy=false;
		for(Job job:new ArrayList<>(jobs.values())) abandon(job);
	}
	private boolean current(){
		if(!environment.current()){ invalidate(); return false; }
		String now=PostTranslationSupport.normalizeLanguage(environment.target());
		if(!Objects.equals(now, target)){
			invalidate(); target=now; cache.clear(); attemptedLanguageSync=null; lastPreferencesAttempt=Long.MIN_VALUE;
		}
		return !target.isEmpty();
	}

	public TranslationPreferences preferences(){ return preferences; }
	public boolean preferencesBusy(){ return preferencesBusy; }
	public Cancelable observePreferences(Runnable observer){ preferenceObservers.add(observer); return ()->preferenceObservers.remove(observer); }
	private void preferencesChanged(){ for(Runnable observer:new ArrayList<>(preferenceObservers)) observer.run(); }

	/** Called on screen entry/resume, never by builders or on every bind. Errors are throttled too. */
	public void refreshPreferences(boolean force){
		if(!current() || preferencesBusy || !force && lastPreferencesAttempt!=Long.MIN_VALUE && environment.now()-lastPreferencesAttempt<60000) return;
		preferenceConflicts=0; fetchPreferences();
	}
	private void fetchPreferences(){
		preferencesBusy=true; lastPreferencesAttempt=environment.now(); long generation=++preferenceGeneration; String language=target;
		preferencesChanged();
		preferenceCall=transport.preferences(preferenceCallback(generation, language, result->{
			preferences=result; preferenceCall=null;
			boolean languageMismatch=!Objects.equals(PostTranslationSupport.normalizeLanguage(result.readingLanguage), target);
			if(desiredAutomatic!=null || languageMismatch && !Objects.equals(attemptedLanguageSync, target)) savePreferences();
			else{ preferencesBusy=false; preferencesChanged(); pump(); }
		}));
	}
	public void setAutomatic(boolean enabled){
		if(!current() || preferencesBusy || preferences==null) return;
		desiredAutomatic=enabled; preferenceConflicts=0; savePreferences();
	}
	private void savePreferences(){
		if(!current() || preferences==null) return;
		preferencesBusy=true; attemptedLanguageSync=target; long generation=++preferenceGeneration; String language=target;
		boolean automatic=desiredAutomatic==null ? preferences.autoTranslateEnabled : desiredAutomatic;
		preferencesChanged();
		preferenceCall=transport.savePreferences(language, automatic, preferences.revision, preferenceCallback(generation, language, result->{
			preferences=result; desiredAutomatic=null; preferenceCall=null; preferencesBusy=false; preferencesChanged(); pump();
		}));
	}
	private Callback<TranslationPreferences> preferenceCallback(long generation, String language, java.util.function.Consumer<TranslationPreferences> success){
		return new Callback<>(){
			private boolean live(){ return current() && generation==preferenceGeneration && Objects.equals(language, target); }
			@Override public void onSuccess(TranslationPreferences value){ scheduler.later(()->{ if(live()) success.accept(value); }, 0); }
			@Override public void onError(ErrorResponse error){ scheduler.later(()->{
				if(!live()) return;
				preferenceCall=null;
				if(error instanceof TranslationError translated && translated.httpStatus==409 && preferenceConflicts++==0){
					// GET a fresh revision and preserve that response's opt-in on language-only sync.
					attemptedLanguageSync=null; fetchPreferences(); return;
				}
				preferencesBusy=false; desiredAutomatic=null;
				// A missing/disabled backend must never retain stale automatic capability.
				preferences=null; preferencesChanged(); prune();
			}, 0); }
		};
	}
	public boolean canAutomaticallyTranslate(){
		return current() && !preferencesBusy && preferences!=null && preferences.enabled && preferences.providerConfigured
				&& preferences.autoTranslateAllowed && preferences.autoTranslateEnabled && environment.sponsorActive()
				&& Objects.equals(PostTranslationSupport.normalizeLanguage(preferences.readingLanguage), target);
	}

	public void resume(Visit visit){ if(current()) visit.active=true; }
	public void suspend(Visit visit){
		visit.active=false;
		for(Job job:new ArrayList<>(jobs.values())){
			for(Subscriber subscriber:new ArrayList<>(job.subscribers)) if(subscriber.visit==visit) remove(job, subscriber);
			if(job.subscribers.isEmpty()) abandon(job);
		}
		pump();
	}
	public void toggle(Status original, Visit visit, Listener listener){
		if(!current() || !visit.active) return;
		Status status=original.getContentStatus(); PostTranslationSupport.validateCached(status, accountID, target);
		Key key=key(status);
		if(status.translationState==Status.TranslationState.SHOWN || status.translationState==Status.TranslationState.LOADING){
			visit.suppressed.add(key); status.translationState=Status.TranslationState.HIDDEN;
			Job job=jobs.get(key);
			if(job!=null){
				for(Subscriber subscriber:new ArrayList<>(job.subscribers)) if(subscriber.visit==visit) remove(job, subscriber);
				if(job.subscribers.isEmpty()) abandon(job);
			}
			listener.changed(); pump(); return;
		}
		visit.suppressed.remove(key); request(status, visit, listener, false);
	}
	public void automaticallyTranslate(Status status, Visit visit, Listener listener){
		if(!canAutomaticallyTranslate() || !visit.active || !listener.visible()) return;
		status=status.getContentStatus(); PostTranslationSupport.validateCached(status, accountID, target);
		Key key=key(status);
		if(visit.suppressed.contains(key) || status.translationState==Status.TranslationState.SHOWN || visit.automaticAttempts.contains(key)) return;
		request(status, visit, listener, true);
	}
	private Key key(Status status){ return new Key(accountID, status.id, target, PostTranslationSupport.sourceVersion(status)); }
	private void request(Status status, Visit visit, Listener listener, boolean automatic){
		if(!PostTranslationSupport.eligible(status, target)) return;
		Key key=key(status);
		if(!listener.current(key)) return;
		Cached entry=cache.get(key);
		Translation cached=entry!=null && environment.now()-entry.validatedAt<CACHE_TTL_MS ? entry.value : null;
		if(cached==null && status.translation!=null && status.translation.isReady() && environment.now()-status.translationValidatedAt<CACHE_TTL_MS) cached=status.translation;
		if(cached!=null){
			long validatedAt=entry!=null && entry.value==cached ? entry.validatedAt : status.translationValidatedAt;
			PostTranslationSupport.apply(status, accountID, target, key.sourceVersion, cached); status.translationValidatedAt=validatedAt;
			listener.changed(); return;
		}
		// Expired client data is only a hint to perform an ACL/hash-checked server GET.
		if(entry!=null || status.translation!=null) queryOnly.add(key);
		Job job=jobs.get(key);
		if(job==null){
			if(queue.size()>=MAX_QUEUED) return;
			job=new Job(key, epoch, automatic); jobs.put(key, job);
			if(automatic) queue.addLast(job); else queue.addFirst(job);
		}
		for(Subscriber subscriber:job.subscribers) if(subscriber.status==status && subscriber.visit==visit) return;
		job.subscribers.add(new Subscriber(status, visit, listener, automatic));
		PostTranslationSupport.identify(status, accountID, target, key.sourceVersion); status.translationState=Status.TranslationState.LOADING;
		listener.changed(); pump();
	}
	private boolean valid(Job job, Subscriber subscriber, boolean requireVisible){
		return subscriber.visit.active && !subscriber.visit.suppressed.contains(job.key) && subscriber.listener.current(job.key)
				&& Objects.equals(PostTranslationSupport.sourceVersion(subscriber.status), job.key.sourceVersion)
				&& (!subscriber.automatic || canAutomaticallyTranslate() && (!requireVisible || subscriber.listener.visible()));
	}
	public void prune(){
		if(!current()) return;
		for(Job job:new ArrayList<>(jobs.values())){
			for(Subscriber subscriber:new ArrayList<>(job.subscribers)) if(!valid(job, subscriber, true)) remove(job, subscriber);
			if(job.subscribers.isEmpty()) abandon(job);
		}
		pump();
	}
	private void pump(){
		if(!current()) return;
		while(active<MAX_ACTIVE && !queue.isEmpty()){
			Job job=queue.removeFirst();
			for(Subscriber subscriber:new ArrayList<>(job.subscribers)) if(!valid(job, subscriber, true)) remove(job, subscriber);
			if(job.subscribers.isEmpty()){ abandon(job); continue; }
			job.started=true; active++;
			for(Subscriber subscriber:job.subscribers) if(subscriber.automatic) subscriber.visit.automaticAttempts.add(job.key);
			boolean automatic=job.subscribers.stream().allMatch(subscriber->subscriber.automatic);
			Callback<Translation> callback=callback(job);
			if(queryOnly.contains(job.key)){ job.querying=true; job.call=transport.query(job.key, callback); }
			else{
				job.querying=false;
				// Any interrupted POST might already have reached the provider. Only GET it thereafter.
				queryOnly.add(job.key); job.call=transport.translate(job.key, automatic, callback);
			}
		}
	}
	private boolean live(Job job){ return current() && jobs.get(job.key)==job && job.epoch==epoch && Objects.equals(job.key.target, target); }
	private Callback<Translation> callback(Job job){
		return new Callback<>(){
			@Override public void onSuccess(Translation value){ scheduler.later(()->{ if(live(job)){ job.call=null; received(job, value); } }, 0); }
			@Override public void onError(ErrorResponse error){ scheduler.later(()->{
				if(!live(job)) return;
				job.call=null;
				if(error instanceof TranslationError translated){
					if(!unknownOutcomes.contains(job.key) && (job.querying && translated.httpStatus==404
							|| !job.querying && (translated.httpStatus==503 || translated.httpStatus>=400 && translated.httpStatus<500 && translated.httpStatus!=404))) queryOnly.remove(job.key);
					if(job.querying && translated.httpStatus==404){ cache.remove(job.key); for(Subscriber subscriber:job.subscribers) subscriber.status.translation=null; }
					if(translated.httpStatus==503 || translated.httpStatus==429 || translated.httpStatus==403){ preferences=null; preferencesChanged(); }
				}
				finish(job, null, error);
			}, 0); }
		};
	}
	private void received(Job job, Translation value){
		if(value==null || value.targetLanguage!=null && !Objects.equals(PostTranslationSupport.normalizeLanguage(value.targetLanguage), job.key.target)
				|| value.cached && (value.sourceHash==null || value.sourceHash.isBlank())
				|| job.serverHash!=null && !Objects.equals(job.serverHash, value.sourceHash)){
			finish(job, null, new TranslationError(null, 422)); return;
		}
		if(value.sourceHash!=null) job.serverHash=value.sourceHash;
		if(value.isReady()){
			cache.put(job.key, new Cached(value, environment.now())); while(cache.size()>128) cache.remove(cache.keySet().iterator().next());
			queryOnly.remove(job.key); unknownOutcomes.remove(job.key); finish(job, value, null); return;
		}
		if(value.isUnknown()){ unknownOutcomes.add(job.key); finish(job, null, new NoticeError(true)); return; }
		if(!value.isPending()){ finish(job, null, new TranslationError(null, 422)); return; }
		if(job.deadline==0){
			job.deadline=environment.now()+POLL_WINDOW_MS;
			job.timeout=scheduler.later(()->{ if(live(job)) finish(job, null, new NoticeError(false)); }, POLL_WINDOW_MS);
		}
		long delay=Math.max(1, Math.min(5, value.retryAfter==null ? 3 : value.retryAfter))*1000L;
		if(environment.now()+delay>=job.deadline) return;
		job.poll=scheduler.later(()->{
			if(!live(job)) return;
			for(Subscriber subscriber:new ArrayList<>(job.subscribers)) if(!valid(job, subscriber, true)) remove(job, subscriber);
			if(job.subscribers.isEmpty()){ abandon(job); pump(); return; }
			job.querying=true; job.call=transport.query(job.key, callback(job));
		}, delay);
	}
	private void finish(Job job, Translation result, ErrorResponse error){
		if(jobs.remove(job.key)!=job) return;
		queue.remove(job); if(job.started) active--;
		cancelCalls(job);
		for(Subscriber subscriber:new ArrayList<>(job.subscribers)){
			boolean valid=valid(job, subscriber, true);
			if(result!=null && valid){
				PostTranslationSupport.apply(subscriber.status, accountID, job.key.target, job.key.sourceVersion, result);
				subscriber.status.translationValidatedAt=environment.now();
			}
			else if(Objects.equals(subscriber.status.translationSourceVersion, job.key.sourceVersion)) subscriber.status.translationState=Status.TranslationState.HIDDEN;
			if(subscriber.listener.current(job.key)) subscriber.listener.changed();
			if(error!=null && valid && !subscriber.automatic) subscriber.listener.error(error);
		}
		pump();
	}
	private void cancelCalls(Job job){ if(job.call!=null) job.call.cancel(); if(job.poll!=null) job.poll.cancel(); if(job.timeout!=null) job.timeout.cancel(); }
	private void remove(Job job, Subscriber subscriber){
		job.subscribers.remove(subscriber);
		if(subscriber.status.translationState==Status.TranslationState.LOADING && Objects.equals(subscriber.status.translationSourceVersion, job.key.sourceVersion)) subscriber.status.translationState=Status.TranslationState.HIDDEN;
		if(subscriber.listener.current(job.key)) subscriber.listener.changed();
	}
	private void abandon(Job job){
		if(jobs.remove(job.key)!=job) return;
		queue.remove(job); if(job.started) active--; cancelCalls(job);
		for(Subscriber subscriber:new ArrayList<>(job.subscribers)) remove(job, subscriber);
	}
	public static class NoticeError extends TranslationError{
		private final boolean unknown;
		public NoticeError(boolean unknown){ super(null, 202); this.unknown=unknown; }
		@Override public int localizedMessage(){ return unknown ? org.joinmastodon.android.R.string.post_translation_unknown : org.joinmastodon.android.R.string.post_translation_pending; }
	}
	private static final class Subscriber{
		final Status status; final Visit visit; final Listener listener; final boolean automatic;
		Subscriber(Status status, Visit visit, Listener listener, boolean automatic){ this.status=status; this.visit=visit; this.listener=listener; this.automatic=automatic; }
	}
	private static final class Job{
		final Key key; final long epoch; final boolean automatic;
		final ArrayList<Subscriber> subscribers=new ArrayList<>();
		Cancelable call, poll, timeout; boolean started, querying; String serverHash; long deadline;
		Job(Key key, long epoch, boolean automatic){ this.key=key; this.epoch=epoch; this.automatic=automatic; }
	}
}
