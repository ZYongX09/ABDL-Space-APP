package org.joinmastodon.android.sponsors;

import android.content.Context;
import android.content.SharedPreferences;

import org.joinmastodon.android.BuildConfig;

import java.util.function.LongSupplier;

/** Only public recovery identity is durable. Tokens, signed orders and pairing codes are memory-only. */
final class SandboxSession{
	static final String IDENTITY_PREFS="sandbox_pair_identity";
	private final SharedPreferences identity, recovery;
	private final LongSupplier seconds;
	final String origin;
	private Lease lease;

	static final class Lease{
		final String owner, token;
		final long expiresAt;
		Lease(String owner, String token, long expiresAt){ this.owner=owner; this.token=token; this.expiresAt=expiresAt; }
	}

	SandboxSession(Context context){ this(context, BuildConfig.SANDBOX_ORIGIN, ()->System.currentTimeMillis()/1000); }
	SandboxSession(Context context, String origin, LongSupplier seconds){
		this.origin=SandboxProtocol.requireOrigin(origin);
		this.seconds=seconds;
		identity=context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE);
		recovery=context.getSharedPreferences("sponsor_payment_recovery", Context.MODE_PRIVATE);
	}
	long now(){ return seconds.getAsLong(); }
	static String owner(String origin, String run, String user){ return origin+"|"+run+"|"+user; }
	synchronized String owner(){
		String savedOrigin=identity.getString("origin", null), run=identity.getString("run_id", null), user=identity.getString("user_id", null);
		if(savedOrigin==null || run==null || user==null) return null;
		try{ SandboxProtocol.requireOrigin(savedOrigin); }catch(IllegalArgumentException invalid){ return null; }
		return SandboxProtocol.validRun(run) && "101".equals(user) ? owner(savedOrigin, run, user) : null;
	}
	synchronized String run(){ return identity.getString("run_id", ""); }
	synchronized String user(){ return identity.getString("user_id", ""); }
	synchronized boolean hasRecovery(){ return !recovery.getAll().isEmpty(); }
	synchronized Lease lease(){
		if(lease!=null && lease.expiresAt<=now()) lease=null;
		return lease;
	}
	synchronized boolean valid(Lease expected){ return expected!=null && lease()==expected; }
	synchronized void invalidate(Lease expected){ if(lease==expected) lease=null; }

	/** Failure never clears recovery or rewrites the original owner. Same-owner re-pair is normal. */
	synchronized String accept(SandboxProtocol.Pair pair){
		try{ SandboxProtocol.validatePair(pair, origin, now()); }
		catch(Exception invalid){ return "invalid_response"; }
		String old=owner(), next=owner(pair.origin, pair.runId, pair.userId);
		if(!identity.getAll().isEmpty() && old==null) return "storage_failed";
		if(hasRecovery() && !next.equals(old)) return "pending_owner_changed";
		// Do not strand an orphaned/malformed recovery owner by accepting a fresh identity.
		for(String key:recovery.getAll().keySet()) if(old==null || !key.startsWith("payment."+old+".")) return "storage_failed";
		if(!identity.edit().putString("origin", pair.origin).putString("run_id", pair.runId).putString("user_id", pair.userId).commit()) return "storage_failed";
		lease=new Lease(next, pair.accessToken, pair.expiresAt);
		return null;
	}
}
