package org.joinmastodon.android.ui.sheets;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import me.grishka.appkit.views.BottomSheet;

public class LocationPermissionSheet extends BottomSheet{
	private final Activity activity;
	private Runnable onDismissCallback;

	public LocationPermissionSheet(@NonNull Context context, Activity activity){
		this(context, activity, null);
	}

	public LocationPermissionSheet(@NonNull Context context, Activity activity, @Nullable Runnable onDismissCallback){
		super(context);
		this.activity=activity;
		this.onDismissCallback=onDismissCallback;
		View content=context.getSystemService(LayoutInflater.class).inflate(R.layout.sheet_location_permission, null);
		setContentView(content);
		setNavigationBarBackground(new ColorDrawable(UiUtils.alphaBlendColors(
				UiUtils.getThemeColor(context, R.attr.colorM3Surface),
				UiUtils.getThemeColor(context, R.attr.colorM3Primary), 0.05f)), !UiUtils.isDarkTheme());

		findViewById(R.id.btn_skip).setOnClickListener(v->dismiss());
		findViewById(R.id.btn_allow).setOnClickListener(v->{
			dismiss();
			ActivityCompat.requestPermissions(activity,
					new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 7001);
		});
	}

	@Override
	public void dismiss(){
		super.dismiss();
		if(onDismissCallback!=null){
			onDismissCallback.run();
			onDismissCallback=null;
		}
	}
}
