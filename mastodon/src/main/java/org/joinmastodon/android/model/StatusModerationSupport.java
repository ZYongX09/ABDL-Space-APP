package org.joinmastodon.android.model;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Presentation and copy-on-write refresh helpers. The server remains the moderation authority. */
public final class StatusModerationSupport{
	private StatusModerationSupport(){}

	/** An exact server-stored notice is also safe after Parcel has intentionally omitted the DTO. */
	public static String notice(Status status){
		if(status==null) return null;
		if(status.moderation!=null && status.moderation.isSupported()) return status.moderation.notice;
		String body=status.content;
		if(body==null || !body.startsWith(StatusModeration.PREFIX) || !body.endsWith(StatusModeration.SUFFIX)) return null;
		String reason=body.substring(StatusModeration.PREFIX.length(), body.length()-StatusModeration.SUFFIX.length());
		return StatusModeration.validReason(reason) ? body : null;
	}
	public static boolean isBlocked(Status status){ return notice(status)!=null; }

	/** Replace every occurrence, including nested quotes and boosts, without editing shared objects. */
	public static Status replace(Status root, Status fresh){
		if(fresh==null || fresh.id==null) return root;
		return replace(root, fresh, Collections.newSetFromMap(new IdentityHashMap<>()));
	}
	private static Status replace(Status root, Status fresh, Set<Status> path){
		if(root==null || !path.add(root)) return root;
		try{
			if(fresh.id.equals(root.id)) return fresh.clone();
			Status reblog=replace(root.reblog, fresh, path);
			Status quoted=root.quote==null ? null : replace(root.quote.quotedStatus, fresh, path);
			if(reblog==root.reblog && (root.quote==null || quoted==root.quote.quotedStatus)) return root;
			Status copy=root.clone();
			copy.reblog=reblog;
			if(root.quote!=null && quoted!=root.quote.quotedStatus){
				copy.quote=(Quote)root.quote.clone();
				copy.quote.quotedStatus=quoted;
			}
			return copy;
		}finally{ path.remove(root); }
	}

	/** Collect only explicit current public moderation, never infer it from an unrefreshed cache. */
	public static void collect(Status root, java.util.Map<String, Status> blocked){
		collect(root, blocked, Collections.newSetFromMap(new IdentityHashMap<>()));
	}
	private static void collect(Status root, java.util.Map<String, Status> blocked, Set<Status> seen){
		if(root==null || !seen.add(root)) return;
		if(isBlocked(root)) blocked.put(root.id, root);
		collect(root.reblog, blocked, seen);
		if(root.quote!=null) collect(root.quote.quotedStatus, blocked, seen);
	}
}
