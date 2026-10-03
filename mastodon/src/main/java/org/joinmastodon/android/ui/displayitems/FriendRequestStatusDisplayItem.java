package org.joinmastodon.android.ui.displayitems;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.E;
import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.friendrequests.DeleteFriendRequest;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.StatusDeletedEvent;
import org.joinmastodon.android.fragments.FriendRequestCreateFragment;
import org.joinmastodon.android.fragments.FriendRequestDetailFragment;
import org.joinmastodon.android.fragments.FriendRequestReportFragment;
import org.joinmastodon.android.model.FriendRequest;
import org.joinmastodon.android.model.FriendRequestField;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.FlowLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

public class FriendRequestStatusDisplayItem extends StatusDisplayItem{
	public final Status status;
	public final String accountID;

	// Metadata icon 映射（与交友宇宙列表页一致的定制图标）
	private static final String[][] METADATA_ICONS = {
		{"生理性别", "ic_field_gender"}, {"心理性别", "ic_field_gender_identity"},
		{"年龄", "ic_field_age"}, {"生日", "ic_field_birthday"},
		{"城市", "ic_field_city"}, {"QQ", "ic_field_qq"},
		{"微信", "ic_field_wechat"}, {"手机号", "ic_field_phone"},
		{"X(原推特)", "ic_field_twitter"}, {"Telegram", "ic_field_telegram"},
		{"博客", "ic_field_blog"}, {"宝宝新天地", "ic_field_nbw"},
		{"爱好", "ic_field_hobby"}, {"出生地", "ic_field_birthplace"},
		{"工作地", "ic_field_workplace"}, {"现居地", "ic_field_city"},
		{"性取向", "ic_field_orientation"}, {"会玩游戏", "ic_field_game"}
	};

	public FriendRequestStatusDisplayItem(String parentID, Callbacks callbacks, Context context, Status status, String accountID){
		super(parentID, callbacks, context);
		this.status=status;
		this.accountID=accountID;
	}

	@Override
	public Type getType(){
		return Type.FRIEND_REQUEST_ITEM;
	}

	private static String formatTime(String createdAt){
		if(createdAt==null) return "";
		try{
			SimpleDateFormat sdf=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
			sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
			Date date=sdf.parse(createdAt);
			long diff=System.currentTimeMillis()-date.getTime();
			long minutes=diff/60000;
			if(minutes<1) return "刚刚";
			if(minutes<60) return minutes+"分钟前";
			long hours=minutes/60;
			if(hours<24) return hours+"小时前";
			long days=hours/24;
			if(days<30) return days+"天前";
			if(days<365) return (days/30)+"个月前";
			return (days/365)+"年前";
		}catch(Exception e){
			return "";
		}
	}

	private static int getMetadataIconRes(Context context, String fieldKey){
		for(String[] mapping : METADATA_ICONS){
			if(mapping[0].equals(fieldKey)){
				return context.getResources().getIdentifier(mapping[1], "drawable", context.getPackageName());
			}
		}
		return 0;
	}

	/** 基础信息：年龄·性别·城市（交友卡片定制内容） */
	private static List<String> buildBasicParts(List<FriendRequestField> fields){
		String age=null, gender=null, city=null;
		if(fields!=null){
			for(FriendRequestField f : fields){
				if("年龄".equals(f.field_key)) age=f.field_value;
				else if("生理性别".equals(f.field_key)) gender=f.field_value;
				else if("城市".equals(f.field_key)) city=f.field_value;
			}
		}
		List<String> parts=new ArrayList<>();
		if(age!=null && !age.isBlank() && !"未知".equals(age)) parts.add(age.matches("\\d+") ? age+"岁" : age);
		if(gender!=null && !gender.isBlank() && !"未知".equals(gender)) parts.add(gender);
		if(city!=null && !city.isBlank() && !"未知".equals(city)) parts.add(city);
		return parts;
	}

	public static class Holder extends StatusDisplayItem.Holder<FriendRequestStatusDisplayItem>{
		private final ImageView avatar;
		private final TextView username, chip, title, desc, basicInfo, commentCount, publishTime, cta;
		private final FlowLayout metaContainer;
		private final ImageButton menuBtn;

		public Holder(Activity activity, ViewGroup parent){
			super(activity, R.layout.item_friend_request_timeline, parent);
			avatar=itemView.findViewById(R.id.fr_timeline_avatar);
			username=itemView.findViewById(R.id.fr_timeline_username);
			chip=itemView.findViewById(R.id.fr_timeline_chip);
			title=itemView.findViewById(R.id.fr_timeline_title);
			desc=itemView.findViewById(R.id.fr_timeline_desc);
			basicInfo=itemView.findViewById(R.id.fr_timeline_basic_info);
			commentCount=itemView.findViewById(R.id.fr_timeline_comment_count);
			publishTime=itemView.findViewById(R.id.fr_timeline_publish_time);
			cta=itemView.findViewById(R.id.fr_timeline_cta);
			metaContainer=itemView.findViewById(R.id.fr_timeline_meta_container);
			menuBtn=itemView.findViewById(R.id.fr_timeline_menu);
		}

		@Override
		public void onBind(FriendRequestStatusDisplayItem item){
			FriendRequest fr=item.status.friendRequest;
			if(fr==null) return;

			username.setText(fr.user!=null ? (fr.user.display_name!=null ? fr.user.display_name : fr.user.username) : "");
			chip.setText(fr.looking_for!=null ? fr.looking_for : "");
			chip.setVisibility(fr.looking_for==null || fr.looking_for.isBlank() ? View.GONE : View.VISIBLE);
			title.setText(fr.title);
			title.setVisibility(fr.title==null || fr.title.isBlank() ? View.GONE : View.VISIBLE);
			desc.setText(fr.description);
			desc.setVisibility(fr.description==null || fr.description.isBlank() ? View.GONE : View.VISIBLE);

			avatar.setImageResource(R.drawable.default_avatar);
			if(fr.user!=null && fr.user.avatar!=null && !fr.user.avatar.isEmpty()){
				avatar.setOutlineProvider(OutlineProviders.roundedRect(16));
				ViewImageLoader.loadWithoutAnimation(avatar, null, new UrlImageLoaderRequest(fr.user.avatar, V.dp(64), V.dp(64)));
			}

			List<String> basicParts=buildBasicParts(fr.fields);
			basicInfo.setText(String.join(" · ", basicParts));
			basicInfo.setVisibility(basicParts.isEmpty() ? View.GONE : View.VISIBLE);

			metaContainer.removeAllViews();
			if(fr.fields!=null){
				for(FriendRequestField f : fr.fields){
					if("年龄".equals(f.field_key) || "生理性别".equals(f.field_key) || "城市".equals(f.field_key)) continue;
					int iconRes=getMetadataIconRes(itemView.getContext(), f.field_key);
					if(iconRes!=0){
						ImageView icon=new ImageView(itemView.getContext());
						ViewGroup.LayoutParams iconParams=new ViewGroup.LayoutParams(V.dp(24), V.dp(24));
						icon.setLayoutParams(iconParams);
						icon.setPadding(0, 0, V.dp(10), 0);
						icon.setImageResource(iconRes);
						icon.setColorFilter(UiUtils.getThemeColor(itemView.getContext(), R.attr.colorM3OnSurfaceVariant));
						icon.setContentDescription(f.field_key);
						metaContainer.addView(icon);
					}
				}
			}

			commentCount.setText(R.string.friend_universe_branding);
			publishTime.setText(formatTime(fr.created_at));
			cta.setText(R.string.friend_request_chat_button);

			// Card 按压动画
			itemView.setOnTouchListener((v, event)->{
				switch(event.getAction()){
					case MotionEvent.ACTION_DOWN:
						v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
						v.setTranslationZ(V.dp(4));
						break;
					case MotionEvent.ACTION_UP:
					case MotionEvent.ACTION_CANCEL:
						v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
						v.setTranslationZ(0);
						break;
				}
				return false;
			});

			cta.setOnClickListener(v->openChat(item));
			menuBtn.setOnClickListener(v->showMenu(item));
		}

		@Override
		public void onClick(){
			Nav.go((Activity) item.context, FriendRequestDetailFragment.class, detailArgs(item));
		}

		/** 发私信：跳转到与该用户的私信会话页 */
		private void openChat(FriendRequestStatusDisplayItem item){
			FriendRequest fr=item.status.friendRequest;
			if(fr==null || fr.user==null) return;
			long peerId=0;
			try{
				peerId=Long.parseLong(fr.user_id!=null ? fr.user_id.replace(".0", "") : "");
			}catch(Exception ignored){}
			if(peerId<=0) return;
			Intent chatIntent=new Intent(item.context, MainActivity.class);
			chatIntent.putExtra("navigate_to", "chat");
			chatIntent.putExtra("peer_id", peerId);
			chatIntent.putExtra("peer_name", fr.user.display_name!=null ? fr.user.display_name : fr.user.username);
			chatIntent.putExtra("peer_avatar", fr.user.avatar);
			chatIntent.putExtra("account", item.accountID);
			item.context.startActivity(chatIntent);
		}

		private static Bundle detailArgs(FriendRequestStatusDisplayItem item){
			Bundle args=new Bundle();
			args.putString("account", item.accountID);
			args.putString("requestId", item.status.friendRequest.id);
			return args;
		}

		private void showMenu(FriendRequestStatusDisplayItem item){
			FriendRequest fr=item.status.friendRequest;
			String myUserId="";
			try{
				myUserId=String.valueOf(AccountSessionManager.getInstance().getAccount(item.accountID).self.id);
			}catch(Exception ignored){}
			String itemUserId=fr.user_id!=null ? fr.user_id.replace(".0", "") : "";
			boolean isOwner=itemUserId.equals(myUserId.replace(".0", ""));

			PopupMenu popup=new PopupMenu(menuBtn.getContext(), menuBtn);
			if(isOwner){
				popup.getMenu().add(0, 1, 0, "编辑");
				popup.getMenu().add(0, 2, 1, "删除");
			}
			popup.getMenu().add(0, 4, 2, "举报");
			popup.setOnMenuItemClickListener(menuItem->{
				int id=menuItem.getItemId();
				if(id==1){
					Bundle args=new Bundle();
					args.putString("account", item.accountID);
					args.putString("editRequestId", fr.id);
					args.putString("editTitle", fr.title);
					args.putString("editLookingFor", fr.looking_for);
					args.putString("editDescription", fr.description);
					if(fr.fields!=null){
						String[] keys=new String[fr.fields.size()];
						String[] values=new String[fr.fields.size()];
						for(int i=0;i<fr.fields.size();i++){
							keys[i]=fr.fields.get(i).field_key;
							values[i]=fr.fields.get(i).field_value;
						}
						args.putStringArray("editFieldKeys", keys);
						args.putStringArray("editFieldValues", values);
					}
					Nav.go((Activity) item.context, FriendRequestCreateFragment.class, args);
				}else if(id==2){
					new DeleteFriendRequest(fr.id)
						.setCallback(new Callback<Map<String, Object>>(){
							@Override
							public void onSuccess(Map<String, Object> result){
								Toast.makeText(item.context, "已删除", Toast.LENGTH_SHORT).show();
								E.post(new StatusDeletedEvent(item.status.id, item.accountID));
							}

							@Override
							public void onError(ErrorResponse error){
								error.showToast(item.context);
							}
						})
						.exec(item.accountID);
				}else if(id==4){
					Bundle args=new Bundle();
					args.putString("account", item.accountID);
					args.putString("requestId", fr.id);
					args.putString("requestTitle", fr.title);
					Nav.go((Activity) item.context, FriendRequestReportFragment.class, args);
				}
				return true;
			});
			popup.show();
		}
	}
}
