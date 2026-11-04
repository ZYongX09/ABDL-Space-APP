package org.joinmastodon.android.api.session;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.joinmastodon.android.api.requests.nbw.GetNBWEndpoints;
import org.joinmastodon.android.api.requests.nbw.NBWLoginRequest;
import org.joinmastodon.android.model.NBWEndpoints;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** One public, identity-free snapshot. Session grants remain in the account-owned repository. */
public final class NBWEndpointsRepository{
	public interface Transport{ NBWForumOptionsRepository.Cancelable load(NBWForumOptionsRepository.Result<NBWEndpoints> result); }
	public static final class Snapshot{
		public final NBWEndpoints endpoints;
		public final long generation;
		public final boolean loading, fresh;
		Snapshot(NBWEndpoints endpoints,long generation,boolean loading,boolean fresh){ this.endpoints=endpoints; this.generation=generation; this.loading=loading; this.fresh=fresh; }
		public boolean canRead(){ return fresh && endpoints!=null && endpoints.canRead(); }
		public boolean canWrite(){ return fresh && endpoints!=null && endpoints.canWrite(); }
	}
	private static NBWEndpointsRepository instance;
	public static synchronized NBWEndpointsRepository getInstance(){
		if(instance==null) instance=new NBWEndpointsRepository(result->{
			GetNBWEndpoints request=new GetNBWEndpoints();
			request.setCallback(new Callback<>(){
				@Override public void onSuccess(NBWEndpoints value){ result.success(value); }
				@Override public void onError(ErrorResponse error){ result.failure(); }
			}).execNoAuth(NBWLoginRequest.DOMAIN);
			return request::cancel;
		},SystemClock::elapsedRealtime,UiUtils::runOnUiThread);
		return instance;
	}
	private final Transport transport;
	private final LongSupplier clock;
	private final Consumer<Runnable> dispatch;
	private final Handler handler=new Handler(Looper.getMainLooper());
	private final Set<Consumer<Snapshot>> observers=new HashSet<>();
	private final ArrayList<Consumer<Snapshot>> waiters=new ArrayList<>();
	private NBWEndpoints endpoints;
	private long generation=1, expiresAt, retryAfter;
	private boolean loading;
	private final Set<Object> foregroundOwners=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private final Runnable expiry=()->refreshNow();
	private final Runnable deadline=this::onDeadline;
	private void onDeadline(){
		synchronized(this){ if(endpoints==null || clock.getAsLong()<expiresAt) return; }
		emit(); // Absolute expiry is never extended by a pending or failed renewal.
	}
	private void scheduleLocked(){
		handler.removeCallbacks(expiry); handler.removeCallbacks(deadline);
		if(foregroundOwners.isEmpty() || observers.isEmpty()) return;
		long remaining=expiresAt-clock.getAsLong();
		if(endpoints!=null && remaining>0){
			handler.postDelayed(deadline,remaining);
			if(!loading) handler.postDelayed(expiry,Math.max(1,Math.max(remaining-1000,retryAfter-clock.getAsLong())));
		}else if(!loading) handler.postDelayed(expiry,30000);
	}

	public NBWEndpointsRepository(Transport transport,LongSupplier clock,Consumer<Runnable> dispatch){ this.transport=transport; this.clock=clock; this.dispatch=dispatch; }
	public synchronized Snapshot getSnapshot(){ return new Snapshot(endpoints,generation,loading,endpoints!=null && clock.getAsLong()<expiresAt); }
	public NBWForumOptionsRepository.Cancelable observe(Consumer<Snapshot> observer){
		Snapshot snapshot;
		synchronized(this){ observers.add(observer); snapshot=getSnapshot(); scheduleLocked(); }
		observer.accept(snapshot);
		return ()->{ synchronized(NBWEndpointsRepository.this){ observers.remove(observer); if(observers.isEmpty()){ handler.removeCallbacks(expiry); handler.removeCallbacks(deadline); } } };
	}
	private void emit(){
		Snapshot value; ArrayList<Consumer<Snapshot>> listeners;
		synchronized(this){ value=getSnapshot(); listeners=new ArrayList<>(observers); }
		for(Consumer<Snapshot> observer:listeners) dispatch.accept(()->{
			// Never call session code with the public-repository monitor held (session -> public is a valid lock order).
			boolean deliver;
			synchronized(NBWEndpointsRepository.this){ deliver=observers.contains(observer) && value.generation==generation; }
			if(deliver) observer.accept(value);
		});
	}
	public void enterForeground(Object owner){
		boolean added;
		synchronized(this){ added=foregroundOwners.add(owner); }
		if(added) refreshNow();
	}
	public synchronized void leaveForeground(Object owner){ foregroundOwners.remove(owner); if(foregroundOwners.isEmpty()){ handler.removeCallbacks(expiry); handler.removeCallbacks(deadline); } }
	public void refreshIfExpired(){ if(!getSnapshot().fresh) refreshNow(); }
	public void refreshNow(){ refreshNow(null); }
	/** Forced authorization preflight; never replays an OAuth POST. Concurrent callers share this GET only. */
	public void refreshNow(Consumer<Snapshot> done){
		synchronized(this){
			if(done!=null) waiters.add(done);
			if(loading) return;
			loading=true; handler.removeCallbacks(expiry);
		}
		emit();
		transport.load(new NBWForumOptionsRepository.Result<>(){
			@Override public void success(NBWEndpoints value){
				try{ value.postprocess(); complete(value); }catch(Exception invalid){ complete(null); }
			}
			@Override public void failure(){ complete(null); }
		});
	}
	private void complete(NBWEndpoints value){
		ArrayList<Consumer<Snapshot>> callbacks; Snapshot snapshot;
		synchronized(this){
			if(!loading) return;
			// A failed early renewal may use only the original, still-live lease. Never invent primary or extend it.
			if(value!=null || endpoints==null || clock.getAsLong()>=expiresAt){
				if(!java.util.Objects.equals(endpoints,value)) generation++;
				endpoints=value;
				if(value!=null) expiresAt=clock.getAsLong()+value.ttlSeconds*1000L;
			}
			loading=false; retryAfter=value==null ? clock.getAsLong()+30000 : 0;
			callbacks=new ArrayList<>(waiters); waiters.clear(); snapshot=getSnapshot(); scheduleLocked();
			if(value==null) snapshot=new Snapshot(endpoints,generation,false,false); // Authorization preflight failure is not permission to POST.
		}
		emit();
		Snapshot completed=snapshot;
		for(Consumer<Snapshot> callback:callbacks) dispatch.accept(()->callback.accept(completed));
	}
}
