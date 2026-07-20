package org.joinmastodon.android.ui.views;

import android.view.View;

import org.joinmastodon.android.R;

public class CrisisWarningViewController{
	private final View card;

	public CrisisWarningViewController(View root){
		card=root.findViewById(R.id.compose_crisis_card);
		root.findViewById(R.id.compose_crisis_close).setOnClickListener(v->hide());
		root.findViewById(R.id.compose_crisis_help).setOnClickListener(v->hide());
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
