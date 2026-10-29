package org.joinmastodon.android.novel.app.design;

import org.joinmastodon.android.api.requests.statuses.CreateStatus;
import org.joinmastodon.android.model.NovelAttachment;
import java.util.Objects;

/** An attachment edit is tri-state: untouched, selected/replaced, or explicitly removed. */
public final class NovelComposeState{
	public String initialId, selectedId;
	public NovelAttachment preview;
	public boolean specified;
	public boolean hasNovel(){ return selectedId!=null && !selectedId.isBlank(); }
	public void initialize(String id,NovelAttachment data){ initialId=selectedId=id;preview=data;specified=false; }
	public void select(String id,NovelAttachment data){
		if(id==null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid novel identifier");
		selectedId=id;preview=data;specified=true;
	}
	public void remove(){ selectedId=null;preview=null;specified=true; }
	public boolean isChanged(){ return !Objects.equals(initialId,selectedId); }
	public void apply(CreateStatus.Request request){ if(specified) request.setNovel(selectedId); }
}
