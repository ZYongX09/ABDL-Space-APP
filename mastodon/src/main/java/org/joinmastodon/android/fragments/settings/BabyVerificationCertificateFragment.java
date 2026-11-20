package org.joinmastodon.android.fragments.settings;

import android.Manifest;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.requests.verification.VerificationRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.verification.VerificationModels.Certificate;
import org.joinmastodon.android.model.verification.VerificationModels.CertificateEnvelope;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.verification.BabyCertificateRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.utils.V;

public class BabyVerificationCertificateFragment extends ToolbarFragment{
	private static final int STORAGE_PERMISSION_REQUEST=5104;
	private enum PendingAction{ NONE, SAVE, SHARE }

	private String accountID;
	private AccountSession pinnedSession;
	private Certificate certificate;
	private Bitmap rendered;
	private VerificationRequest<CertificateEnvelope> request;
	private int generation;
	private boolean viewReady, resumedOnce;
	private PendingAction pendingAction=PendingAction.NONE;
	private LinearLayout content;
	private TextView status;
	private ImageView image;
	private Button refresh, save, share;

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		if(getActivity()!=null) getActivity().getTheme().applyStyle(UiUtils.isDarkTheme()
				? R.style.ThemeOverlay_Mastodon_Verification_Dark
				: R.style.ThemeOverlay_Mastodon_Verification_Light, true);
		setTitle(R.string.verification_certificate_title);
		accountID=getArguments().getString("account");
		pinnedSession=AccountSessionManager.getInstance().tryGetAccount(accountID);
		String serialized=getArguments().getString("certificate");
		if(serialized!=null){
			certificate=MastodonAPIController.gson.fromJson(serialized, Certificate.class);
			try{ certificate.postprocess(); }catch(Exception invalid){ certificate=null; }
		}
	}

	@Override public void onViewCreated(View view, Bundle state){
		super.onViewCreated(view, state);
		// Full-screen design: the toolbar only exists after onCreateContentView, so hide it here.
		android.widget.Toolbar toolbar=getToolbar();
		if(toolbar!=null) toolbar.setVisibility(View.GONE);
		view.post(()->org.joinmastodon.android.ui.verification.VerificationWindowMode.reapply(getActivity()));
	}

	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		org.joinmastodon.android.ui.verification.VerificationWindowMode.enter(getActivity());
		ScrollView scroll=new ScrollView(getActivity());
		content=new LinearLayout(getActivity());
		content.setOrientation(LinearLayout.VERTICAL);
		content.setBackgroundColor(UiUtils.getThemeColor(getActivity(), R.attr.vrPage));
		content.setPadding(V.dp(20), V.dp(12), V.dp(20), V.dp(24));
		androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets)->{
			v.setPadding(0, insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top, 0, 0);
			return insets;
		});
		scroll.addView(content);

		android.widget.FrameLayout bar=new android.widget.FrameLayout(getActivity());
		android.widget.ImageButton back=new android.widget.ImageButton(getActivity());
		back.setBackgroundColor(android.graphics.Color.TRANSPARENT);
		back.setImageResource(R.drawable.ic_fluent_arrow_left_24_regular);
		android.content.res.ColorStateList tint=android.content.res.ColorStateList.valueOf(UiUtils.getThemeColor(getActivity(), R.attr.vrTextPrimary));
		back.setImageTintList(tint);
		back.setContentDescription(getString(R.string.back));
		back.setOnClickListener(v->{ if(getActivity()!=null) getActivity().onBackPressed(); });
		int backSize=V.dp(36);
		android.widget.FrameLayout.LayoutParams backParams=new android.widget.FrameLayout.LayoutParams(backSize, backSize);
		backParams.gravity=android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL;
		bar.addView(back, backParams);
		android.widget.TextView pill=new android.widget.TextView(getActivity());
		boolean chinese=org.joinmastodon.android.ui.utils.AppLanguageManager.getEffectiveLocale(getActivity())
				.getLanguage().startsWith("zh");
		pill.setText(chinese ? "ZH" : "EN");
		pill.setTextSize(11);
		pill.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.vrTextPrimary));
		pill.setBackgroundResource(R.drawable.vr_bg_pill);
		pill.setGravity(android.view.Gravity.CENTER);
		pill.setPadding(V.dp(9), V.dp(6), V.dp(7), V.dp(6));
		pill.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_vr_globe_20, 0, 0, 0);
		pill.setCompoundDrawablePadding(V.dp(5));
		pill.setCompoundDrawableTintList(tint);
		pill.setOnClickListener(v->{
			if(getActivity()!=null) org.joinmastodon.android.ui.utils.AppLanguageManager.setLanguage(getActivity(),
					chinese ? org.joinmastodon.android.ui.utils.AppLanguageManager.ENGLISH
							: org.joinmastodon.android.ui.utils.AppLanguageManager.CHINESE);
		});
		android.widget.FrameLayout.LayoutParams pillParams=new android.widget.FrameLayout.LayoutParams(-2, -2);
		pillParams.gravity=android.view.Gravity.END|android.view.Gravity.CENTER_VERTICAL;
		bar.addView(pill, pillParams);
		content.addView(bar, new LinearLayout.LayoutParams(-1, V.dp(36)));

		status=new TextView(getActivity());
		status.setTextAppearance(R.style.TextAppearance_Mastodon_Verification2_Subtitle);
		status.setGravity(android.view.Gravity.CENTER);
		LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(-1, -2);
		statusParams.bottomMargin=V.dp(12);
		content.addView(status, statusParams);

		// The credential itself is the legacy design: show it edge-to-edge on the page.
		android.widget.FrameLayout card=new android.widget.FrameLayout(getActivity());
		image=new ImageView(getActivity());
		image.setAdjustViewBounds(true);
		image.setContentDescription(getString(R.string.verification_certificate_qr_description));
		card.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -2));
		content.addView(card, new LinearLayout.LayoutParams(-1, -2));

		refresh=new Button(getActivity()); refresh.setVisibility(View.GONE);
		save=new Button(getActivity(), null, 0, R.style.Widget_Mastodon_Verification2_PrimaryButton);
		save.setText(R.string.vr_save_certificate);
		save.setOnClickListener(v->save());
		LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(-1, V.dp(52));
		saveParams.topMargin=V.dp(18);
		content.addView(save, saveParams);

		LinearLayout row=new LinearLayout(getActivity());
		row.setOrientation(LinearLayout.HORIZONTAL);
		LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1, V.dp(52));
		rowParams.topMargin=V.dp(10);
		content.addView(row, rowParams);
		Button copy=new Button(getActivity(), null, 0, R.style.Widget_Mastodon_Verification2_SecondaryButton);
		copy.setText(R.string.vr_copy_link);
		copy.setOnClickListener(v->copyLink());
		share=new Button(getActivity(), null, 0, R.style.Widget_Mastodon_Verification2_SecondaryButton);
		share.setText(R.string.vr_share_certificate);
		share.setOnClickListener(v->refresh(PendingAction.SHARE));
		LinearLayout.LayoutParams copyParams=new LinearLayout.LayoutParams(0, -1, 1f);
		copyParams.rightMargin=V.dp(5);
		LinearLayout.LayoutParams shareParams=new LinearLayout.LayoutParams(0, -1, 1f);
		shareParams.leftMargin=V.dp(5);
		row.addView(copy, copyParams);
		row.addView(share, shareParams);

		TextView footer=new TextView(getActivity());
		footer.setText(R.string.vr_footer);
		footer.setTextAppearance(R.style.TextAppearance_Mastodon_Verification2_Footer);
		footer.setGravity(android.view.Gravity.CENTER);
		LinearLayout.LayoutParams footerParams=new LinearLayout.LayoutParams(-1, -2);
		footerParams.topMargin=V.dp(12);
		content.addView(footer, footerParams);
		viewReady=true;
		refresh(PendingAction.NONE);
		return scroll;
	}

	private Button button(int label, View.OnClickListener listener, int topMargin){
		Button button=new Button(getActivity(), null, 0, R.style.Widget_Mastodon_M3_Button_Tonal);
		button.setText(label);
		button.setOnClickListener(listener);
		LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1, V.dp(52));
		lp.topMargin=V.dp(topMargin);
		content.addView(button, lp);
		return button;
	}

	@Override public void onResume(){
		org.joinmastodon.android.ui.verification.VerificationWindowMode.reapply(getActivity());
		super.onResume();
		if(!resumedOnce){ resumedOnce=true; return; }
		if(viewReady) refresh(PendingAction.NONE);
	}

	private void refresh(PendingAction action){
		if(!viewReady) return;
		if(!sessionValid()){
			pendingAction=PendingAction.NONE;
			showUnavailable(getString(R.string.verification_account_changed));
			return;
		}
		if(request!=null) request.cancel();
		request=null;
		pendingAction=action;
		int token=++generation;
		clearRendered();
		status.setText(R.string.verification_status_loading);
		setActionsEnabled(false);
		VerificationRequest<CertificateEnvelope> next=VerificationRequest.certificate();
		request=next;
		next.setCallback(new Callback<>(){
			@Override public void onSuccess(CertificateEnvelope result){
				if(!live(token, next)) return;
				request=null;
				certificate=result.certificate;
				renderLatest(token);
			}
			@Override public void onError(ErrorResponse error){
				if(!live(token, next)) return;
				request=null;
				pendingAction=PendingAction.NONE;
				showUnavailable(getString(R.string.verification_certificate_refresh_failed)+"\n"+error);
			}
		}).exec(accountID);
	}

	private void renderLatest(int token){
		if(!viewReady || token!=generation || !sessionValid()) return;
		if(certificate==null){
			pendingAction=PendingAction.NONE;
			status.setVisibility(View.VISIBLE);
			status.setText(R.string.verification_certificate_pending_sync);
			setActionsEnabled(false);
			refresh.setEnabled(true);
			return;
		}
		if(certificate.isRevoked()){
			pendingAction=PendingAction.NONE;
			status.setVisibility(View.VISIBLE);
			status.setText(getString(R.string.verification_certificate_revoked_detail, formatTime(certificate.revokedAt), certificate.revokeReason));
			setActionsEnabled(false);
			refresh.setEnabled(true);
			return;
		}
		if(!certificate.isActive()){
			pendingAction=PendingAction.NONE;
			showUnavailable(getString(R.string.verification_certificate_invalid_link));
			return;
		}
		try{
			rendered=render(certificate);
			if(!viewReady || token!=generation || !sessionValid()){
				clearRendered();
				return;
			}
			image.setImageBitmap(rendered);
			status.setVisibility(View.GONE);
			status.setText(getString(R.string.verification_certificate_active_summary, certificate.generation));
			setActionsEnabled(true);
			PendingAction action=pendingAction;
			pendingAction=PendingAction.NONE;
			if(action==PendingAction.SAVE) doSave();
			else if(action==PendingAction.SHARE) doShare();
		}catch(Exception error){
			pendingAction=PendingAction.NONE;
			showUnavailable(getString(R.string.verification_certificate_export_failed));
		}
	}

	private Bitmap render(Certificate value) throws Exception{
		String[] notes={
				getString(R.string.verification_certificate_note_1),
				getString(R.string.verification_certificate_note_2),
				getString(R.string.verification_certificate_note_3),
				getString(R.string.verification_certificate_note_4),
				getString(R.string.verification_certificate_note_5),
		};
		return BabyCertificateRenderer.render(value, notes);
	}

	private void copyLink(){
		if(certificate==null || !certificate.isActive()) return;
		ClipboardManager clipboard=(ClipboardManager)getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
		clipboard.setPrimaryClip(ClipData.newPlainText("ABDL Space 宝宝认证证书", certificate.publicUrl()));
		Toast.makeText(getActivity(), R.string.vr_copy_link_done, Toast.LENGTH_SHORT).show();
	}

	private void save(){
		if(Build.VERSION.SDK_INT<29 && getActivity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
			requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
			return;
		}
		refresh(PendingAction.SAVE);
	}

	private void doSave(){
		if(!canExport()) return;
		String name="ABDL-Space-Certificate-"+certificate.id+".png";
		Uri uri=Build.VERSION.SDK_INT>=29 ? writeToMediaStore(name) : writeToLegacyPictures(name);
		Toast.makeText(getActivity(), uri==null ? R.string.error_saving_file : R.string.verification_certificate_saved, Toast.LENGTH_LONG).show();
	}

	private Uri writeToMediaStore(String name){
		ContentValues values=new ContentValues();
		values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
		values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
		values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES+"/ABDL Space");
		if(Build.VERSION.SDK_INT>=29) values.put(MediaStore.Images.Media.IS_PENDING, 1);
		Uri uri=null;
		try{
			uri=getActivity().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
			if(uri==null || !canExport()) return null;
			try(OutputStream output=getActivity().getContentResolver().openOutputStream(uri)){
				if(output==null || !rendered.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException();
			}
			ContentValues complete=new ContentValues();
			complete.put(MediaStore.Images.Media.IS_PENDING, 0);
			getActivity().getContentResolver().update(uri, complete, null, null);
			return uri;
		}catch(Exception error){
			if(uri!=null) getActivity().getContentResolver().delete(uri, null, null);
			return null;
		}
	}

	private Uri writeToLegacyPictures(String name){
		if(!canExport()) return null;
		File directory=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ABDL Space");
		if(!directory.exists() && !directory.mkdirs()) return null;
		File file=new File(directory, name);
		try(FileOutputStream output=new FileOutputStream(file)){
			if(!canExport() || !rendered.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException();
			MediaScannerConnection.scanFile(getActivity(), new String[]{file.getAbsolutePath()}, new String[]{"image/png"}, null);
			return Uri.fromFile(file);
		}catch(Exception error){
			file.delete();
			return null;
		}
	}

	private void doShare(){
		if(!canExport()) return;
		File directory=new File(getActivity().getCacheDir(), "images");
		if(!directory.exists() && !directory.mkdirs()) return;
		File file=new File(directory, "ABDL-Space-Certificate-"+certificate.id+".png");
		try(FileOutputStream output=new FileOutputStream(file)){
			if(!canExport() || !rendered.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException();
			Uri uri=UiUtils.getFileProviderUri(getActivity(), file);
			Intent intent=new Intent(Intent.ACTION_SEND)
					.setType("image/png")
					.putExtra(Intent.EXTRA_STREAM, uri)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			intent.setClipData(android.content.ClipData.newRawUri(null, uri));
			startActivity(Intent.createChooser(intent, getString(R.string.verification_certificate_share)));
		}catch(Exception error){
			file.delete();
			Toast.makeText(getActivity(), R.string.error_saving_file, Toast.LENGTH_LONG).show();
		}
	}

	private boolean canExport(){
		return viewReady && sessionValid() && certificate!=null && certificate.isActive() && rendered!=null && !rendered.isRecycled();
	}

	private void showUnavailable(String message){
		clearRendered();
		status.setText(message);
		setActionsEnabled(false);
		if(refresh!=null) refresh.setEnabled(sessionValid());
	}

	private void setActionsEnabled(boolean active){
		if(refresh!=null) refresh.setEnabled(active);
		if(save!=null) save.setEnabled(active);
		if(share!=null) share.setEnabled(active);
	}

	private boolean sessionValid(){
		return pinnedSession!=null && AccountSessionManager.getInstance().tryGetAccount(accountID)==pinnedSession;
	}

	private boolean live(int token, VerificationRequest<?> expected){
		return viewReady && token==generation && request==expected && sessionValid();
	}

	private String formatTime(Long seconds){
		if(seconds==null || seconds<=0) return "—";
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()));
	}

	private void clearRendered(){
		if(image!=null) image.setImageDrawable(null);
		if(rendered!=null && !rendered.isRecycled()) rendered.recycle();
		rendered=null;
	}

	@Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if(requestCode==STORAGE_PERMISSION_REQUEST && grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED) refresh(PendingAction.SAVE);
		else if(requestCode==STORAGE_PERMISSION_REQUEST) Toast.makeText(getActivity(), R.string.storage_permission_to_download, Toast.LENGTH_LONG).show();
	}

	@Override public void onDestroyView(){
		org.joinmastodon.android.ui.verification.VerificationWindowMode.exit(getActivity());
		viewReady=false;
		generation++;
		pendingAction=PendingAction.NONE;
		if(request!=null) request.cancel();
		request=null;
		clearRendered();
		content=null;
		status=null;
		image=null;
		refresh=save=share=null;
		super.onDestroyView();
	}
}
