package org.joinmastodon.android.sponsors;

import java.util.Objects;

/** Monotonic, foreground-only reading credit. Restored snapshots are always paused. */
public final class ForegroundReadTimer{
	private String fingerprint;
	private long required=5000, accumulated, started=-1, lastNow;
	private long clock(long now){ return lastNow=Math.max(lastNow, now); }
	public boolean configure(String value, int seconds, long now){
		Objects.requireNonNull(value);
		long duration=Math.max(5L, seconds)*1000L;
		clock(now);
		if(value.equals(fingerprint) && required==duration) return false;
		fingerprint=value; required=duration; accumulated=0; started=-1;
		return true;
	}
	public void visible(boolean visible, long now){
		now=clock(now);
		if(visible && fingerprint!=null && started<0) started=now;
		else if(!visible && started>=0){ accumulated=Math.min(required, accumulated+now-started); started=-1; }
	}
	public long elapsedMillis(long now){
		now=clock(now);
		return Math.min(required, accumulated+(started<0 ? 0 : now-started));
	}
	public long remainingMillis(long now){ return required-elapsedMillis(now); }
	public boolean ready(long now){ return fingerprint!=null && remainingMillis(now)==0; }
	public String fingerprint(){ return fingerprint; }
	public long requiredMillis(){ return required; }
	/** No wall-clock timestamp or running state is restored, so rotation/background adds no credit. */
	public void restore(String value, long duration, long elapsed){
		fingerprint=value; required=Math.max(5000, duration);
		accumulated=value==null ? 0 : Math.max(0, Math.min(required, elapsed)); started=-1; lastNow=0;
	}
	public void restart(){ fingerprint=null; accumulated=0; started=-1; }
}
