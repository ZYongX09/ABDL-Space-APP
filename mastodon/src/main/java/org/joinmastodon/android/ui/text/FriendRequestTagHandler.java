package org.joinmastodon.android.ui.text;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import org.joinmastodon.android.R;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 处理帖子中的 [交友]id[/交友] 标签
 * 将其渲染为可点击的交友卡片样式
 */
public class FriendRequestTagHandler {
	private static final Pattern FRIEND_REQUEST_PATTERN = Pattern.compile("\\[交友\\](\\d+)\\[/交友\\]");
	
	/**
	 * 检测并处理帖子内容中的交友标签
	 * @param source 原始帖子内容
	 * @param context 上下文
	 * @return 处理后的 SpannableStringBuilder
	 */
	public static SpannableStringBuilder processFriendRequestTags(String source, Context context) {
		if (source == null || !source.contains("[交友]")) {
			return null;
		}
		
		SpannableStringBuilder ssb = new SpannableStringBuilder(source);
		Matcher matcher = FRIEND_REQUEST_PATTERN.matcher(source);
		
		while (matcher.find()) {
			String friendRequestId = matcher.group(1);
			int start = matcher.start();
			int end = matcher.end();
			
			// 创建可点击的 span
			ClickableSpan clickSpan = new ClickableSpan() {
				@Override
				public void onClick(View widget) {
					// 点击时跳转到交友详情页
					// 这里需要通过回调或其他方式传递事件
				}
			};
			
			// 设置 span
			ssb.setSpan(clickSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			
			// 设置颜色（品牌蓝色）
			ssb.setSpan(new ForegroundColorSpan(context.getResources().getColor(android.R.color.holo_blue_dark)),
				start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		
		return ssb;
	}
	
	/**
	 * 检查帖子内容是否包含交友标签
	 */
	public static boolean containsFriendRequestTag(String content) {
		return content != null && content.contains("[交友]");
	}
	
	/**
	 * 获取帖子中的所有交友请求 ID
	 */
	public static List<String> extractFriendRequestIds(String content) {
		List<String> ids = new ArrayList<>();
		if (content == null) return ids;
		
		Matcher matcher = FRIEND_REQUEST_PATTERN.matcher(content);
		while (matcher.find()) {
			ids.add(matcher.group(1));
		}
		return ids;
	}
}
