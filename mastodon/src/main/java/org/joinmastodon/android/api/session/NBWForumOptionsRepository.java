package org.joinmastodon.android.api.session;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.nbw.NBWAppRequest;
import org.joinmastodon.android.model.NBWCapabilities;
import org.joinmastodon.android.model.NBWForumOptions;
import org.joinmastodon.android.model.NBWSelection;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** Session-owned, account-scoped single flight. Opening an editor only observes this snapshot. */
public final class NBWForumOptionsRepository{
	public static final long TTL_MS=5*60*1000L;
	public enum State{ LOADING, READY, STALE, UNAVAILABLE, LOCAL_ONLY }
	public interface Cancelable{ void cancel(); }
	public interface Result<T>{ void success(T value); void failure(); }
	public interface Transport{
		Cancelable capabilities(Result<NBWCapabilities> result);
		Cancelable options(Result<NBWForumOptions> result);
		Cancelable consent(boolean accepted, Result<Object> result);
	}
	public interface SelectionStore{ NBWSelection load(); void save(NBWSelection value); }
	public static final class Snapshot{
		public final State state;
		public final NBWCapabilities capabilities;
		public final NBWForumOptions options;
		public final NBWSelection selection;
		public final boolean consentSaving;
		public final long generation;
		Snapshot(State state, NBWCapabilities capabilities, NBWForumOptions options, NBWSelection selection, boolean consentSaving, long generation){
			this.state=state; this.capabilities=capabilities; this.options=options; this.selection=selection; this.consentSaving=consentSaving; this.generation=generation;
		}
		public boolean canRead(){ return capabilities!=null && capabilities.canRead(); }
		public boolean canWrite(){ return capabilities!=null && capabilities.canWrite(); }
		public boolean hasFallbackConsent(){ return capabilities!=null && Boolean.TRUE.equals(capabilities.fallback.consented); }
	}

	private final Transport transport;
	private final LongSupplier clock;
	private final SelectionStore store;
	private final Runnable invalidateRemote;
	private final Consumer<Runnable> dispatch;
	private final Set<Consumer<Snapshot>> observers=new HashSet<>();
	private final Set<Cancelable> remoteRequests=new HashSet<>();
	private State state=State.UNAVAILABLE;
	private NBWCapabilities capabilities;
	private NBWForumOptions options;
	private NBWSelection selection;
	private Cancelable capabilityCall, optionsCall, consentCall;
	private boolean capabilityLoading, optionsLoading, consentSaving, active;
	private long generation=1, lastAttempt=-TTL_MS, lastOptions=-TTL_MS;

	public NBWForumOptionsRepository(Transport transport, LongSupplier clock, SelectionStore store, Runnable invalidateRemote, Consumer<Runnable> dispatch){
		this.transport=transport; this.clock=clock; this.store=store; this.invalidateRemote=invalidateRemote; this.dispatch=dispatch;
		NBWSelection stored=store.load(); selection=stored==null ? NBWSelection.ai() : stored;
	}

	public static NBWForumOptionsRepository forSession(AccountSession session){
		return new NBWForumOptionsRepository(new Transport(){
			private <T> Cancelable send(NBWAppRequest<T> request, Result<T> result){
				request.setCallback(new Callback<>(){
					@Override public void onSuccess(T value){ result.success(value); }
					@Override public void onError(ErrorResponse error){ result.failure(); }
				}).exec(session.getID());
				return request::cancel;
			}
			public Cancelable capabilities(Result<NBWCapabilities> r){ return send(NBWAppRequest.capabilities(), r); }
			public Cancelable options(Result<NBWForumOptions> r){ return send(NBWAppRequest.options(), r); }
			public Cancelable consent(boolean accepted, Result<Object> r){ return send(NBWAppRequest.fallbackConsent(accepted), r); }
		}, android.os.SystemClock::elapsedRealtime, new SelectionStore(){
			public NBWSelection load(){
				var prefs=session.getRawLocalPreferences();
				int fid=prefs.getInt("nbw_v2_manual_fid", 0), typeid=prefs.getInt("nbw_v2_manual_typeid", 0);
				return "manual".equals(prefs.getString("nbw_v2_classification", "ai")) && fid>0 ? NBWSelection.manual(fid, typeid) : NBWSelection.ai();
			}
			public void save(NBWSelection value){
				session.getRawLocalPreferences().edit().putString("nbw_v2_classification", value.mode)
						.putInt("nbw_v2_manual_fid", value.fid==null ? 0 : value.fid).putInt("nbw_v2_manual_typeid", value.typeid==null ? 0 : value.typeid).apply();
			}
		}, ()->session.getCacheController().clearNBWRemoteCache(), UiUtils::runOnUiThread);
	}

	public synchronized Snapshot getSnapshot(){ return new Snapshot(state, capabilities, options, selection, consentSaving, generation); }
	public synchronized Cancelable observe(Consumer<Snapshot> observer){
		observers.add(observer); observer.accept(getSnapshot());
		return ()->{ synchronized(NBWForumOptionsRepository.this){ observers.remove(observer); } };
	}
	private void emit(){
		Snapshot snapshot=getSnapshot();
		for(Consumer<Snapshot> observer:new ArrayList<>(observers)) dispatch.accept(()->{
			synchronized(NBWForumOptionsRepository.this){
				if(observers.contains(observer) && snapshot.generation==generation) observer.accept(snapshot);
			}
		});
	}
	public synchronized void activate(){ active=true; refreshIfExpired(); }
	public synchronized void deactivate(){
		active=false; invalidate(); capabilities=null; options=null; state=State.LOCAL_ONLY;
		lastAttempt=lastOptions=-TTL_MS; emit();
	}
	public synchronized void refreshIfExpired(){
		if(!active || capabilityLoading || clock.getAsLong()-lastAttempt<TTL_MS) return;
		refreshCapabilities();
	}
	/** Explicit connection/consent completion or app resume, never called by Compose opening. */
	public synchronized void refreshNow(){ if(active && !capabilityLoading) refreshCapabilities(); }
	private void refreshCapabilities(){
		long ticket=generation;
		capabilityLoading=true; lastAttempt=clock.getAsLong(); state=options==null ? State.LOADING : State.STALE; emit();
		capabilityCall=transport.capabilities(new Result<>(){
			public void success(NBWCapabilities value){ synchronized(NBWForumOptionsRepository.this){
				if(!current(ticket)) return;
				capabilityLoading=false; capabilityCall=null;
				try{ value.postprocess(); }catch(Exception error){ localOnly(); return; }
				String oldMode=capabilities==null ? "local_only" : capabilities.remoteMode;
				if(!oldMode.equals(value.remoteMode)){
					invalidate(); ticketModeChanged(value); return;
				}
				applyCapabilities(value);
			} }
			public void failure(){ synchronized(NBWForumOptionsRepository.this){ if(current(ticket)){ capabilityLoading=false; capabilityCall=null; localOnly(); } } }
		});
	}
	private void ticketModeChanged(NBWCapabilities value){
		options=null; lastOptions=-TTL_MS; applyCapabilities(value);
	}
	private void applyCapabilities(NBWCapabilities value){
		capabilities=value;
		if(!value.canRead()){ options=null; state=State.LOCAL_ONLY; emit(); return; }
		if(options==null || clock.getAsLong()-lastOptions>=TTL_MS) loadOptions();
		else{ state=State.READY; emit(); }
	}
	private void loadOptions(){
		if(!active || optionsLoading || capabilities==null || !capabilities.canRead()) return;
		long ticket=generation; optionsLoading=true; state=options==null ? State.LOADING : State.STALE; emit();
		optionsCall=transport.options(new Result<>(){
			public void success(NBWForumOptions value){ synchronized(NBWForumOptionsRepository.this){
				if(!current(ticket)) return;
				optionsLoading=false; optionsCall=null;
				try{ value.postprocess(); }catch(Exception error){ localOnly(); return; }
				options=value; lastOptions=clock.getAsLong(); state=State.READY; emit();
			} }
			public void failure(){ synchronized(NBWForumOptionsRepository.this){ if(current(ticket)){ optionsLoading=false; optionsCall=null; localOnly(); } } }
		});
	}
	private boolean current(long ticket){ return active && generation==ticket; }
	private void localOnly(){ invalidate(); capabilities=null; options=null; state=State.LOCAL_ONLY; emit(); }
	private void invalidate(){
		generation++;
		if(capabilityCall!=null) capabilityCall.cancel(); if(optionsCall!=null) optionsCall.cancel(); if(consentCall!=null) consentCall.cancel();
		capabilityCall=optionsCall=consentCall=null; capabilityLoading=optionsLoading=consentSaving=false;
		// Clear ownership before cancel: production requests enqueue their terminal UI cleanup,
		// which must be allowed to finish/remove only that request even after this generation ends.
		ArrayList<Cancelable> canceledRequests=new ArrayList<>(remoteRequests);
		remoteRequests.clear();
		for(Cancelable request:canceledRequests) request.cancel();
		invalidateRemote.run();
	}
	public synchronized boolean select(NBWSelection value){
		if(value==null || value.isManual() && !value.isLegal(options)) return false;
		selection=value; store.save(value); emit(); return true;
	}
	/** Only a user's affirmative/negative control calls this. No implicit default acceptance. */
	public synchronized void setFallbackConsent(boolean accepted, Consumer<Boolean> done){
		if(!active || consentSaving || capabilities==null || !capabilities.canWrite()){ done.accept(false); return; }
		long ticket=generation; consentSaving=true; emit();
		consentCall=transport.consent(accepted, new Result<>(){
			public void success(Object ignored){ synchronized(NBWForumOptionsRepository.this){
				if(!current(ticket)) return;
				consentCall=null; consentSaving=false;
				// Re-read authenticated state. A local boolean is not authoritative consent.
				done.accept(true); refreshNow(); emit();
			} }
			public void failure(){ synchronized(NBWForumOptionsRepository.this){ if(current(ticket)){ consentCall=null; consentSaving=false; done.accept(false); emit(); } } }
		});
	}
	public synchronized long registerRemote(Cancelable request, boolean write){
		if(!active || capabilities==null || !(write ? capabilities.canWrite() : capabilities.canRead())) return -1;
		remoteRequests.add(request); return generation;
	}
	public synchronized boolean isCurrentRemote(long ticket){ return current(ticket) && capabilities!=null && capabilities.canRead(); }
	public synchronized void finishRemote(Cancelable request){ remoteRequests.remove(request); }
}
