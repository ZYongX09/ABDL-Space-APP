package org.joinmastodon.android.verification;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayDeque;

/**
 * Presents ordered upload steps with a minimum on-screen time per step.
 * Real completion only ever advances the queue; the pacer never invents progress,
 * it only waits when a step finished faster than the readability floor.
 */
public final class VerificationStagePacer{
	public interface Callback{ void onStep(int step); }

	private final Handler handler=new Handler(Looper.getMainLooper());
	private final long minimumMs;
	private final Callback callback;
	private final ArrayDeque<Integer> queue=new ArrayDeque<>();
	private int shown=-1, target=-1;
	private long lastShownAt;
	private boolean running, disposed;

	public VerificationStagePacer(Callback callback){ this(callback, 1000L); }
	public VerificationStagePacer(Callback callback, long minimumMs){
		if(callback==null || minimumMs<0) throw new IllegalArgumentException();
		this.callback=callback; this.minimumMs=minimumMs;
	}

	/** Advance to a real step; intermediate steps are queued so none is skipped visually. */
	public void reach(int step){
		if(disposed || step<=target) return;
		for(int value=target+1; value<=step; value++) queue.add(value);
		target=step;
		if(!running) drain();
	}

	private void drain(){
		if(disposed) return;
		Integer next=queue.peek();
		if(next==null){ running=false; return; }
		long now=SystemClock.uptimeMillis();
		long delay=shown<0 ? minimumMs : Math.max(0, lastShownAt+minimumMs-now);
		running=true;
		handler.postDelayed(()->{
			if(disposed) return;
			Integer value=queue.poll();
			if(value==null){ running=false; return; }
			shown=value; lastShownAt=SystemClock.uptimeMillis();
			try{ callback.onStep(value); }
			finally{ drain(); }
		}, delay);
	}

	/** Current visible step, or -1 before the first one. */
	public int shownStep(){ return shown; }

	public void dispose(){
		disposed=true; running=false; queue.clear();
		handler.removeCallbacksAndMessages(null);
	}
}
