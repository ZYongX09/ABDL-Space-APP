package org.joinmastodon.android.verification;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import java.util.ArrayList;
import java.util.List;

/** Pins async work to the exact selected AccountSession and generation. */
public final class VerificationSessionGuard{
	private final String accountId;
	private final AccountSession session;
	private final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
	private int generation;
	public VerificationSessionGuard(String accountId){ this.accountId=accountId; this.session=AccountSessionManager.getInstance().tryGetAccount(accountId); }
	public int nextGeneration(){ cancelRequests(); return ++generation; }
	public boolean live(int token){ return token==generation && session!=null && AccountSessionManager.getInstance().tryGetAccount(accountId)==session; }
	public <T extends MastodonAPIRequest<?>> T track(T request){ requests.add(request); return request; }
	public void done(MastodonAPIRequest<?> request){ requests.remove(request); }
	public void cancel(){ generation++; cancelRequests(); }
	private void cancelRequests(){ for(MastodonAPIRequest<?> request:new ArrayList<>(requests)) request.cancel(); requests.clear(); }
}
