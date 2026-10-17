package org.joinmastodon.android.sponsors;

import org.joinmastodon.android.model.sponsors.SponsorModels.ThanksItem;
import org.joinmastodon.android.model.sponsors.SponsorModels.ThanksPage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Main-thread state machine. A refresh replaces the loaded prefix atomically, never mixes rankings. */
public final class SponsorThanksController{
	public static final long REFRESH_MS=15000;
	public interface Cancelable{ void cancel(); }
	public interface Result{
		void success(ThanksPage page);
		void failure(String message);
	}
	public interface Source{ Cancelable load(int offset, Result result); }
	public interface Scheduler{
		void postDelayed(Runnable task, long delay);
		void remove(Runnable task);
	}
	private final Source source;
	private final Scheduler scheduler;
	private final BooleanSupplier sessionValid;
	private final Runnable changed;
	private final List<ThanksItem> items=new ArrayList<>(), staging=new ArrayList<>();
	private Cancelable request;
	private long generation;
	private boolean visible, destroyed, loading, refreshing, retryRefresh;
	private int total, stagedTotal, target, revision;
	private String error;
	private final Runnable tick=new Runnable(){
		@Override public void run(){
			if(!visible || destroyed) return;
			if(!loading) refresh();
			if(visible && !destroyed) scheduler.postDelayed(this, REFRESH_MS);
		}
	};

	public SponsorThanksController(Source source, Scheduler scheduler, BooleanSupplier sessionValid, Runnable changed){
		this.source=source; this.scheduler=scheduler; this.sessionValid=sessionValid; this.changed=changed;
	}
	public List<ThanksItem> items(){ return Collections.unmodifiableList(items); }
	public boolean loading(){ return loading; }
	public boolean hasMore(){ return items.size()<total; }
	public String error(){ return error; }
	public int revision(){ return revision; }

	public void show(){
		if(destroyed || visible) return;
		visible=true;
		refresh();
		if(visible) scheduler.postDelayed(tick, REFRESH_MS);
	}
	public void hide(){
		visible=false;
		scheduler.remove(tick);
		invalidate();
	}
	public void destroy(){ hide(); destroyed=true; items.clear(); staging.clear(); }
	private void invalidate(){
		generation++;
		Cancelable old=request; request=null;
		loading=false; refreshing=false; staging.clear();
		if(old!=null) old.cancel();
	}
	private boolean ready(){
		if(!visible || destroyed) return false;
		if(sessionValid.getAsBoolean()) return true;
		hide(); items.clear(); total=0; revision++;
		error="登录状态已变化，请返回关于页后重试"; changed.run(); return false;
	}
	public void refresh(){
		if(!ready()) return;
		invalidate();
		refreshing=true; loading=true; retryRefresh=true; error=null;
		target=Math.max(100, items.size()); stagedTotal=-1;
		changed.run(); load(0);
	}
	public void more(){
		if(!ready() || loading || !hasMore()) return;
		loading=true; refreshing=false; retryRefresh=false; error=null;
		changed.run(); load(items.size());
	}
	public void retry(){ if(retryRefresh || items.isEmpty()) refresh(); else more(); }
	public void sponsorChanged(){ if(visible) refresh(); }

	private boolean live(long token){ return token==generation && loading && ready(); }
	private void load(int offset){
		long token=++generation;
		Cancelable next=source.load(offset, new Result(){
			@Override public void success(ThanksPage page){
				if(!live(token)) return;
				request=null;
				try{
					page.postprocess();
					if(page.offset!=offset || page.limit!=100) throw new IllegalArgumentException();
					List<ThanksItem> prefix=refreshing ? staging : items;
					int expectedTotal=refreshing ? stagedTotal : total;
					if(expectedTotal>=0 && expectedTotal!=page.total) throw new IllegalArgumentException();
					Set<String> ids=new HashSet<>();
					for(ThanksItem item:prefix) ids.add(item.accountId);
					long previous=prefix.isEmpty() ? Long.MAX_VALUE : prefix.get(prefix.size()-1).totalAmountCents;
					for(ThanksItem item:page.items){
						if(!ids.add(item.accountId) || item.totalAmountCents>previous) throw new IllegalArgumentException();
						previous=item.totalAmountCents;
					}
				}catch(Exception invalid){
					retryRefresh=true;
					fail("感谢榜已更新或响应无效，请重试刷新"); return;
				}
				if(refreshing){
					stagedTotal=page.total; staging.addAll(page.items);
					if(staging.size()<Math.min(target, stagedTotal)){
						load(staging.size()); return;
					}
					items.clear(); items.addAll(staging); staging.clear(); total=stagedTotal;
				}else{
					items.addAll(page.items); total=page.total;
				}
				loading=false; refreshing=false; error=null; revision++; changed.run();
			}
			@Override public void failure(String message){ if(live(token)){ request=null; fail(message); } }
		});
		// Test sources (and cached transports) may complete synchronously.
		if(token==generation && loading) request=next;
		else if(next!=null) next.cancel();
	}
	private void fail(String message){
		loading=false; refreshing=false; staging.clear();
		error=message==null || message.isBlank() ? "感谢榜暂不可用，请重试" : message;
		changed.run();
	}
}
