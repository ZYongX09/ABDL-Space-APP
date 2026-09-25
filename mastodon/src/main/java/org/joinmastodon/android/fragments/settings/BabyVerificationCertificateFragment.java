package org.joinmastodon.android.fragments.settings;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.model.verification.VerificationModels.Certificate;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.joinmastodon.android.ui.verification.BabyCertificateRenderer;

import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.utils.V;

public class BabyVerificationCertificateFragment extends ToolbarFragment{
	private static final int STORAGE_PERMISSION_REQUEST=5104;
	private Certificate certificate;
	private Bitmap rendered;

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		setTitle(R.string.verification_certificate_title);
		certificate=MastodonAPIController.gson.fromJson(getArguments().getString("certificate"), Certificate.class);
		try{ certificate.postprocess(); }catch(Exception invalid){ certificate=null; }
	}

	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		ScrollView scroll=new ScrollView(getActivity());
		LinearLayout content=new LinearLayout(getActivity());
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(V.dp(16), V.dp(20), V.dp(16), V.dp(32));
		scroll.addView(content);
		if(certificate==null){
			android.widget.TextView error=new android.widget.TextView(getActivity());
			error.setText(R.string.verification_certificate_invalid_link);
			content.addView(error);
			return scroll;
		}
			try{
				rendered=render(certificate);
			}catch(Exception error){
				android.widget.TextView failure=new android.widget.TextView(getActivity());
				failure.setText(R.string.verification_certificate_export_failed);
				content.addView(failure);
				return scroll;
			}
			ImageView image=new ImageView(getActivity());
			image.setAdjustViewBounds(true);
			image.setContentDescription(getString(R.string.verification_certificate_qr_description));
			image.setImageBitmap(rendered);
		content.addView(image, new LinearLayout.LayoutParams(-1, -2));
		Button save=new Button(getActivity(), null, 0, R.style.Widget_Mastodon_M3_Button_Tonal);
		save.setText(R.string.verification_certificate_save);
		save.setOnClickListener(v->save());
		LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1, V.dp(52));
		lp.topMargin=V.dp(16);
		content.addView(save, lp);
		Button share=new Button(getActivity(), null, 0, R.style.Widget_Mastodon_M3_Button_Tonal);
		share.setText(R.string.verification_certificate_share);
		share.setOnClickListener(v->share());
		lp=new LinearLayout.LayoutParams(-1, V.dp(52));
		lp.topMargin=V.dp(8);
		content.addView(share, lp);
		return scroll;
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

	private void save(){
		if(Build.VERSION.SDK_INT<29 && getActivity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
			requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
			return;
		}
		doSave();
	}

	private void doSave(){
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
			if(uri==null) return null;
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
		File directory=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ABDL Space");
		if(!directory.exists() && !directory.mkdirs()) return null;
		File file=new File(directory, name);
		try(FileOutputStream output=new FileOutputStream(file)){
			if(!rendered.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException();
			MediaScannerConnection.scanFile(getActivity(), new String[]{file.getAbsolutePath()}, new String[]{"image/png"}, null);
			return Uri.fromFile(file);
		}catch(Exception error){
			file.delete();
			return null;
		}
	}

	private void share(){
		File directory=new File(getActivity().getCacheDir(), "images");
		if(!directory.exists() && !directory.mkdirs()) return;
		File file=new File(directory, "ABDL-Space-Certificate-"+certificate.id+".png");
		try(FileOutputStream output=new FileOutputStream(file)){
			if(!rendered.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException();
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

	@Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if(requestCode==STORAGE_PERMISSION_REQUEST && grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED) doSave();
		else if(requestCode==STORAGE_PERMISSION_REQUEST) Toast.makeText(getActivity(), R.string.storage_permission_to_download, Toast.LENGTH_LONG).show();
	}

	@Override public void onDestroy(){
		if(rendered!=null) rendered.recycle();
		super.onDestroy();
	}
}
