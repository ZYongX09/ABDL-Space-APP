package org.joinmastodon.android.model;

import java.util.Objects;

/** Stable IDs, never menu indices. AI is executed after local creation, not on private drafts. */
public final class NBWSelection{
	public final String mode;
	public final Integer fid, typeid;
	private NBWSelection(String mode, Integer fid, Integer typeid){ this.mode=mode; this.fid=fid; this.typeid=typeid; }
	public static NBWSelection ai(){ return new NBWSelection("ai", null, null); }
	public static NBWSelection manual(int fid, int typeid){ return new NBWSelection("manual", fid, typeid); }
	public boolean isManual(){ return "manual".equals(mode); }
	public boolean isLegal(NBWForumOptions options){ return !isManual() || options!=null && options.isLegal(fid, typeid); }
	@Override public boolean equals(Object other){ return other instanceof NBWSelection s && mode.equals(s.mode) && Objects.equals(fid, s.fid) && Objects.equals(typeid, s.typeid); }
	@Override public int hashCode(){ return Objects.hash(mode, fid, typeid); }
}
