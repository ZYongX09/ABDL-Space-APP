package org.joinmastodon.android.sponsors;

import org.joinmastodon.android.E;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.SponsorChangedEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.sponsors.SponsorModels;

public final class SponsorRefresh{
	private SponsorRefresh(){}

	public static void apply(String accountID, SponsorModels.Me result){
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session==null || session.self==null || result==null || result.sponsor==null) return;
		Account.PublicSponsor appearance=null;
		if(result.sponsor.isActive()){
			appearance=new Account.PublicSponsor();
			appearance.active=true;
			appearance.permanent=result.sponsor.permanent;
			appearance.validUntil=result.sponsor.permanent ? null : result.sponsor.expiresAt;
			appearance.colorLight=result.sponsor.colorLight;
			appearance.colorDark=result.sponsor.colorDark;
		}
		session.self.sponsor=appearance;
		AccountSessionManager.getInstance().updateAccountInfo(accountID, session.self);
		E.post(new SponsorChangedEvent(accountID));
	}
}
