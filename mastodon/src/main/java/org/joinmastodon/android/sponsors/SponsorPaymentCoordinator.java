package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.sponsors.SponsorPaymentRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;

import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** Page-owned main-thread checkout. SDK results are hints to query, never entitlement evidence. */
public final class SponsorPaymentCoordinator{
	public static final int MAX_QUERIES=4;
	public static final long QUERY_INTERVAL_MS=2000;
	public interface Cancelable{ void cancel(); }
	public interface Result<T>{ void success(T value); void failure(Failure error); }
	public static final class Failure{
		public final int status;
		public final String code;
		public Failure(int status, String code){ this.status=status; this.code=code==null ? "request_failed" : code; }
	}
	public interface Source{
		Cancelable catalog(Result<Catalog> result);
		Cancelable me(Result<Me> result);
		Cancelable options(String plan, int version, Result<Options> result);
		Cancelable create(String plan, int version, String operation, Result<Order> result);
		Cancelable current(Result<CurrentOrder> result);
		Cancelable order(String id, Result<Order> result);
		Cancelable close(String id, Result<Order> result);
	}
	/** Instance seam; production delegates to the SDK adapter, tests never call real pay. */
	public interface PaymentLauncher{
		boolean available();
		Cancelable pay(String orderString, Consumer<String> completed);
	}
	public interface Scheduler{
		long now();
		void post(Runnable task, long delay);
		void remove(Runnable task);
	}
	public interface Listener{
		void changed();
		void granted(Me freshlyConfirmedMe);
		void openAfdian();
	}
	/** The only persisted fields. Neither signed data nor pricing/entitlements belong here. */
	public static final class Pending{
		public final String owner, operation, orderId;
		public Pending(String owner, String operation, String orderId){ this.owner=owner; this.operation=operation; this.orderId=orderId; }
	}
	public interface Store{
		Pending read(String owner);
		boolean compareAndSet(String owner, Pending expected, Pending pending);
	}
	public static boolean samePending(Pending a, Pending b){
		return a==b || a!=null && b!=null && Objects.equals(a.owner, b.owner)
				&& Objects.equals(a.operation, b.operation) && Objects.equals(a.orderId, b.orderId);
	}
	public static final class PreferencesStore implements Store{
		private static final Object LOCK=new Object();
		private final SharedPreferences prefs;
		public PreferencesStore(Context context){ prefs=context.getSharedPreferences("sponsor_payment_recovery", Context.MODE_PRIVATE); }
		@Override public Pending read(String owner){ synchronized(LOCK){ return readLocked(owner); } }
		private Pending readLocked(String owner){
			String prefix="payment."+owner+".";
			if(!prefs.contains(prefix+"owner") && !prefs.contains(prefix+"operation") && !prefs.contains(prefix+"order")) return null;
			return new Pending(prefs.getString(prefix+"owner", null), prefs.getString(prefix+"operation", null), prefs.getString(prefix+"order", null));
		}
		@Override public boolean compareAndSet(String owner, Pending expected, Pending pending){
			synchronized(LOCK){
				if(!samePending(readLocked(owner), expected)) return false;
				String prefix="payment."+owner+".";
				SharedPreferences.Editor editor=prefs.edit().remove(prefix+"owner").remove(prefix+"operation").remove(prefix+"order");
				if(pending!=null){
					editor.putString(prefix+"owner", pending.owner);
					if(pending.operation!=null) editor.putString(prefix+"operation", pending.operation);
					if(pending.orderId!=null) editor.putString(prefix+"order", pending.orderId);
				}
				return editor.commit();
			}
		}
	}
	public static final class HttpSource implements Source{
		private final String account;
		public HttpSource(String account){ this.account=account; }
		private <T> Cancelable send(MastodonAPIRequest<T> request, Result<T> result){
			request.setCallback(new Callback<>(){
				@Override public void onSuccess(T value){ result.success(value); }
				@Override public void onError(ErrorResponse error){
					result.failure(new Failure(error instanceof MastodonErrorResponse e ? e.httpStatus : -1,
							error instanceof SponsorRequest.SponsorError e ? e.code : "request_failed"));
				}
			}).exec(account);
			return request::cancel;
		}
		@Override public Cancelable catalog(Result<Catalog> result){ return send(SponsorRequest.catalog(), result); }
		@Override public Cancelable me(Result<Me> result){ return send(SponsorRequest.me(), result); }
		@Override public Cancelable options(String plan, int version, Result<Options> result){ return send(SponsorPaymentRequest.options(plan, version), result); }
		@Override public Cancelable create(String plan, int version, String operation, Result<Order> result){ return send(SponsorPaymentRequest.create(plan, version, operation), result); }
		@Override public Cancelable current(Result<CurrentOrder> result){ return send(SponsorPaymentRequest.current(), result); }
		@Override public Cancelable order(String id, Result<Order> result){ return send(SponsorPaymentRequest.order(id), result); }
		@Override public Cancelable close(String id, Result<Order> result){ return send(SponsorPaymentRequest.close(id), result); }
	}
	public static Scheduler scheduler(Handler handler){
		return new Scheduler(){
			@Override public long now(){ return SystemClock.elapsedRealtime(); }
			@Override public void post(Runnable task, long delay){ handler.postDelayed(task, delay); }
			@Override public void remove(Runnable task){ handler.removeCallbacks(task); }
		};
	}
	public static PaymentLauncher launcher(Activity activity){
		return new PaymentLauncher(){
			@Override public boolean available(){ return AlipaySdkAdapter.available(activity); }
			@Override public Cancelable pay(String value, Consumer<String> completed){
				AlipaySdkAdapter.Cancelable call=AlipaySdkAdapter.pay(activity, value, completed::accept);
				return call::cancel;
			}
		};
	}

	private final String owner, planId;
	private final Source source;
	private final PaymentLauncher launcher;
	private final Store store;
	private final Scheduler scheduler;
	private final BooleanSupplier ownerValid;
	private final Listener listener;
	private Catalog catalog;
	private Plan plan;
	private Me me;
	private Options options;
	private Order order;
	private Pending pending;
	private Cancelable request, sdkCall;
	private boolean active, destroyed, busy, sdkActive, unresolved, fallback, fresh, requireChoice, confirmed, storageFailed;
	private boolean releaseRejectedCreation;
	private String selected, problem;
	private long generation, sdkGeneration, lastQuery=-QUERY_INTERVAL_MS;
	private int queries;
	private final Runnable queryTask=this::query;

	public SponsorPaymentCoordinator(String owner, String planId, Source source, PaymentLauncher launcher, Store store,
			Scheduler scheduler, BooleanSupplier ownerValid, Listener listener){
		this.owner=owner; this.planId=planId; this.source=source; this.launcher=launcher; this.store=store;
		this.scheduler=scheduler; this.ownerValid=ownerValid; this.listener=listener;
		try{
			pending=store.read(owner);
			if(!validPending(pending)) storageFailed=true;
		}catch(RuntimeException invalid){ storageFailed=true; }
		unresolved=pending!=null || storageFailed;
	}
	private boolean validPending(Pending value){
		return value==null || owner.equals(value.owner) && (value.operation!=null || value.orderId!=null)
				&& (value.operation==null || SponsorPaymentRequest.validOperation(value.operation))
				&& (value.orderId==null || SponsorPaymentModels.validOrderId(value.orderId));
	}
	public Plan plan(){ return plan; }
	public Order order(){ return order; }
	public String selected(){ return selected; }
	public String problem(){ return problem; }
	public boolean busy(){ return busy; }
	public boolean sdkActive(){ return sdkActive; }
	public boolean confirmed(){ return confirmed; }
	public boolean fallback(){ return fallback; }
	public boolean blocking(){ return unresolved || sdkActive || order!=null && !"CLOSED".equals(order.paymentState) && !confirmed; }
	public boolean canSwitch(){ return !busy && !sdkActive && order!=null
			&& "PENDING_PAYMENT".equals(order.paymentState) && "PENDING".equals(order.fulfillmentState); }
	public boolean showAlipay(){
		return !fallback && (options==null || options.channel("alipay")==null || !"amount_unsupported".equals(options.channel("alipay").reason));
	}
	public String disabledReason(String channel){
		if(storageFailed) return "storage_failed";
		if(!fresh) return problem==null ? "checking" : problem;
		if(me==null || me.sponsor.permanent) return "already_permanent";
		if(catalog==null || !Boolean.TRUE.equals(catalog.config.enabled) || plan==null || !plan.enabled) return "disabled";
		if("afdian".equals(channel)){
			if(!SponsorPurchaseSafety.valid(plan)) return "not_configured";
			if(fallback) return null;
		}else{
			if(fallback) return "not_supported";
			if(!launcher.available()) return "wallet_unavailable";
		}
		Channel option=options==null ? null : options.channel(channel);
		return option==null ? "checking" : Boolean.TRUE.equals(option.available) ? null : option.reason;
	}
	public boolean canSelect(String channel){ return !destroyed && !busy && !blocking() && !confirmed && disabledReason(channel)==null; }
	public boolean canPay(){ return active && canSelect(selected==null ? "" : selected) && ("alipay".equals(selected) || "afdian".equals(selected)); }

	public void resume(){
		if(!valid()) return;
		active=true;
		if(busy) return;
		if(catalog==null && !confirmed) loadFresh(false); else if(blocking()) beginQuery(); else if(!confirmed) loadFresh(false);
	}
	/** SDK opens another Activity: do not suppress its successful callback just because of pause. */
	public void pause(){ active=false; scheduler.remove(queryTask); }
	public void destroy(){
		if(destroyed) return;
		destroyed=true; active=false; generation++; sdkGeneration++; scheduler.remove(queryTask);
		if(request!=null) request.cancel(); request=null;
		if(sdkCall!=null) sdkCall.cancel(); sdkCall=null; sdkActive=false;
	}
	private boolean valid(){
		if(destroyed) return false;
		if(storageFailed){ problem="storage_failed"; listener.changed(); return false; }
		if(ownerValid.getAsBoolean()) return true;
		destroy(); problem="session_changed"; fresh=false; selected=null; listener.changed(); return false;
	}
	private boolean live(long token){ return token==generation && valid() && recoveryCurrent(); }
	private boolean recoveryCurrent(){
		try{ if(samePending(store.read(owner), pending)) return true; }
		catch(RuntimeException ignored){}
		storageConflict(); return false;
	}
	private void storageConflict(){
		storageFailed=true; unresolved=true; fresh=false; selected=null; busy=false;
		generation++; sdkGeneration++; scheduler.remove(queryTask);
		if(request!=null) request.cancel(); request=null;
		if(sdkCall!=null) sdkCall.cancel(); sdkCall=null; sdkActive=false;
		error("storage_failed");
	}
	private <T> void call(Function<Result<T>, Cancelable> start, Consumer<T> success, Consumer<Failure> failure){
		if(!valid()) return;
		long token=++generation; busy=true; listener.changed();
		Cancelable next=start.apply(new Result<>(){
			@Override public void success(T value){ if(live(token)){ request=null; busy=false; success.accept(value); } }
			@Override public void failure(Failure error){ if(live(token)){ request=null; busy=false; failure.accept(error); } }
		});
		if(token==generation && busy && !destroyed) request=next; else if(next!=null) next.cancel();
	}
	private void error(String code){ problem=code; listener.changed(); }
	private boolean persist(Pending value){
		try{
			if(!store.compareAndSet(owner, pending, value)){ storageConflict(); return false; }
			pending=value; storageFailed=false; return true;
		}catch(RuntimeException failure){ storageFailed=true; unresolved=true; error("storage_failed"); return false; }
	}
	private void loadFresh(boolean legacy){
		if(!valid() || busy) return;
		fresh=false; options=null; fallback=legacy; problem="checking"; listener.changed();
		call(source::catalog, (Catalog value)->{
			try{ value.postprocess(); }catch(Exception invalid){ error("invalid_response"); return; }
			catalog=value; plan=value.findPlan(planId);
			call(source::me, (Me state)->{
				try{ state.postprocess(); }catch(Exception invalid){ error("invalid_response"); return; }
				if(state.configVersion!=value.config.version){ error("plan_changed"); return; }
				me=state;
				if(blocking()){ beginQuery(); return; }
				if(plan==null || !SponsorPaymentModels.validPlan(plan)){ error("plan_changed"); return; }
				if(legacy){ fresh=true; problem="legacy_backend"; chooseDefault(); listener.changed(); return; }
				if(!Boolean.TRUE.equals(value.config.enabled) || state.sponsor.permanent){
					fresh=true; error(state.sponsor.permanent ? "already_permanent" : "disabled"); return;
				}
				loadOptions();
			}, failure->error(failure.status==401 ? "session_changed" : failure.code));
		}, failure->error(failure.status==401 ? "session_changed" : failure.code));
	}
	private void loadOptions(){
		Plan expected=plan;
		call(result->source.options(expected.id, expected.version, result), (Options value)->{
			try{ value.postprocess(); }catch(Exception invalid){ error("invalid_response"); return; }
			if(!SponsorPaymentModels.samePlan(expected, value.plan)){ error("plan_changed"); return; }
			options=value; plan=value.plan; fresh=true; fallback=false; problem=null;
			if(value.activeOrder!=null){ accept(value.activeOrder, false); beginQuery(); return; }
			chooseDefault(); listener.changed();
		}, failure->{
			if(failure.status==404 || failure.status==501 || failure.status>=500 || failure.status<=0){
				// A cached catalogue is not fallback proof. Re-fetch catalogue AND Me before showing Afdian.
				loadFresh(true);
			}else error(failure.status==401 ? "session_changed" : failure.code);
		});
	}
	private void chooseDefault(){
		if(requireChoice){ selected=null; return; }
		if(selected!=null && disabledReason(selected)==null) return;
		selected=showAlipay() && disabledReason("alipay")==null ? "alipay" : disabledReason("afdian")==null ? "afdian" : null;
	}
	public void select(String channel){
		if(valid() && canSelect(channel) && Set.of("alipay", "afdian").contains(channel)){
			selected=channel; requireChoice=false; problem=fallback ? "legacy_backend" : null; listener.changed();
		}
	}
	public void retry(){
		if(!valid() || busy) return;
		if(blocking() || pending!=null) beginQuery(); else if(order!=null && "GRANTED".equals(order.fulfillmentState)) confirmMe(); else loadFresh(false);
	}
	public void pay(){
		if(!valid() || !canPay()) return;
		// Two pages for the same owner cannot start two purchase operations.
		Pending stored;
		try{ stored=store.read(owner); }catch(RuntimeException failure){ error("storage_failed"); return; }
		if(stored!=null){
			if(!validPending(stored)){ storageConflict(); return; }
			pending=stored; unresolved=true; beginQuery(); return;
		}
		if("afdian".equals(selected)){ listener.openAfdian(); return; }
		if(fallback || options==null || !Boolean.TRUE.equals(options.channel("alipay").available)) return;
		Plan expected=plan;
		String operation=UUID.randomUUID().toString();
		if(!persist(new Pending(owner, operation, null))) return;
		order=null; unresolved=true; fresh=false; problem="creating";
		call(result->source.create(expected.id, expected.version, operation, result), (Order value)->{
			if(!expected.id.equals(value.planId) || expected.version!=value.planVersion || expected.priceMinor!=value.amountMinor || !expected.currency.equals(value.currency)){
				value.orderStr=null; error("invalid_response"); beginQuery(); return;
			}
			accept(value, true);
		}, failure->{
			releaseRejectedCreation=failure.status>=400 && failure.status<500 && failure.status!=429
					&& Set.of("payment_unavailable", "limits_unverified", "amount_unsupported", "plan_changed", "already_permanent", "sponsors_disabled").contains(failure.code);
			problem=failure.code; beginQuery();
		});
	}
	public void switchChannel(){
		if(!valid() || !canSwitch()) return;
		requireChoice=true; selected=null; problem="closing";
		String expected=order.orderId;
		call(result->source.close(expected, result), (Order value)->{
			if(!expected.equals(value.orderId)){ value.orderStr=null; error("invalid_response"); return; }
			accept(value, false);
		}, failure->{ error(failure.code); beginQuery(); });
	}
	private void accept(Order value, boolean mayLaunch){
		String signed=value.orderStr; value.orderStr=null;
		try{ value.postprocess(); }catch(Exception invalid){ error("invalid_response"); return; }
		if(pending!=null && pending.orderId!=null && !pending.orderId.equals(value.orderId)){ error("invalid_response"); return; }
		order=value; problem=null;
		if("GRANTED".equals(value.fulfillmentState)){
			unresolved=false; scheduler.remove(queryTask); confirmMe(); return;
		}
		if("CLOSED".equals(value.paymentState)){
			if(!Boolean.TRUE.equals(value.canSwitchChannel)){ unresolved=true; error("payment_result_unknown"); return; }
			unresolved=false; scheduler.remove(queryTask); requireChoice=true; selected=null;
			if(!persist(null)) return;
			loadFresh(fallback); return;
		}
		unresolved=true;
		boolean durable=persist(new Pending(owner, pending==null ? null : pending.operation, value.orderId));
		if(!durable) return;
		if("MANUAL_REVIEW".equals(value.fulfillmentState) || "REFUND_REVIEW".equals(value.fulfillmentState)){
			scheduler.remove(queryTask); listener.changed(); return;
		}
		if(durable && mayLaunch && active && !sdkActive && "PENDING_PAYMENT".equals(value.paymentState)
				&& signed!=null && !signed.isBlank() && launcher.available()){
			launch(signed); return;
		}
		listener.changed(); scheduleQuery();
	}
	private void launch(String signed){
		if(!valid() || sdkActive) return;
		sdkActive=true; long token=++sdkGeneration; listener.changed();
		Cancelable next=launcher.pay(signed, ignoredStatus->{
			if(token!=sdkGeneration || !sdkActive || !valid() || !recoveryCurrent()) return;
			sdkActive=false; sdkCall=null;
			// 9000, cancellation, processing and UNKNOWN all follow exactly the same server reconciliation.
			listener.changed(); beginQuery();
		});
		if(token==sdkGeneration && sdkActive && !destroyed) sdkCall=next; else if(next!=null) next.cancel();
	}
	private void beginQuery(){
		if(!valid()) return;
		queries=0; scheduler.remove(queryTask);
		if(!busy) query();
	}
	private void query(){
		if(!valid() || busy || confirmed || queries>=MAX_QUERIES) return;
		if(scheduler.now()-lastQuery<QUERY_INTERVAL_MS){
			if(active) scheduler.post(queryTask, QUERY_INTERVAL_MS-(scheduler.now()-lastQuery)); return;
		}
		queries++; lastQuery=scheduler.now(); problem="checking_order";
		if(!recoveryCurrent()) return;
		String id=pending==null ? null : pending.orderId;
		if(id!=null){
			call(result->source.order(id, result), (Order value)->{
				if(!id.equals(value.orderId)){ value.orderStr=null; error("invalid_response"); return; }
				accept(value, false);
			}, failure->{ error(failure.status==401 ? "session_changed" : "payment_result_unknown"); });
		}else{
			call(source::current, (CurrentOrder value)->{
				try{ value.postprocess(); }catch(Exception invalid){ error("invalid_response"); return; }
				if(value.order!=null){ releaseRejectedCreation=false; accept(value.order, false); }
				else if(releaseRejectedCreation){
					releaseRejectedCreation=false; unresolved=false;
					if(persist(null)) loadFresh(false);
				}else{
					// A lost POST response plus an empty current read does not prove that no charge is in flight.
					unresolved=true; error("payment_result_unknown");
				}
			}, failure->error(failure.status==401 ? "session_changed" : "payment_result_unknown"));
		}
	}
	private void scheduleQuery(){
		scheduler.remove(queryTask);
		if(active && !busy && !confirmed && queries<MAX_QUERIES && order!=null
				&& !"CLOSED".equals(order.paymentState) && "PENDING".equals(order.fulfillmentState))
			scheduler.post(queryTask, QUERY_INTERVAL_MS);
	}
	private void confirmMe(){
		if(!valid() || busy || confirmed || order==null || !"GRANTED".equals(order.fulfillmentState)) return;
		problem="confirming_membership";
		call(source::me, (Me value)->{
			try{ value.postprocess(); }catch(Exception invalid){ error("invalid_response"); return; }
			if(!value.sponsor.isActive()){ error("membership_refresh_pending"); return; }
			if(!persist(null)) return;
			me=value; confirmed=true; unresolved=false; fresh=false; problem=null; selected=null;
			listener.granted(value); listener.changed();
		}, failure->error("membership_refresh_pending"));
	}
}
