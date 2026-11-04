package org.joinmastodon.android.ui.utils;

import android.view.View;

import org.joinmastodon.android.api.session.NBWEndpointsRepository;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;

/** Shared identity-free observer for the two anonymous login layouts; one repository owns the timer. */
public final class NBWLoginButtonBinding{
	private final View button;
	private final NBWForumOptionsRepository.Cancelable subscription;
	public NBWLoginButtonBinding(View button){
		this.button=button;
		subscription=NBWEndpointsRepository.getInstance().observe(snapshot->UiUtils.runOnUiThread(()->{
			button.setVisibility(snapshot.canWrite() ? View.VISIBLE : View.GONE);
			button.setEnabled(snapshot.canWrite());
		}));
	}
	public void resume(){ NBWEndpointsRepository.getInstance().enterForeground(this); }
	public void pause(){ NBWEndpointsRepository.getInstance().leaveForeground(this); }
	public void dispose(){ pause(); subscription.cancel(); }
	public static boolean canLaunch(){ return NBWEndpointsRepository.getInstance().getSnapshot().canWrite(); }
}
