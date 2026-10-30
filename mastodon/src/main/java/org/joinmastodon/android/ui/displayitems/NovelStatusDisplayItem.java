package org.joinmastodon.android.ui.displayitems;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import androidx.core.graphics.ColorUtils;
import org.joinmastodon.android.ui.drawables.NovelCoverDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.res.ResourcesCompat;
import com.google.gson.JsonObject;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.statuses.NovelCardRequest;
import org.joinmastodon.android.model.NovelAttachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.UiUtils;
import java.util.List;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.imageloader.ImageLoaderViewHolder;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** Native enhancement inside the ordinary post/CW/filter chain; timeline palette stays untouched. */
public final class NovelStatusDisplayItem extends StatusDisplayItem{
	public static final String FALLBACK="【ABDL Space 小说】当前渠道暂不支持该功能，请更新最新版APP体验该功能";
	public enum Presentation{ NONE, NEW_BOOK, CHAPTER_UPDATE, LINK, UNAVAILABLE }
	public final NovelAttachment attachment;
	public final Presentation presentation;
	public final String accountID;
	private final boolean hideCover;
	private final Status concealmentOwner;
	private final ImageLoaderRequest coverRequest;

	public NovelStatusDisplayItem(String parentID,Callbacks callbacks,Context context,Status status,String accountID,boolean hideCover){
		this(parentID,callbacks,context,status,accountID,hideCover,status);
	}
	public NovelStatusDisplayItem(String parentID,Callbacks callbacks,Context context,Status status,String accountID,boolean hideCover,Status concealmentOwner){
		super(parentID,callbacks,context); this.status=status; this.attachment=status.novel;
		this.presentation=presentation(attachment); this.accountID=accountID; this.hideCover=hideCover; this.concealmentOwner=concealmentOwner;
		String cover=attachment==null ? null : attachment.coverUrl;
		coverRequest=cover!=null && (cover.startsWith("https://") || cover.startsWith("http://"))
				? new UrlImageLoaderRequest(cover,V.dp(160),V.dp(220)) : null;
	}
	public static Presentation presentation(NovelAttachment data){
		if(data==null || !data.isSupported()) return Presentation.NONE;
		if(!data.available) return Presentation.UNAVAILABLE;
		if(!data.isDisplayable()) return Presentation.NONE;
		return switch(data.mode){ case "new_book" -> Presentation.NEW_BOOK; case "chapter_update" -> Presentation.CHAPTER_UPDATE; default -> Presentation.LINK; };
	}
	public static String contentWithoutFallback(String content,NovelAttachment data){
		Presentation p=presentation(data);
		if(content==null || p==Presentation.NONE || p==Presentation.UNAVAILABLE) return content;
		// Only exact terminal wire forms, no trim, substring search, HTML normalization or body-marker trust.
		String html="<p>"+FALLBACK+"</p>";
		if(content.endsWith(html)) return content.substring(0,content.length()-html.length());
		String plain="\n\n"+FALLBACK;
		if(content.endsWith(plain)) return content.substring(0,content.length()-plain.length());
		if(content.equals(FALLBACK)) return "";
		return content;
	}
	public static String chapterSummary(NovelAttachment data){
		List<NovelAttachment.Chapter> chapters=data.chapters;
		if(chapters==null || chapters.isEmpty()) return "共 "+data.chapterCount+" 章 · "+data.wordCount+" 字";
		long words=0; for(NovelAttachment.Chapter c:chapters) words=words>Long.MAX_VALUE-c.wordCount ? Long.MAX_VALUE : words+c.wordCount;
		String first=chapters.get(0).title;
		String range=chapters.size()==1 ? first : first+" — "+chapters.get(chapters.size()-1).title+"（"+chapters.size()+" 章）";
		return range+"\n本次新增 "+words+" 字";
	}
	private ImageLoaderRequest permittedCoverRequest(){
		if(hideCover || status.novel!=attachment || presentation(attachment)!=presentation || presentation==Presentation.NONE || presentation==Presentation.UNAVAILABLE
				|| status.sensitive || status.visibility==StatusPrivacy.PRIVATE || status.visibility==StatusPrivacy.DIRECT
				|| (!TextUtils.isEmpty(status.spoilerText) && !concealmentOwner.revealedSpoilers.contains(Status.SpoilerType.CONTENT_WARNING))
				|| filtered(concealmentOwner)) return null;
		return coverRequest instanceof UrlImageLoaderRequest r && r.uri.toString().equals(attachment.coverUrl) ? r : null;
	}
	private static boolean filtered(Status status){
		return status.filtered!=null && !status.revealedSpoilers.contains(Status.SpoilerType.FILTER)
				&& status.filtered.stream().anyMatch(f->f!=null && f.filter!=null && f.filter.isActive());
	}
	private CoverImageBinding.Key coverKey(){
		ImageLoaderRequest request=permittedCoverRequest();
		return request==null ? null : new CoverImageBinding.Key(accountID,parentID,attachment.workId,presentation,request.getMemoryCacheKey());
	}
	@Override public Type getType(){ return Type.NOVEL; }
	@Override public int getImageCount(){ return permittedCoverRequest()==null ? 0 : 1; }
	@Override public ImageLoaderRequest getImageRequest(int index){ return index==0 ? permittedCoverRequest() : null; }

	public static void openDetail(Context context,String accountID,String workID){ open(context,"org.joinmastodon.android.novel.app.NovelAppActivity",accountID,workID,null); }
	public static void openReader(Context context,String accountID,String workID,String chapterID){ open(context,"org.joinmastodon.android.novel.app.editor.NovelReaderActivity",accountID,workID,chapterID); }
	private static void open(Context context,String activity,String accountID,String workID,String chapterID){
		if(accountID==null || workID==null) return;
		Intent intent=new Intent().setClassName(context,activity).putExtra("account",accountID).putExtra("work_id",workID);
		if(chapterID!=null && !chapterID.isBlank()) intent.putExtra("chapter_id",chapterID);
		if(!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}

	public static final class Holder extends StatusDisplayItem.Holder<NovelStatusDisplayItem> implements ImageLoaderViewHolder{
		private final FrameLayout wrapper;
		private final LinearLayout card,body,headingRow,footer,header;
		private final ImageView cover,coverFallback,headerIcon,chevron;
		private final ImageButton shelf;
		private final TextView badge,title,author,meta,description,chapters,chapterWords,excerpt,read,source;
		private final View chapterDivider,linkDivider;
		private final CoverImageBinding coverBinding=new CoverImageBinding();
		private NovelStatusDisplayItem bound;
		private MastodonAPIRequest<?> shelfRequest;
		private int shelfGeneration;
		private Boolean collected;
		public Holder(Activity activity,ViewGroup parent){
			super(new FrameLayout(activity)); wrapper=(FrameLayout)itemView;
			wrapper.setLayoutParams(new ViewGroup.LayoutParams(-1,-2));
			card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
			GradientDrawable surface=rounded(activity,R.attr.colorM3Surface,12,true);
			surface.setColor(ColorUtils.blendARGB(UiUtils.getThemeColor(activity,R.attr.colorM3Surface),UiUtils.getThemeColor(activity,R.attr.colorM3OnSurface),.055f));
			card.setBackground(surface);card.setOutlineProvider(OutlineProviders.roundedRect(12));card.setClipToOutline(true);wrapper.addView(card,new FrameLayout.LayoutParams(-1,-2));
			header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(V.dp(14),0,V.dp(14),0);
			header.setBackgroundColor(ColorUtils.blendARGB(UiUtils.getThemeColor(activity,R.attr.colorM3Surface),UiUtils.getThemeColor(activity,R.attr.colorM3Primary),.13f));
			card.addView(header,new LinearLayout.LayoutParams(-1,V.dp(38)));
			headerIcon=new ImageView(activity);headerIcon.setImageTintList(ColorStateList.valueOf(UiUtils.getThemeColor(activity,R.attr.colorM3Primary)));headerIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);header.addView(headerIcon,new LinearLayout.LayoutParams(V.dp(18),V.dp(18)));
			badge=text(activity,12,R.attr.colorM3Primary,1);badge.setTypeface(Typeface.DEFAULT,Typeface.BOLD);LinearLayout.LayoutParams badgeParams=new LinearLayout.LayoutParams(-2,-2);badgeParams.setMarginStart(V.dp(8));header.addView(badge,badgeParams);
			body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(V.dp(14),V.dp(14),V.dp(14),V.dp(14));card.addView(body,new LinearLayout.LayoutParams(-1,-2));
			headingRow=new LinearLayout(activity);headingRow.setGravity(Gravity.CENTER_VERTICAL);body.addView(headingRow,new LinearLayout.LayoutParams(-1,-2));
			FrameLayout portrait=new FrameLayout(activity);portrait.setOutlineProvider(OutlineProviders.roundedRect(3));portrait.setClipToOutline(true);
			headingRow.addView(portrait,new LinearLayout.LayoutParams(V.dp(68),V.dp(96)));
			coverFallback=new ImageView(activity);coverFallback.setScaleType(ImageView.ScaleType.FIT_XY);coverFallback.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);portrait.addView(coverFallback,new FrameLayout.LayoutParams(-1,-1));
			cover=new ImageView(activity);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setVisibility(View.GONE);cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			portrait.addView(cover,new FrameLayout.LayoutParams(-1,-1));portrait.setOnClickListener(v->onClick());
			LinearLayout copy=new LinearLayout(activity);copy.setOrientation(LinearLayout.VERTICAL);copy.setPaddingRelative(V.dp(13),0,0,0);headingRow.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
			title=text(activity,20,R.attr.colorM3OnSurface,2);title.setTypeface(ResourcesCompat.getFont(activity,R.font.noto_serif_sc_semibold));title.setIncludeFontPadding(false);title.setLineSpacing(V.dp(2),1);title.setOnClickListener(v->onClick());copy.addView(title);
			author=text(activity,12,R.attr.colorM3OnSurfaceVariant,1);author.setPadding(0,V.dp(8),0,0);copy.addView(author);
			meta=text(activity,12,R.attr.colorM3OnSurfaceVariant,3);meta.setPadding(0,V.dp(9),0,0);meta.setLineSpacing(V.dp(6),1);copy.addView(meta);
			chevron=new ImageView(activity);chevron.setImageResource(R.drawable.ic_novel_chevron_right);chevron.setImageTintList(ColorStateList.valueOf(UiUtils.getThemeColor(activity,R.attr.colorM3OnSurfaceVariant)));chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);headingRow.addView(chevron,new LinearLayout.LayoutParams(V.dp(18),V.dp(24)));
			description=text(activity,14,R.attr.colorM3OnSurfaceVariant,3);description.setPadding(0,V.dp(16),0,0);description.setLineSpacing(V.dp(4),1);body.addView(description);
			chapterDivider=divider(activity);LinearLayout.LayoutParams chapterRule=new LinearLayout.LayoutParams(-1,V.dp(1));chapterRule.topMargin=V.dp(16);chapterRule.bottomMargin=V.dp(14);body.addView(chapterDivider,chapterRule);
			chapters=text(activity,16,R.attr.colorM3OnSurface,3);chapters.setTypeface(Typeface.DEFAULT,Typeface.BOLD);body.addView(chapters);
			chapterWords=text(activity,12,R.attr.colorM3OnSurfaceVariant,1);chapterWords.setPadding(0,V.dp(10),0,0);body.addView(chapterWords);
			excerpt=text(activity,14,R.attr.colorM3OnSurfaceVariant,3);excerpt.setTypeface(ResourcesCompat.getFont(activity,R.font.noto_serif_sc_regular));excerpt.setLineSpacing(V.dp(5),1);excerpt.setPadding(0,V.dp(12),0,0);body.addView(excerpt,new LinearLayout.LayoutParams(-1,-2));
			linkDivider=divider(activity);LinearLayout.LayoutParams linkRule=new LinearLayout.LayoutParams(-1,V.dp(1));linkRule.topMargin=V.dp(12);body.addView(linkDivider,linkRule);
			footer=new LinearLayout(activity);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(0,V.dp(16),0,0);body.addView(footer,new LinearLayout.LayoutParams(-1,-2));
			source=text(activity,12,R.attr.colorM3OnSurfaceVariant,1);source.setText(R.string.novel_card_my_work);footer.addView(source,new LinearLayout.LayoutParams(0,-2,1));
			read=text(activity,14,R.attr.colorM3OnPrimary,1);read.setGravity(Gravity.CENTER);read.setMinHeight(V.dp(48));read.setTypeface(Typeface.DEFAULT,Typeface.BOLD);footer.addView(read,new LinearLayout.LayoutParams(0,-2,1));read.setOnClickListener(v->read());
			shelf=new ImageButton(activity);shelf.setScaleType(ImageView.ScaleType.CENTER_INSIDE);shelf.setPadding(V.dp(12),V.dp(12),V.dp(12),V.dp(12));shelf.setImageTintList(ColorStateList.valueOf(UiUtils.getThemeColor(activity,R.attr.colorM3OnSurfaceVariant)));shelf.setBackground(rounded(activity,R.attr.colorM3Surface,9,true));
			LinearLayout.LayoutParams shelfParams=new LinearLayout.LayoutParams(V.dp(48),V.dp(48));shelfParams.setMarginStart(V.dp(8));footer.addView(shelf,shelfParams);shelf.setOnClickListener(v->toggleShelf());
			card.setOnClickListener(v->onClick());
		}
		private static TextView text(Context c,int sp,int attr,int lines){
			TextView v=new TextView(c);v.setTextSize(sp);v.setTextColor(UiUtils.getThemeColor(c,attr));v.setMaxLines(lines);v.setEllipsize(TextUtils.TruncateAt.END);return v;
		}
		private static View divider(Context c){View v=new View(c);v.setBackgroundColor(UiUtils.getThemeColor(c,R.attr.colorM3OutlineVariant));return v;}
		private static GradientDrawable rounded(Context c,int attr,int radius,boolean border){
			GradientDrawable d=new GradientDrawable();d.setColor(UiUtils.getThemeColor(c,attr));d.setCornerRadius(V.dp(radius));
			if(border)d.setStroke(V.dp(1),UiUtils.getThemeColor(c,R.attr.colorM3OutlineVariant));return d;
		}
		@Override public void onBind(NovelStatusDisplayItem next){
			if(shelfRequest!=null)shelfRequest.cancel(); shelfRequest=null; shelfGeneration++; collected=null; bound=next;
			wrapper.setPaddingRelative(V.dp(next.fullWidth ? 16 : 64),0,V.dp(16),V.dp(10));
			NovelAttachment data=next.attachment; boolean available=data!=null && data.isDisplayable() && next.presentation!=Presentation.NONE;
			boolean link=next.presentation==Presentation.LINK, update=next.presentation==Presentation.CHAPTER_UPDATE;
			header.setVisibility(available && !link ? View.VISIBLE : View.GONE);badge.setText(update ? R.string.novel_card_update : R.string.novel_card_new_book);
			headerIcon.setImageResource(update ? R.drawable.ic_novel_pen_line : R.drawable.ic_novel_book_open);
			title.setText(available ? data.title : wrapper.getContext().getString(R.string.novel_card_unavailable));title.setTextSize(link ? 18 : 20);
			String authorName=available ? data.getDisplayAuthorName() : "";
			bind(author,available && link ? authorName+" 著" : null);
			String type=available ? switch(data.workType==null ? "" : data.workType){case "original" -> "原创";case "repost" -> "授权转载";case "ai" -> "AI 辅助";default -> "作品";} : "";
			String status=available ? (data.completed ? "已完结" : "连载中") : "";
			meta.setText(!available ? "" : link ? status+" · "+data.chapterCount+" 章 · "+data.wordCount+" 字" : type+" · "+status+"\n"+(update ? "已更新至第 "+data.chapterCount+" 章" : "首发 "+data.chapterCount+" 章 · "+data.wordCount+" 字"));
			meta.setVisibility(available ? View.VISIBLE : View.GONE);chevron.setVisibility(available && link ? View.VISIBLE : View.GONE);
			View portrait=(View)cover.getParent();portrait.setVisibility(available ? View.VISIBLE : View.GONE);LinearLayout.LayoutParams portraitParams=(LinearLayout.LayoutParams)portrait.getLayoutParams();portraitParams.width=V.dp(link ? 52 : 68);portraitParams.height=V.dp(link ? 74 : 96);portrait.setLayoutParams(portraitParams);
			coverFallback.setImageDrawable(available ? new NovelCoverDrawable(wrapper.getContext(),data.workId,data.title,authorName) : null);
			bind(description,available && !link && !update ? data.description : null);
			String summary=available && update ? chapterSummary(data) : null;int split=summary==null ? -1 : summary.indexOf('\n');
			bind(chapters,split<0 ? summary : summary.substring(0,split));bind(chapterWords,split<0 ? null : summary.substring(split+1));chapterDivider.setVisibility(available && update ? View.VISIBLE : View.GONE);
			bind(excerpt,available && update ? data.excerpt : null);
			footer.setVisibility(available ? View.VISIBLE : View.GONE);shelf.setVisibility(link ? View.GONE : View.VISIBLE);source.setVisibility(link ? View.VISIBLE : View.GONE);linkDivider.setVisibility(available && link ? View.VISIBLE : View.GONE);
			footer.setPadding(0,V.dp(link ? 2 : 16),0,0);body.setPadding(V.dp(14),V.dp(14),V.dp(14),V.dp(link ? 2 : 14));
			read.setText(link ? R.string.novel_card_read_link : update ? R.string.novel_card_read_update : R.string.novel_card_read_new);read.setTextSize(link ? 13 : 14);read.setTextColor(UiUtils.getThemeColor(wrapper.getContext(),link ? R.attr.colorM3Primary : R.attr.colorM3OnPrimary));
			read.setGravity(link ? Gravity.CENTER_VERTICAL|Gravity.END : Gravity.CENTER);read.setPadding(V.dp(link ? 6 : 12),0,V.dp(link ? 0 : 12),0);read.setBackground(link ? null : rounded(wrapper.getContext(),R.attr.colorM3Primary,9,false));
			Drawable arrow=wrapper.getContext().getDrawable(R.drawable.ic_novel_arrow_right).mutate();arrow.setTint(read.getCurrentTextColor());arrow.setBounds(0,0,V.dp(18),V.dp(18));read.setCompoundDrawablesRelative(null,null,arrow,null);read.setCompoundDrawablePadding(V.dp(6));
			LinearLayout.LayoutParams readParams=(LinearLayout.LayoutParams)read.getLayoutParams();readParams.width=link ? -2 : 0;readParams.weight=link ? 0 : 1;read.setLayoutParams(readParams);
			updateShelf(false);shelf.setEnabled(true);
			card.setEnabled(available);card.setClickable(available);title.setClickable(available);
			Drawable image=coverBinding.bind(wrapper.getContext(),next.coverKey(),next.permittedCoverRequest());
			if(image==null)clearImage(0);else showCover(image);
		}
		private void bind(TextView v,String text){v.setText(text==null ? "" : text);v.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);}
		private void updateShelf(boolean inShelf){
			shelf.setImageResource(inShelf ? R.drawable.ic_novel_bookmark_check : R.drawable.ic_novel_bookmark_plus);
			shelf.setContentDescription(wrapper.getContext().getString(inShelf ? R.string.novel_card_shelf_remove : R.string.novel_card_shelf_add));shelf.setTooltipText(shelf.getContentDescription());
		}
		@Override public void onClick(){
			if(bound!=null && bound.attachment!=null && bound.attachment.isDisplayable()) openDetail(wrapper.getContext(),bound.accountID,bound.attachment.workId);
		}
		private void read(){
			if(bound==null || !bound.attachment.isDisplayable()) return;
			if(bound.presentation==Presentation.LINK) openReader(wrapper.getContext(),bound.accountID,bound.attachment.workId,null);
			else openDetail(wrapper.getContext(),bound.accountID,bound.attachment.workId);
		}
		private void toggleShelf(){
			NovelStatusDisplayItem current=bound; if(current==null || !current.attachment.isDisplayable() || !shelf.isEnabled())return;
			int generation=++shelfGeneration; shelf.setEnabled(false);
			// Unknown initial shelf state means ADD, as the button says. The idempotent setter must
			// never remove an already-collected work merely because its projection omitted in_shelf.
			writeShelf(current,generation,collected==null || !collected);
		}
		private void writeShelf(NovelStatusDisplayItem current,int generation,boolean desired){
			shelfRequest=NovelCardRequest.shelf(current.attachment.workId,desired).setCallback(new Callback<JsonObject>(){
				@Override public void onSuccess(JsonObject result){
					if(!current(current,generation))return;
					if(!result.has("collected") || !result.get("collected").isJsonPrimitive() || !result.getAsJsonPrimitive("collected").isBoolean()){
						shelf.setEnabled(true);return;
					}
					collected=result.get("collected").getAsBoolean();updateShelf(collected);shelf.setEnabled(true);
					Toast.makeText(wrapper.getContext(),collected ? "已加入书架" : "已移出书架",Toast.LENGTH_SHORT).show();
				}
				@Override public void onError(ErrorResponse e){failure(current,generation,e);}
			}).exec(current.accountID);
		}
		private boolean current(NovelStatusDisplayItem expected,int generation){return bound==expected && generation==shelfGeneration && expected.attachment.isDisplayable();}
		private void failure(NovelStatusDisplayItem expected,int generation,ErrorResponse error){if(current(expected,generation)){shelf.setEnabled(true);error.showToast(wrapper.getContext());}}
		@Override public void setImage(int index,Drawable image){
			if(index!=0)return;
			if(bound==null || !coverBinding.matches(bound.coverKey())){clearImage(0);return;}
			showCover(coverBinding.loaded(image));
		}
		private void showCover(Drawable image){cover.setImageDrawable(image);cover.setVisibility(image==null ? View.GONE : View.VISIBLE);coverFallback.setVisibility(image==null ? View.VISIBLE : View.GONE);}
		@Override public void clearImage(int index){if(index==0){coverBinding.clear();showCover(null);}}
		@Override public void onImageLoadingFailed(int index,Throwable error){if(index==0){clearImage(0);if(bound!=null && coverBinding.shouldRetry(bound.coverKey()))bound.callbacks.scheduleRetryFailedImages();}}
	}
}
