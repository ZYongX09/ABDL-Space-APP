package org.joinmastodon.android.ui.sheets;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.ui.text.BadgeSpan;
import org.joinmastodon.android.ui.utils.UiUtils;

import androidx.annotation.NonNull;
import java.util.concurrent.atomic.AtomicBoolean;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.BottomSheet;

/**
 * 新徽章通知 bottomsheet：用户获得新徽章（颁发/达成条件）后，
 * 下次启动 App 时逐枚弹窗提示。确认后回调 onAcknowledged。
 */
public class NewBadgeSheet extends BottomSheet{

	public NewBadgeSheet(@NonNull Context context, String name, String description, String color, Runnable onAcknowledged){
		super(context);
		View content=context.getSystemService(LayoutInflater.class).inflate(R.layout.sheet_badge_explainer, null);
		setContentView(content);
		setNavigationBarBackground(new ColorDrawable(UiUtils.alphaBlendColors(UiUtils.getThemeColor(context, R.attr.colorM3Surface),
				UiUtils.getThemeColor(context, R.attr.colorM3Primary), 0.05f)), !UiUtils.isDarkTheme());

		// 用圆角矩形文字徽章预览替换原插画位置
		View iconView=content.findViewById(R.id.badge_icon);
		if(iconView!=null && iconView.getParent() instanceof LinearLayout headerRow){
			int index=headerRow.indexOfChild(iconView);
			headerRow.removeView(iconView);
			TextView pill=new TextView(context);
			pill.setText(name);
			int bgColor=parseColor(color);
			GradientDrawable bg=new GradientDrawable();
			bg.setCornerRadius(V.dp(10));
			bg.setColor(bgColor);
			pill.setBackground(bg);
			pill.setTextColor(BadgeSpan.foregroundColorFor(bgColor));
			pill.setTextSize(15);
			pill.setTypeface(pill.getTypeface(), android.graphics.Typeface.BOLD);
			pill.setPadding(V.dp(12), V.dp(5), V.dp(12), V.dp(5));
			LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
			params.setMargins(V.dp(16), 0, V.dp(16), 0);
			headerRow.addView(pill, index, params);
		}

		TextView badgeName=content.findViewById(R.id.badge_name);
		TextView badgeDescription=content.findViewById(R.id.badge_description);
		TextView howToGet=content.findViewById(R.id.badge_how_to_get);
		badgeName.setText(name);
		badgeDescription.setText(description==null || description.isEmpty() ? context.getString(R.string.new_badge_default_description) : description);
		howToGet.setText(R.string.new_badge_how_to_get);
		howToGet.setVisibility(View.VISIBLE);

		AtomicBoolean completed=new AtomicBoolean();
		Runnable complete=()->{ if(onAcknowledged!=null && completed.compareAndSet(false,true)) onAcknowledged.run(); };
		setOnDismissListener(dialog->complete.run());
		TextView dismiss=content.findViewById(R.id.btn_dismiss);
		dismiss.setText(R.string.new_badge_acknowledge);
		dismiss.setOnClickListener(v->{ dismiss(); complete.run(); });
	}

	private static int parseColor(String color){
		if(color==null || color.length()!=7 || color.charAt(0)!='#')
			return 0xFF7C4DFF;
		try{
			return (int)(0xFF000000L | Long.parseLong(color.substring(1), 16));
		}catch(NumberFormatException e){
			return 0xFF7C4DFF;
		}
	}
}
