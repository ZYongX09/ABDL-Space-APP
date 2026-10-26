package org.joinmastodon.android.model;

import java.util.List;

/** Native destinations only. Names and legal subtype IDs still come from the live catalog. */
public final class NBWForumPolicy{
	public static final List<Integer> FORUM_IDS=List.of(18, 28, 27, 26, 3, 19);
	private NBWForumPolicy(){}
	public static boolean allows(Integer fid){ return fid!=null && FORUM_IDS.contains(fid); }
	public static boolean isLegal(NBWSelection selection, NBWForumOptions options){
		return !selection.isManual() || allows(selection.fid) && selection.isLegal(options);
	}
}
