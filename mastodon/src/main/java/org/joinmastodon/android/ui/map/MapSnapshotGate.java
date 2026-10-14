package org.joinmastodon.android.ui.map;

/** A late or duplicate vendor callback must not finish a newer snapshot request. */
public final class MapSnapshotGate{
	private long sequence,inFlight;
	public long begin(){
		if(inFlight!=0) return 0;
		inFlight=++sequence; return inFlight;
	}
	public boolean finish(long token){
		if(token==0 || token!=inFlight) return false;
		inFlight=0; return true;
	}
	public boolean busy(){ return inFlight!=0; }
	public void reset(){ inFlight=0; sequence++; }
}
