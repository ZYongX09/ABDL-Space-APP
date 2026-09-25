package org.joinmastodon.android.novel.editor;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.utils.V;

final class NovelUi{
	private NovelUi(){}
	static LinearLayout column(Context context){ LinearLayout v=new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(V.dp(20),V.dp(16),V.dp(20),V.dp(28)); return v; }
	static TextView title(LinearLayout parent,CharSequence text){ TextView v=text(parent,text); v.setTextAppearance(R.style.m3_headline_medium); return v; }
	static TextView heading(LinearLayout parent,CharSequence text){ TextView v=text(parent,text); v.setTextAppearance(R.style.m3_title_large); v.setPadding(0,V.dp(10),0,V.dp(8)); if(android.os.Build.VERSION.SDK_INT>=28)v.setAccessibilityHeading(true); return v; }
	static TextView body(LinearLayout parent,CharSequence text){ TextView v=text(parent,text); v.setTextAppearance(R.style.m3_body_large); return v; }
	static TextView label(LinearLayout parent,CharSequence text){ TextView v=text(parent,text); v.setTextAppearance(R.style.m3_label_large); v.setTextColor(UiUtils.getThemeColor(parent.getContext(),R.attr.colorM3OnSurfaceVariant)); return v; }
	private static TextView text(LinearLayout parent,CharSequence text){ TextView v=new TextView(parent.getContext()); v.setText(text); v.setTextColor(UiUtils.getThemeColor(parent.getContext(),R.attr.colorM3OnSurface)); v.setLineSpacing(V.dp(2),1); v.setPadding(0,V.dp(4),0,V.dp(4)); parent.addView(v,new LinearLayout.LayoutParams(-1,-2)); return v; }
	static LinearLayout card(LinearLayout parent){ LinearLayout card=column(parent.getContext()); card.setPadding(V.dp(20),V.dp(16),V.dp(20),V.dp(16)); GradientDrawable bg=new GradientDrawable(); bg.setColor(UiUtils.alphaBlendColors(UiUtils.getThemeColor(parent.getContext(),R.attr.colorM3Surface),UiUtils.getThemeColor(parent.getContext(),R.attr.colorM3Primary),.05f)); bg.setCornerRadius(V.dp(24)); card.setBackground(bg); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=V.dp(12); parent.addView(card,lp); return card; }
	static Button primary(LinearLayout parent,String text,Runnable action){ return button(parent,text,action,R.style.Widget_Mastodon_M3_Button_Filled); }
	static Button secondary(LinearLayout parent,String text,Runnable action){ return button(parent,text,action,R.style.Widget_Mastodon_M3_Button_Outlined); }
	static Button textButton(LinearLayout parent,String text,Runnable action){ return button(parent,text,action,R.style.Widget_Mastodon_M3_Button_Text); }
	private static Button button(LinearLayout parent,String text,Runnable action,int style){ Button b=new Button(new ContextThemeWrapper(parent.getContext(),style),null,0); b.setText(text); b.setAllCaps(false); b.setMinHeight(V.dp(48)); b.setOnClickListener(v->action.run()); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=V.dp(8); parent.addView(b,lp); return b; }
}
