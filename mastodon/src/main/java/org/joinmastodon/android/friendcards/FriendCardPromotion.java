package org.joinmastodon.android.friendcards;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Installation-wide campaign; page tokens survive view/activity recreation. */
public final class FriendCardPromotion{
	private static final Object LOCK=new Object();
	private static final String PREFERENCES="friend_card_promotion";
	private static final String PAGES="shown_pages", DISMISSED="dismissed";
	private static final int LIMIT=5;
	private final SharedPreferences preferences;
	private final int version;
	private final String page;

	public FriendCardPromotion(Context context,int version,String page){
		this(context.getApplicationContext().getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE),version,page);
	}

	FriendCardPromotion(SharedPreferences preferences,int version,String page){
		this.preferences=preferences;
		this.version=version;
		this.page=page;
	}

	public static boolean supports(int version){ return version==33 || version==34; }

	public boolean canShow(){
		synchronized(LOCK){ return eligible(pages()); }
	}

	public boolean onVisible(){
		synchronized(LOCK){
			Set<String> pages=pages();
			if(!eligible(pages)) return false;
			if(pages.contains(page)) return true;
			pages.add(page);
			return preferences.edit().putStringSet(PAGES,pages).commit();
		}
	}

	public void dismiss(){
		synchronized(LOCK){ preferences.edit().putBoolean(DISMISSED,true).commit(); }
	}

	private boolean eligible(Set<String> pages){
		return supports(version) && page!=null && !page.isEmpty() && !preferences.getBoolean(DISMISSED,false)
				&& (pages.contains(page) || pages.size()<LIMIT);
	}

	private Set<String> pages(){ return new HashSet<>(preferences.getStringSet(PAGES,Set.of())); }
}
