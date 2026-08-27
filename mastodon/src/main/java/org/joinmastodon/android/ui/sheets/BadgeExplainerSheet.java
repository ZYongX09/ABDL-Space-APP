package org.joinmastodon.android.ui.sheets;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import androidx.annotation.NonNull;
import me.grishka.appkit.views.BottomSheet;

public class BadgeExplainerSheet extends BottomSheet{

	public BadgeExplainerSheet(@NonNull Context context, String name, String description, int iconRes){
		super(context);
		View content=context.getSystemService(LayoutInflater.class).inflate(R.layout.sheet_badge_explainer, null);
		setContentView(content);
		setNavigationBarBackground(new ColorDrawable(UiUtils.alphaBlendColors(UiUtils.getThemeColor(context, R.attr.colorM3Surface),
				UiUtils.getThemeColor(context, R.attr.colorM3Primary), 0.05f)), !UiUtils.isDarkTheme());

		ImageView badgeIcon=findViewById(R.id.badge_icon);
		TextView badgeName=findViewById(R.id.badge_name);
		TextView badgeDescription=findViewById(R.id.badge_description);
		TextView badgeHowToGet=findViewById(R.id.badge_how_to_get);

		if(iconRes!=0){
			badgeIcon.setImageResource(iconRes);
			badgeIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
			badgeIcon.setVisibility(View.VISIBLE);
		}else{
			badgeIcon.setVisibility(View.GONE);
		}

		badgeName.setText(name);
		badgeDescription.setText(description);

		String howToGet=getHowToGetText(name);
		if(howToGet!=null){
			badgeHowToGet.setText(howToGet);
			badgeHowToGet.setVisibility(View.VISIBLE);
		}else{
			badgeHowToGet.setVisibility(View.GONE);
		}

		findViewById(R.id.btn_dismiss).setOnClickListener(v->dismiss());
	}

	private String getHowToGetText(String badgeName){
		if(badgeName==null) return null;
		switch(badgeName){
			case "圈内认证":
				return "如何获得：完成平台身份验证流程。";
			default:
				return null;
		}
	}
}
