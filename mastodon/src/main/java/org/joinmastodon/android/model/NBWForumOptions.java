package org.joinmastodon.android.model;

import org.joinmastodon.android.api.ObjectValidationException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Public display snapshot only; the server revalidates before any remote write. */
public class NBWForumOptions extends BaseModel{
	public Integer schemaVersion;
	public String configRevision;
	public List<Forum> forums;
	public Fallback fallback;

	@Override public void postprocess() throws ObjectValidationException{
		if(schemaVersion==null || schemaVersion!=1 || configRevision==null || configRevision.isBlank() || forums==null || forums.size()>256
				|| fallback==null || fallback.fid==null || fallback.fid!=18 || fallback.policyVersion==null || fallback.policyVersion!=1)
			throw new ObjectValidationException("Invalid NBW V2 options");
		Set<Integer> fids=new HashSet<>();
		for(Forum forum:forums){
			if(forum==null || forum.fid==null || forum.fid<=0 || !fids.add(forum.fid) || !validName(forum.name) || forum.required==null || forum.types==null || forum.types.size()>512)
				throw new ObjectValidationException("Invalid NBW forum");
			Set<Integer> types=new HashSet<>();
			for(Type type:forum.types){
				if(type==null || type.typeid==null || type.typeid<=0 || !types.add(type.typeid) || !validName(type.name) || type.moderatorOnly==null)
					throw new ObjectValidationException("Invalid NBW forum type");
			}
		}
	}

	private static boolean validName(String value){ return value!=null && !value.isBlank() && value.length()<=256; }
	public Forum findForum(Integer fid){
		if(fid!=null && forums!=null) for(Forum forum:forums) if(fid.equals(forum.fid)) return forum;
		return null;
	}
	public boolean isLegal(Integer fid, Integer typeid){
		Forum forum=findForum(fid);
		if(forum==null) return false;
		if(typeid==null || typeid==0) return Boolean.FALSE.equals(forum.required);
		return forum.types.stream().anyMatch(type->typeid.equals(type.typeid) && Boolean.FALSE.equals(type.moderatorOnly));
	}
	public boolean canFallback(){ return isLegal(18, 0); }
	public String label(Integer fid, Integer typeid){
		Forum forum=findForum(fid);
		if(forum==null) return null;
		if(typeid==null || typeid==0) return forum.name;
		for(Type type:forum.types) if(typeid.equals(type.typeid)) return forum.name+" / "+type.name;
		return null;
	}

	public static class Forum{ public Integer fid; public String name; public Boolean required; public List<Type> types; }
	public static class Type{ public Integer typeid; public String name; public Boolean moderatorOnly; }
	public static class Fallback{ public Integer fid, policyVersion; }
}
