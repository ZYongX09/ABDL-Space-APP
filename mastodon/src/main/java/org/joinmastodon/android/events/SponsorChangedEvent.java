package org.joinmastodon.android.events;

public class SponsorChangedEvent{
	public final String accountID;
	public SponsorChangedEvent(String accountID){ this.accountID=accountID; }
}
