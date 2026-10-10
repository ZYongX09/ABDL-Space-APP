package org.joinmastodon.android.ui.map;

public final class MapSheetState{
	public enum Page{ NEARBY, PERSON, SETTINGS, VISIBILITY, PRECISION, PROVIDER, CONSENT, PIN }
	public enum Height{ COLLAPSED, HALF, EXPANDED }
	private Page page=Page.NEARBY;
	private Height height=Height.COLLAPSED;

	public Page page(){ return page; }
	public Height height(){ return height; }
	public void open(Page page){
		this.page=page;
		height=page==Page.SETTINGS || page==Page.CONSENT ? Height.EXPANDED : Height.HALF;
	}
	public void setHeight(Height height){ this.height=height; }
	public boolean back(){
		if(page!=Page.NEARBY){ page=Page.NEARBY; height=Height.HALF; return true; }
		if(height!=Height.COLLAPSED){ height=Height.COLLAPSED; return true; }
		return false;
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
