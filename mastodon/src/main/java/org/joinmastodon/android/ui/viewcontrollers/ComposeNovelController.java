package org.joinmastodon.android.ui.viewcontrollers;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.Button;
import androidx.core.graphics.ColorUtils;
import org.joinmastodon.android.ui.drawables.NovelCoverDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.res.ResourcesCompat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.statuses.NovelCardRequest;
import org.joinmastodon.android.model.NovelAttachment;
import org.joinmastodon.android.novel.app.design.NovelComposeState;
import org.joinmastodon.android.novel.app.models.NovelJson;
import org.joinmastodon.android.novel.app.models.Work;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.displayitems.NovelStatusDisplayItem;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.json.JSONObject;
import org.parceler.Parcels;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** Native selection/preview UI. Owns no editor, reader, manifest, credentials or background draft upload. */
public final class ComposeNovelController{
	private final Activity activity;
	private final String accountID, ownerID;
	private final NovelComposeState state;
	private final Runnable changed, languageAction;
	private final BooleanSupplier mixedEntity;
	private final ImageButton tool;
	private final LinearLayout preview;
	private final ImageView cover;
	private final TextView title,meta;
	private final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
	private List<Work> publicWorks=List.of();
	private boolean active,disposed;
	private int generation,coverGeneration;
	private AlertDialog picker;
	private String selected;

	public ComposeNovelController(Activity activity,String accountID,String ownerID,NovelComposeState state,ImageButton tool,ViewGroup previewParent,int position,
			BooleanSupplier mixedEntity,Runnable changed,Runnable languageAction){
		this.activity=activity;this.accountID=accountID;this.ownerID=ownerID;this.state=state;this.tool=tool;this.mixedEntity=mixedEntity;this.changed=changed;this.languageAction=languageAction;
		preview=new LinearLayout(activity);preview.setOrientation(LinearLayout.VERTICAL);preview.setPadding(V.dp(16),V.dp(12),V.dp(16),V.dp(12));
		preview.setBackground(rounded(R.attr.colorM3SurfaceVariant,12));
		LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.setMargins(V.dp(16),0,V.dp(16),V.dp(16));previewParent.addView(preview,position,pp);
		LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);preview.addView(row,new LinearLayout.LayoutParams(-1,-2));
		cover=new ImageView(activity);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setOutlineProvider(OutlineProviders.roundedRect(6));cover.setClipToOutline(true);
		row.addView(cover,new LinearLayout.LayoutParams(V.dp(58),V.dp(80)));cover.setOnClickListener(v->detail());
		LinearLayout copy=new LinearLayout(activity);copy.setOrientation(LinearLayout.VERTICAL);copy.setPaddingRelative(V.dp(12),0,0,0);row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
		title=text(17,R.attr.colorM3OnSurface);title.setTypeface(ResourcesCompat.getFont(activity,R.font.noto_serif_sc_semibold));title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);title.setOnClickListener(v->detail());copy.addView(title);
		meta=text(12,R.attr.colorM3OnSurfaceVariant);copy.addView(meta);
		TextView source=text(11,R.attr.colorM3Primary);source.setText(R.string.novel_card_my_work);source.setPadding(0,V.dp(6),0,0);copy.addView(source);
		LinearLayout actions=new LinearLayout(activity);actions.setGravity(Gravity.END);preview.addView(actions);
		TextView replace=action(R.string.novel_compose_replace);replace.setOnClickListener(v->showPicker());actions.addView(replace);
		TextView remove=action(R.string.novel_compose_remove);remove.setOnClickListener(v->{state.remove();renderPreview();changed.run();});actions.addView(remove);
		tool.setOnClickListener(v->languageAction.run());
		tool.setOnLongClickListener(v->{languageAction.run();return true;});
		renderPreview();reload();
	}
	public void dispose(){disposed=true;generation++;coverGeneration++;for(MastodonAPIRequest<?> r:requests)r.cancel();requests.clear();if(picker!=null)picker.dismiss();cover.setImageDrawable(null);}
	private void track(MastodonAPIRequest<?> r){requests.add(r);}
	private boolean current(int g){return !disposed && generation==g && !activity.isFinishing();}
	private GradientDrawable rounded(int attr,int radius){GradientDrawable d=new GradientDrawable();d.setColor(UiUtils.getThemeColor(activity,attr));d.setCornerRadius(V.dp(radius));return d;}
	private TextView text(int sp,int attr){TextView v=new TextView(activity);v.setTextSize(sp);v.setTextColor(UiUtils.getThemeColor(activity,attr));return v;}
	private TextView action(int label){TextView v=text(14,R.attr.colorM3Primary);v.setText(label);v.setGravity(Gravity.CENTER);v.setMinHeight(V.dp(48));v.setPadding(V.dp(14),0,V.dp(14),0);v.setBackgroundResource(R.drawable.bg_rect_4dp_ripple);return v;}
	private GradientDrawable tintedSurface(float tint,int radius,boolean selected){
		GradientDrawable d=rounded(R.attr.colorM3Surface,radius);d.setColor(ColorUtils.blendARGB(UiUtils.getThemeColor(activity,R.attr.colorM3Surface),UiUtils.getThemeColor(activity,selected ? R.attr.colorM3Primary : R.attr.colorM3OnSurface),tint));return d;
	}
	private void styleOption(LinearLayout row,RadioButton option,boolean checked){
		option.setChecked(checked);int color=UiUtils.getThemeColor(activity,checked ? R.attr.colorM3Primary : R.attr.colorM3Outline);
		GradientDrawable background=tintedSurface(checked ? .10f : .045f,9,checked);background.setStroke(V.dp(1),UiUtils.getThemeColor(activity,checked ? R.attr.colorM3Primary : R.attr.colorM3OutlineVariant));row.setBackground(background);
		Drawable radio=activity.getDrawable(checked ? R.drawable.ic_novel_circle_check : R.drawable.ic_novel_circle).mutate();radio.setTint(color);radio.setBounds(0,0,V.dp(22),V.dp(22));option.setCompoundDrawablesRelative(null,null,radio,null);
	}
	private void detail(){if(state.hasNovel())NovelStatusDisplayItem.openDetail(activity,accountID,state.selectedId);}
	private void renderPreview(){
		preview.setVisibility(state.hasNovel() ? View.VISIBLE : View.GONE);coverGeneration++;
		NovelAttachment data=state.preview;boolean valid=data!=null && data.isDisplayable() && Objects.equals(data.workId,state.selectedId);
		title.setText(valid ? data.title : activity.getString(R.string.novel_compose_attached));
		meta.setText(valid ? (data.author.displayName==null || data.author.displayName.isBlank() ? data.author.username : data.author.displayName)+" 著\n"+(data.completed ? "已完结" : "连载中")+" · "+data.chapterCount+" 章 · "+data.wordCount+" 字" : "");
		String authorName=valid ? (TextUtils.isEmpty(data.author.displayName) ? data.author.username : data.author.displayName) : "";
		loadCover(cover,valid ? data.coverUrl : null,coverGeneration,true,new NovelCoverDrawable(activity,state.selectedId,valid ? data.title : null,authorName));
	}
	private void loadCover(ImageView image,String url,int g,boolean previewImage,Drawable placeholder){
		image.setImageTintList(null);image.setImageDrawable(placeholder);
		if(url==null || !(url.startsWith("https://") || url.startsWith("http://")))return;
		ViewImageLoader.load(new ViewImageLoader.Target(){
			@Override public View getView(){return image;}
			@Override public void setImageDrawable(Drawable d){
				if(disposed || previewImage && g!=coverGeneration || !previewImage && g!=generation)return;
				if(d!=null){image.setImageTintList(null);image.setImageDrawable(d);}
			}
		},null,new UrlImageLoaderRequest(url,V.dp(116),V.dp(160)),false,true);
	}
	private void updateTool(){
		boolean enabled=active && !publicWorks.isEmpty();
		tool.setImageResource(enabled ? R.drawable.ic_novel_rebuilt : R.drawable.ic_language_24px);
		tool.setContentDescription(activity.getString(enabled ? R.string.novel_compose_insert : R.string.language));
		tool.setTooltipText(tool.getContentDescription());
		tool.setOnClickListener(v->{if(enabled)showPicker();else languageAction.run();});
	}
	private void reload(){
		int g=++generation;active=false;publicWorks=List.of();updateTool();
		track(NovelCardRequest.creator().setCallback(new Callback<JsonObject>(){
			@Override public void onSuccess(JsonObject j){
				if(!current(g))return;
				active=j.has("active") && j.get("active").isJsonPrimitive() && j.getAsJsonPrimitive("active").isBoolean() && j.get("active").getAsBoolean();
				if(!active)return;
				loadWorks(g);
			}
			@Override public void onError(ErrorResponse e){if(current(g))updateTool();}
		}).exec(accountID));
		if(state.hasNovel() && (state.preview==null || !state.preview.isDisplayable())){
			String id=state.selectedId;
			if(!id.matches("[A-Za-z0-9_-]{1,128}"))return;
			track(NovelCardRequest.work(id).setCallback(new Callback<JsonObject>(){
				@Override public void onSuccess(JsonObject j){if(current(g) && Objects.equals(id,state.selectedId)){try{state.preview=attachment(decode(j));renderPreview();}catch(RuntimeException ignored){}}}
				@Override public void onError(ErrorResponse e){/* Keep reference untouched; removal remains available. */}
			}).exec(accountID));
		}
	}
	private Work decode(JsonObject json){try{return NovelJson.INSTANCE.work(new JSONObject(json.toString()));}catch(Exception e){throw new IllegalArgumentException("Invalid work projection",e);}}
	private boolean ownPublished(Work work){return Long.toString(work.getAuthor().getId()).equals(ownerID) && "published".equals(work.getStatus()) && work.getReleaseId()!=null && !work.getTitle().isBlank();}
	private void loadWorks(int g){
		track(NovelCardRequest.ownerWorks().setCallback(new Callback<JsonObject>(){
			@Override public void onSuccess(JsonObject j){
				if(!current(g))return;
				List<Work> candidates=new ArrayList<>();JsonElement items=j.get("items");
				if(items!=null && items.isJsonArray())for(JsonElement item:items.getAsJsonArray())try{Work w=decode(item.getAsJsonObject());if(ownPublished(w) && w.getId().matches("[A-Za-z0-9_-]{1,128}"))candidates.add(w);}catch(RuntimeException ignored){}
				if(candidates.isEmpty()){publicWorks=List.of();updateTool();return;}
				// Owner metadata includes withdrawn/private review state. Resolve through the public
				// visibility gate, at most four in flight instead of a fan-out for the whole library.
				verifyPublic(candidates,new ArrayList<>(),new int[]{0},new int[]{candidates.size()},g);
			}
			@Override public void onError(ErrorResponse e){if(current(g))updateTool();}
		}).exec(accountID));
	}
	private void verifyPublic(List<Work> candidates,List<Work> visible,int[] next,int[] remaining,int g){
		for(int i=0;i<Math.min(4,candidates.size());i++)verifyNext(candidates,visible,next,remaining,g);
	}
	private void verifyNext(List<Work> candidates,List<Work> visible,int[] next,int[] remaining,int g){
		if(!current(g) || next[0]>=candidates.size())return;
		Work candidate=candidates.get(next[0]++);
		track(NovelCardRequest.work(candidate.getId()).setCallback(new Callback<JsonObject>(){
			private void done(){
				if(!current(g))return;
				if(--remaining[0]==0){
					visible.sort(java.util.Comparator.comparingInt(w->candidates.stream().map(Work::getId).toList().indexOf(w.getId())));
					publicWorks=List.copyOf(visible);updateTool();
				}else verifyNext(candidates,visible,next,remaining,g);
			}
			@Override public void onSuccess(JsonObject publicWork){if(!current(g))return;try{Work w=decode(publicWork);if(ownPublished(w) && candidate.getId().equals(w.getId()))visible.add(w);}catch(RuntimeException ignored){}done();}
			@Override public void onError(ErrorResponse e){done();}
		}).exec(accountID));
	}
	public void showPicker(){
		if(disposed)return;
		if(mixedEntity.getAsBoolean()){Toast.makeText(activity,R.string.novel_compose_mixed,Toast.LENGTH_LONG).show();return;}
		if(!active || publicWorks.isEmpty()){Toast.makeText(activity,R.string.novel_compose_empty,Toast.LENGTH_LONG).show();return;}
		selected=publicWorks.stream().anyMatch(w->w.getId().equals(state.selectedId)) ? state.selectedId : null;
		LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(V.dp(20),V.dp(12),V.dp(20),V.dp(20));
		LinearLayout titleRow=new LinearLayout(activity);titleRow.setGravity(Gravity.CENTER_VERTICAL);content.addView(titleRow,new LinearLayout.LayoutParams(-1,-2));
		TextView heading=text(21,R.attr.colorM3OnSurface);heading.setText(R.string.novel_compose_insert);heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);androidx.core.view.ViewCompat.setAccessibilityHeading(heading,true);titleRow.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
		ImageButton close=new ImageButton(activity);close.setImageResource(R.drawable.ic_novel_x);close.setImageTintList(ColorStateList.valueOf(UiUtils.getThemeColor(activity,R.attr.colorM3OnSurfaceVariant)));close.setBackgroundResource(R.drawable.bg_rect_4dp_ripple);close.setPadding(V.dp(12),V.dp(12),V.dp(12),V.dp(12));close.setContentDescription(activity.getString(R.string.novel_compose_close));titleRow.addView(close,new LinearLayout.LayoutParams(V.dp(48),V.dp(48)));close.setOnClickListener(v->picker.dismiss());
		TextView scope=text(13,R.attr.colorM3OnSurfaceVariant);scope.setText(R.string.novel_compose_scope);scope.setPadding(0,V.dp(8),0,V.dp(18));content.addView(scope);
		EditText query=new EditText(activity);query.setSingleLine();query.setTextSize(14);query.setHint(R.string.novel_compose_search);query.setContentDescription(activity.getString(R.string.novel_compose_search));query.setTextColor(UiUtils.getThemeColor(activity,R.attr.colorM3OnSurface));query.setHintTextColor(UiUtils.getThemeColor(activity,R.attr.colorM3OnSurfaceVariant));query.setBackground(tintedSurface(.035f,9,false));query.setPadding(V.dp(12),0,V.dp(12),0);
		Drawable search=activity.getDrawable(R.drawable.ic_novel_search).mutate();search.setTint(UiUtils.getThemeColor(activity,R.attr.colorM3OnSurfaceVariant));search.setBounds(0,0,V.dp(20),V.dp(20));query.setCompoundDrawablesRelative(search,null,null,null);query.setCompoundDrawablePadding(V.dp(12));content.addView(query,new LinearLayout.LayoutParams(-1,V.dp(46)));
		ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.setClipToPadding(false);LinearLayout rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);LinearLayout.LayoutParams scrollParams=new LinearLayout.LayoutParams(-1,-2);scrollParams.topMargin=V.dp(18);content.addView(scroll,scrollParams);
		TextView hidden=text(12,R.attr.colorM3OnSurfaceVariant);hidden.setText(R.string.novel_compose_hidden);hidden.setPadding(0,V.dp(18),0,V.dp(18));hidden.setLineSpacing(V.dp(3),1);content.addView(hidden);
		View divider=new View(activity);divider.setBackgroundColor(UiUtils.getThemeColor(activity,R.attr.colorM3OutlineVariant));content.addView(divider,new LinearLayout.LayoutParams(-1,V.dp(1)));
		LinearLayout footer=new LinearLayout(activity);footer.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams footerParams=new LinearLayout.LayoutParams(-1,-2);footerParams.topMargin=V.dp(16);content.addView(footer,footerParams);
		TextView count=text(13,R.attr.colorM3OnSurfaceVariant);count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);footer.addView(count,new LinearLayout.LayoutParams(0,-2,1));
		picker=new M3AlertDialogBuilder(activity).setView(content).setPositiveButton(R.string.novel_compose_select,null).create();
		// Keep the actual dialog button (and confirm/revalidation path); move it into the design's footer.
		Button insert=picker.getButton(AlertDialog.BUTTON_POSITIVE);ViewGroup buttonBar=(ViewGroup)insert.getParent();buttonBar.removeView(insert);buttonBar.setVisibility(View.GONE);
		insert.setTextSize(14);insert.setTypeface(Typeface.DEFAULT,Typeface.BOLD);insert.setAllCaps(false);insert.setMinWidth(0);insert.setMinimumWidth(0);insert.setPadding(V.dp(20),0,V.dp(20),0);insert.setBackgroundTintList(null);insert.setBackground(rounded(R.attr.colorM3Primary,9));insert.setTextColor(UiUtils.getThemeColor(activity,R.attr.colorM3OnPrimary));footer.addView(insert,new LinearLayout.LayoutParams(-2,V.dp(48)));
		List<RadioButton> options=new ArrayList<>();
		Runnable render=()->{
			rows.removeAllViews();options.clear();String term=query.getText().toString().trim().toLowerCase(Locale.ROOT);int matches=0;
			for(Work work:publicWorks){
				if(!work.getTitle().toLowerCase(Locale.ROOT).contains(term))continue;matches++;
				LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(V.dp(12),V.dp(12),V.dp(12),V.dp(12));
				ImageView thumb=new ImageView(activity);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);thumb.setOutlineProvider(OutlineProviders.roundedRect(3));thumb.setClipToOutline(true);thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(thumb,new LinearLayout.LayoutParams(V.dp(44),V.dp(64)));
				String name=TextUtils.isEmpty(work.getAuthor().getDisplayName()) ? work.getAuthor().getUsername() : work.getAuthor().getDisplayName();loadCover(thumb,work.getCoverUrl(),generation,false,new NovelCoverDrawable(activity,work.getId(),work.getTitle(),name));
				RadioButton option=new RadioButton(activity);String label=work.getTitle()+"\n"+work.getStatusLabel()+" · "+work.getChapterCount()+" 章";SpannableString styled=new SpannableString(label);int end=work.getTitle().length();styled.setSpan(new StyleSpan(Typeface.BOLD),0,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);styled.setSpan(new RelativeSizeSpan(.84f),end,label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);styled.setSpan(new ForegroundColorSpan(UiUtils.getThemeColor(activity,R.attr.colorM3OnSurfaceVariant)),end,label.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				option.setText(styled);option.setContentDescription(label);option.setTextColor(UiUtils.getThemeColor(activity,R.attr.colorM3OnSurface));option.setTextSize(15);option.setGravity(Gravity.CENTER_VERTICAL);option.setMaxLines(3);option.setEllipsize(TextUtils.TruncateAt.END);option.setLineSpacing(V.dp(6),1);option.setButtonDrawable(null);option.setBackgroundColor(Color.TRANSPARENT);option.setPaddingRelative(V.dp(12),V.dp(4),0,V.dp(4));option.setCompoundDrawablePadding(V.dp(8));option.setMinHeight(V.dp(64));
				row.addView(option,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);if(matches>1)rowParams.topMargin=V.dp(12);rows.addView(row,rowParams);options.add(option);styleOption(row,option,work.getId().equals(selected));
				Runnable choose=()->{selected=work.getId();for(RadioButton b:options)styleOption((LinearLayout)b.getParent(),b,b==option);count.setText(activity.getString(R.string.novel_compose_select_count,1));insert.setEnabled(true);};
				option.setOnClickListener(v->choose.run());row.setOnClickListener(v->choose.run());
			}
			if(matches==0){TextView empty=text(14,R.attr.colorM3OnSurfaceVariant);empty.setText(R.string.novel_compose_no_results);empty.setGravity(Gravity.CENTER);empty.setMinHeight(V.dp(88));rows.addView(empty,new LinearLayout.LayoutParams(-1,-2));}
			// Fit the matching cards, with a viewport cap only for a long library, never a fixed empty hole.
			int cap=Math.max(104,Math.min(300,activity.getResources().getConfiguration().screenHeightDp-360));scroll.getLayoutParams().height=V.dp(matches==0 ? 88 : Math.min(cap,matches*88+Math.max(0,matches-1)*12));scroll.requestLayout();
			count.setText(activity.getString(R.string.novel_compose_select_count,selected==null ? 0 : 1));
		};
		query.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}@Override public void onTextChanged(CharSequence s,int start,int before,int count){render.run();}@Override public void afterTextChanged(Editable s){}});
		picker.setOnShowListener(d->{render.run();insert.setEnabled(selected!=null);insert.setOnClickListener(v->confirmSelection());});
		picker.show();
	}
	private void confirmSelection(){
		if(selected==null || mixedEntity.getAsBoolean())return;
		String id=selected;int g=generation;AlertDialog dialog=picker;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
		track(NovelCardRequest.work(id).setCallback(new Callback<JsonObject>(){
			@Override public void onSuccess(JsonObject j){
				if(!current(g) || picker!=dialog || !dialog.isShowing())return;
				try{Work w=decode(j);if(!ownPublished(w) || !id.equals(w.getId()))throw new IllegalArgumentException();
					if(mixedEntity.getAsBoolean())throw new IllegalArgumentException();
					state.select(id,attachment(w));renderPreview();changed.run();dialog.dismiss();
				}catch(RuntimeException invalid){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);Toast.makeText(activity,R.string.novel_card_unavailable,Toast.LENGTH_LONG).show();}
			}
			@Override public void onError(ErrorResponse e){if(current(g) && picker==dialog && dialog.isShowing()){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);e.showToast(activity);}}
		}).exec(accountID));
	}
	private static NovelAttachment attachment(Work work){
		NovelAttachment d=new NovelAttachment();d.schemaVersion=1;d.mode="link";d.available=true;d.workId=work.getId();d.releaseId=work.getReleaseId();d.title=work.getTitle();d.coverUrl=work.getCoverUrl();d.completed=work.getCompleted();d.chapterCount=work.getChapterCount();d.wordCount=work.getWordCount();
		d.author=new NovelAttachment.Author();d.author.id=work.getAuthor().getId();d.author.username=work.getAuthor().getUsername();d.author.displayName=work.getAuthor().getDisplayName();return d;
	}
	public static void restore(NovelComposeState state,Bundle saved){
		if(saved==null || !saved.containsKey("novelSelectedId"))return;
		state.selectedId=saved.getString("novelSelectedId");state.specified=saved.getBoolean("novelSpecified");state.preview=Parcels.unwrap(saved.getParcelable("novelPreview"));
	}
	public static void save(NovelComposeState state,Bundle out){out.putString("novelSelectedId",state.selectedId);out.putBoolean("novelSpecified",state.specified);out.putParcelable("novelPreview",Parcels.wrap(state.preview));}
}
