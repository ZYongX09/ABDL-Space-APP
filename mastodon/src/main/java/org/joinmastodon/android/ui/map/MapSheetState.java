package org.joinmastodon.android.ui.map;

public final class MapSheetState{
	public enum Page{ NEARBY, PERSON, SETTINGS, VISIBILITY, PRECISION, PROVIDER, CONSENT, PIN }
	public enum Height{ COLLAPSED, HALF, EXPANDED }
	private Page page=Page.NEARBY;
	private Height height=Height.EXPANDED;
	private boolean consentRequired;

	public Page page(){ return page; }
	public Height height(){ return height; }
	public boolean consentRequired(){ return consentRequired; }
	/** Lock navigation and all height changes until current-version consent is accepted. */
	public void setConsentRequired(boolean required){
		consentRequired=required;
		if(required){ page=Page.CONSENT; height=Height.EXPANDED; }
	}
	public void open(Page page){
		if(consentRequired && page!=Page.CONSENT) return;
		this.page=page;
		height=page==Page.PERSON || page==Page.PIN ? Height.HALF : Height.EXPANDED;
	}
	public void setHeight(Height height){ this.height=consentRequired?Height.EXPANDED:height; }
	public boolean back(){
		if(consentRequired) return true;
		if(page!=Page.NEARBY){ page=Page.NEARBY; height=Height.HALF; return true; }
		if(height!=Height.COLLAPSED){ height=Height.COLLAPSED; return true; }
		return false;
	}
	/** Only an explicitly half-open nearby sheet yields to a user map gesture. */
	public boolean onMapDragStarted(){
		if(consentRequired || page!=Page.NEARBY || height!=Height.HALF) return false;
		height=Height.COLLAPSED;
		return true;
	}
	public static int panelHeight(int availableHeight,int collapsedHeight,Height state){
		int min=Math.min(Math.max(0,availableHeight),Math.max(0,collapsedHeight));
		return switch(state){
			case COLLAPSED -> min;
			case HALF -> Math.max(min,Math.round(availableHeight*.43f));
			case EXPANDED -> Math.max(min,availableHeight);
		};
	}
	public static Height settleHeight(int actual,int available,int collapsed){
		Height closest=Height.COLLAPSED;
		for(Height height:Height.values())
			if(Math.abs(actual-panelHeight(available,collapsed,height))<Math.abs(actual-panelHeight(available,collapsed,closest))) closest=height;
		return closest;
	}
}
