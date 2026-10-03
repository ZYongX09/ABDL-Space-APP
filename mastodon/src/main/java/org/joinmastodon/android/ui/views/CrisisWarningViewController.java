package org.joinmastodon.android.ui.views;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.CrisisHelpActivity;

public class CrisisWarningViewController{
	private final View card;

	public CrisisWarningViewController(View root){
		card=root.findViewById(R.id.compose_crisis_card);
		root.findViewById(R.id.compose_crisis_close).setOnClickListener(v->hide());
		root.findViewById(R.id.compose_crisis_help).setOnClickListener(v->{
			Activity activity=(Activity) root.getContext();
			activity.startActivity(new Intent(activity, CrisisHelpActivity.class));
			hide();
		});
	}

	public void show(){
		card.setVisibility(View.VISIBLE);
	}

	public void hide(){
		card.setVisibility(View.GONE);
	}

	public boolean isVisible(){
		return card.getVisibility()==View.VISIBLE;
	}
}
