package org.joinmastodon.android.fragments.albums;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.albums.AlbumModels.Album;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.function.Consumer;

import me.grishka.appkit.utils.V;

final class AlbumUi{
	private AlbumUi(){}
	static final List<String> VISIBILITIES=List.of("private", "public", "shared");
	static String visibility(Context context, String value){
		return context.getString("public".equals(value) ? R.string.album_public : "shared".equals(value) ? R.string.album_shared : R.string.album_private);
	}
	static LinearLayout column(Context context){ LinearLayout view=new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
	static TextView label(LinearLayout parent, CharSequence value, boolean title){
		TextView view=new TextView(parent.getContext()); view.setText(value); view.setTextAppearance(title ? R.style.m3_title_medium : R.style.m3_body_medium);
		view.setTextColor(UiUtils.getThemeColor(parent.getContext(), title ? R.attr.colorM3OnSurface : R.attr.colorM3OnSurfaceVariant));
		view.setPadding(0, V.dp(8), 0, V.dp(8)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
	}
	static Button button(LinearLayout parent, int text, Runnable action){
		Button view=new Button(parent.getContext(), null, 0, R.style.Widget_Mastodon_M3_Button_Outlined); view.setText(text); view.setMinHeight(V.dp(48));
		view.setOnClickListener(v->action.run()); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
	}
	static void rounded(View view){ view.setOutlineProvider(OutlineProviders.roundedRect(16)); view.setClipToOutline(true); }
	static GradientDrawable gradient(Context context, String seed){
		int first=UiUtils.getThemeColor(context, (seed==null || (seed.hashCode()&1)==0) ? R.attr.colorM3PrimaryContainer : R.attr.colorM3SecondaryContainer);
		int second=UiUtils.getThemeColor(context, R.attr.colorM3SurfaceVariant);
		return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{first, second});
	}
	static String date(long second){ return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(second)); }
	static String inviteToken(String value){
		if(value==null) return null;
		value=value.trim();
		if(value.matches("[a-f0-9]{64}")) return value;
		try{ return org.joinmastodon.android.albums.AlbumInviteLink.parseToken(Uri.parse(value)); }
		catch(RuntimeException invalid){ return null; }
	}
	static String clipboard(Context context){
		ClipboardManager manager=context.getSystemService(ClipboardManager.class);
		ClipData clip=manager==null ? null : manager.getPrimaryClip();
		return clip!=null && clip.getItemCount()>0 ? String.valueOf(clip.getItemAt(0).coerceToText(context)) : "";
	}
	static android.app.AlertDialog edit(Context context, Album album, Consumer<EditValues> submit){ return edit(context, album, submit, null); }
	static android.app.AlertDialog edit(Context context, Album album, Consumer<EditValues> submit, Runnable delete){
		LinearLayout root=column(context); root.setPadding(V.dp(24), V.dp(8), V.dp(24), 0);
		EditText name=new EditText(context, null, 0, R.style.Widget_Mastodon_M3_EditText); name.setSingleLine(true); name.setHint(R.string.album_name);
		name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
		name.setText(album==null ? "" : album.name); name.setEnabled(album==null || !album.isDefault); root.addView(name, new LinearLayout.LayoutParams(-1, V.dp(56)));
		label(root, context.getString(R.string.album_privacy_label), true);
		RadioGroup privacy=new RadioGroup(context); privacy.setOrientation(RadioGroup.VERTICAL);
		for(int i=0;i<VISIBILITIES.size();i++){
			RadioButton choice=new RadioButton(context); choice.setId(i+1); choice.setText(visibility(context, VISIBILITIES.get(i))); choice.setMinHeight(V.dp(48));
			choice.setEnabled(album==null || !album.isDefault); privacy.addView(choice);
		}
		privacy.check(1+Math.max(0, VISIBILITIES.indexOf(album==null ? "private" : album.visibility))); root.addView(privacy);
		label(root, context.getString(album!=null && album.isDefault ? R.string.album_default_help : R.string.album_privacy_help), false);
		android.app.AlertDialog dialog=new M3AlertDialogBuilder(context).setTitle(album==null ? R.string.album_new : R.string.album_settings).setView(root)
				.setPositiveButton(album==null ? R.string.album_create : R.string.album_save, null).setNegativeButton(R.string.cancel, null).create();
		dialog.setOnShowListener(ignored->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
			String value=name.getText().toString().trim();
			if(value.isEmpty() || value.codePointCount(0, value.length())>80){ name.setError(context.getString(R.string.album_name_invalid)); return; }
			if(album!=null && album.isDefault){ dialog.dismiss(); return; }
			submit.accept(new EditValues(value, VISIBILITIES.get(Math.max(0, privacy.getCheckedRadioButtonId()-1)))); dialog.dismiss();
		}));
		if(delete!=null && album!=null && !album.isDefault) button(root, R.string.album_delete_album, ()->{ dialog.dismiss(); delete.run(); });
		dialog.show(); return dialog;
	}
	static class EditValues{
		final String name, visibility;
		EditValues(String name, String visibility){ this.name=name; this.visibility=visibility; }
	}
}
