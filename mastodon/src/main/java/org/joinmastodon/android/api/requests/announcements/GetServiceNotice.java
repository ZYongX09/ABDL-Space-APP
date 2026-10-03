package org.joinmastodon.android.api.requests.announcements;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.model.ServiceNotice;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 服务公告拉取 — 对应后端 GET https://api.abdl-space.top/api/broadcast/notice。
 * 该端点是全流程零数据库读取，D1 限流/事故期间仍可用；公告已由后端按时间窗口过滤，
 * 返回的 notice 即「当前应展示」的公告（或 null）。
 *
 * 不走 Mastodon 协议（无需 domain/鉴权），仿 GithubSelfUpdaterImpl 使用裸 OkHttp。
 */
public class GetServiceNotice{
	private static final String API_BASE_URL="https://api.abdl-space.top/api/broadcast/notice";
	private static final Gson GSON=new Gson();

	/**
	 * 同步拉取当前生效公告。
	 * @return 生效中的公告；无公告、请求失败或响应异常时返回 null（调用方静默处理）
	 */
	public static ServiceNotice fetch(){
		Request req=new Request.Builder()
				.url(API_BASE_URL)
				.build();
		Call call=MastodonAPIController.getHttpClient().newCall(req);
		try(Response resp=call.execute()){
			if(resp.body()==null)
				return null;
			JsonObject obj=JsonParser.parseString(resp.body().string()).getAsJsonObject();
			if(!obj.has("notice") || obj.get("notice").isJsonNull())
				return null;
			ServiceNotice notice=GSON.fromJson(obj.get("notice"), ServiceNotice.class);
			if(notice==null || notice.id==null || notice.id.isEmpty())
				return null;
			return notice;
		}catch(IOException|IllegalStateException x){
			return null;
		}
	}
}