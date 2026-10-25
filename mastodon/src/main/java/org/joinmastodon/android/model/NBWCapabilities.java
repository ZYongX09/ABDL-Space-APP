package org.joinmastodon.android.model;

import org.joinmastodon.android.api.ObjectValidationException;

import java.util.Set;

/** Authenticated, first-party metadata. Never infer a grant from Account.nbwUsername. */
public class NBWCapabilities extends BaseModel{
	public Integer schemaVersion;
	public String remoteMode, reason, policyRevision;
	public Binding binding;
	public Authorization authorization;
	public Fallback fallback;

	@Override public void postprocess() throws ObjectValidationException{
		if(schemaVersion==null || schemaVersion!=1 || !Set.of("local_only", "read_only", "full").contains(remoteMode==null ? "" : remoteMode)
				|| reason==null || policyRevision==null || policyRevision.isBlank() || binding==null || authorization==null || fallback==null
				|| !Set.of("bound", "unbound", "unknown").contains(binding.state==null ? "" : binding.state)
				|| !Set.of("ready", "missing", "revoked", "unavailable").contains(authorization.state==null ? "" : authorization.state)
				|| !"non_expiring".equals(authorization.lifetimePolicy)
				|| fallback.fid==null || fallback.fid!=18 || fallback.policyVersion==null || fallback.policyVersion!=1 || fallback.consented==null)
			throw new ObjectValidationException("Invalid NBW V2 capabilities");
		if("bound".equals(binding.state) && (binding.uid==null || binding.uid.isBlank()))
			throw new ObjectValidationException("Missing NBW binding identity");
	}

	public boolean canRead(){ return "full".equals(remoteMode) || "read_only".equals(remoteMode); }
	public boolean canWrite(){ return "full".equals(remoteMode); }
	public boolean hasGrant(){ return "bound".equals(binding.state) && "ready".equals(authorization.state); }

	public static class Binding{ public String state, uid, username; }
	public static class Authorization{ public String state, lifetimePolicy; }
	public static class Fallback{ public Integer fid, policyVersion; public Boolean consented; }
}
