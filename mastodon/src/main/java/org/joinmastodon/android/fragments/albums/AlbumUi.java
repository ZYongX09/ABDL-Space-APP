package org.joinmastodon.android.fragments.albums;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.ImageView;
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
import android.widget.ScrollView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.albums.AlbumModels.Album;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.M3Switch;

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
	static Drawable protectedPlaceholder(Context context){
		Drawable lock=context.getDrawable(R.drawable.ic_lock_24px).mutate(); lock.setTint(UiUtils.getThemeColor(context, R.attr.colorM3OnSurfaceVariant));
		LayerDrawable placeholder=new LayerDrawable(new Drawable[]{gradient(context, null), lock});
		placeholder.setLayerSize(1, V.dp(32), V.dp(32)); placeholder.setLayerGravity(1, Gravity.CENTER); return placeholder;
	}
	private static final java.util.WeakHashMap<ImageView, ImageLoad> imageLoads=new java.util.WeakHashMap<>();
	/** Use the cache's public cancellation handle, rather than ViewImageLoader's private LoadTask. */
	static void loadImage(ImageView view, String url, int size){
		cancelImage(view);
		ImageLoad load=new ImageLoad(view); imageLoads.put(view, load); view.addOnAttachStateChangeListener(load);
		load.request=me.grishka.appkit.imageloader.ImageCache.getInstance(view.getContext()).get(
				new me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest(url, size, size), null, load, true);
	}
	static boolean isImageLoading(ImageView view){ return imageLoads.containsKey(view); }
	static void cancelImage(ImageView view){
		ImageLoad load=imageLoads.remove(view);
		if(load!=null){ load.canceled=true; if(load.request!=null) load.request.cancel(); view.removeOnAttachStateChangeListener(load); }
	}
	static void clearImages(View root){
		if(root instanceof ImageView image){ cancelImage(image); image.setImageDrawable(null); }
		if(root instanceof android.view.ViewGroup group) for(int i=0;i<group.getChildCount();i++) clearImages(group.getChildAt(i));
	}
	private static final class ImageLoad implements me.grishka.appkit.imageloader.ImageLoaderCallback, View.OnAttachStateChangeListener{
		final java.lang.ref.WeakReference<ImageView> view;
		final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
		me.grishka.appkit.imageloader.ImageCache.PendingImageRequest request;
		boolean canceled;
		ImageLoad(ImageView view){ this.view=new java.lang.ref.WeakReference<>(view); }
		@Override public void onImageLoaded(me.grishka.appkit.imageloader.requests.ImageLoaderRequest request, Drawable drawable){
			main.post(()->{
				ImageView target=view.get();
				if(!canceled && target!=null && imageLoads.get(target)==this){ cancelImage(target); target.setImageDrawable(drawable); }
			});
		}
		@Override public void onImageLoadingFailed(me.grishka.appkit.imageloader.requests.ImageLoaderRequest request, Throwable error){
			main.post(()->{ ImageView target=view.get(); if(target!=null && imageLoads.get(target)==this) cancelImage(target); });
		}
		@Override public void onViewAttachedToWindow(View view){}
		@Override public void onViewDetachedFromWindow(View view){ cancelImage((ImageView)view); }
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
		// Creation still uses the legacy name/visibility contract. Protection is owner-only, after creation.
		M3Switch protection=album!=null && album.isOwner ? new M3Switch(context) : null;
		if(protection!=null){
			protection.setText(R.string.album_protection_title); protection.setTextAppearance(R.style.m3_title_medium);
			protection.setTextColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurface)); protection.setMinHeight(V.dp(56));
			protection.setChecked(album.downloadProtected); root.addView(protection, new LinearLayout.LayoutParams(-1, -2));
			label(root, context.getString(R.string.album_protection_description), false);
		}
		ScrollView scroll=new ScrollView(context); scroll.addView(root);
		android.app.AlertDialog dialog=new M3AlertDialogBuilder(context).setTitle(album==null ? R.string.album_new : R.string.album_settings).setView(scroll)
				.setPositiveButton(album==null ? R.string.album_create : R.string.album_save, null).setNegativeButton(R.string.cancel, null).create();
		dialog.setOnShowListener(ignored->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
			String value=name.getText().toString().trim();
			if(album!=null && !album.isOwner){ dialog.dismiss(); return; }
			if((album==null || !album.isDefault) && (value.isEmpty() || value.codePointCount(0, value.length())>80)){ name.setError(context.getString(R.string.album_name_invalid)); return; }
			submit.accept(new EditValues(value, VISIBILITIES.get(Math.max(0, privacy.getCheckedRadioButtonId()-1)), protection!=null && protection.isChecked())); dialog.dismiss();
		}));
		if(delete!=null && album!=null && album.isOwner && !album.isDefault) button(root, R.string.album_delete_album, ()->{ dialog.dismiss(); delete.run(); });
		dialog.show(); return dialog;
	}
	static class EditValues{
		final String name, visibility;
		final boolean downloadProtected;
		EditValues(String name, String visibility){ this(name, visibility, false); }
		EditValues(String name, String visibility, boolean downloadProtected){ this.name=name; this.visibility=visibility; this.downloadProtected=downloadProtected; }
	}
}
