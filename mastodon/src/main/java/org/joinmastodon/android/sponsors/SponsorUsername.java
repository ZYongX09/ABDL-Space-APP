package org.joinmastodon.android.sponsors;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.SponsorChangedEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.ui.utils.UiUtils;

public final class SponsorUsername{
	private SponsorUsername(){}

	public static void apply(TextView view, Account account){
		apply(view, account, null);
	}

	public static void apply(TextView view, Account account, String accountID){
		Binding binding=(Binding) view.getTag(R.id.sponsor_username_binding);
		if(binding==null){
			binding=new Binding(view);
			view.setTag(R.id.sponsor_username_binding, binding);
			view.addOnAttachStateChangeListener(binding);
		}
		binding.bind(account, accountID);
	}

	private static int background(TextView view){
		View current=view;
		while(current!=null){
			Drawable drawable=current.getBackground();
			if(drawable instanceof ColorDrawable color && Color.alpha(color.getColor())==255) return color.getColor();
			ViewParent parent=current.getParent();
			current=parent instanceof View ? (View) parent : null;
		}
		return UiUtils.getThemeColor(view.getContext(), R.attr.colorM3Surface);
	}

	public static final class Binding implements View.OnAttachStateChangeListener, Runnable{
		private final TextView view;
		private final ColorStateList originalColors;
		private Account account;
		private String accountID;
		private boolean registered;

		private Binding(TextView view){
			this.view=view;
			originalColors=view.getTextColors();
		}

		private void bind(Account account, String accountID){
			this.account=account;
			this.accountID=accountID;
			if(view.isAttachedToWindow()) register();
			run();
		}

		private void register(){
			if(registered) return;
			E.register(this);
			registered=true;
		}

		@Override public void run(){
			view.removeCallbacks(this);
			view.setTextColor(originalColors);
			Account.PublicSponsor sponsor=account==null ? null : account.sponsor;
			long now=System.currentTimeMillis()/1000;
			if(sponsor==null || !SponsorContrast.active(sponsor.active, sponsor.permanent, sponsor.validUntil, now)) return;
			Integer color=SponsorContrast.parse(UiUtils.isDarkTheme() ? sponsor.colorDark : sponsor.colorLight);
			if(color!=null) view.setTextColor(SponsorContrast.ensure(color, background(view)));
			if(!sponsor.permanent && sponsor.validUntil!=null && view.isAttachedToWindow()){
				long delay=Math.max(1, Math.min(3600, sponsor.validUntil-now))*1000;
				view.postDelayed(this, delay);
			}
		}

		@Subscribe public void onSponsorChanged(SponsorChangedEvent event){
			if(account==null || accountID==null || !accountID.equals(event.accountID)) return;
			AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
			if(session==null || session.self==null || !session.self.id.equals(account.id)) return;
			account.sponsor=session.self.sponsor;
			run();
		}

		@Override public void onViewAttachedToWindow(View attached){
			register();
			run();
		}

		@Override public void onViewDetachedFromWindow(View detached){
			view.removeCallbacks(this);
			if(registered){
				E.unregister(this);
				registered=false;
			}
		}
	}
}
