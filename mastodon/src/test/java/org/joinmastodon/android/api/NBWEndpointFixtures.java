package org.joinmastodon.android.api;

import org.joinmastodon.android.api.session.NBWEndpointsRepository;
import org.joinmastodon.android.model.NBWEndpoints;

/** Test transport only: exercise the real discovery repository; never replace a production gate. */
public final class NBWEndpointFixtures implements AutoCloseable{
	private final NBWEndpointsRepository previous;
	public final NBWEndpointsRepository repository;
	private NBWEndpoints value;
	public NBWEndpointFixtures() throws Exception{
		value=endpoints("primary","endpoint-a","full");
		var field=NBWEndpointsRepository.class.getDeclaredField("instance"); field.setAccessible(true);
		previous=(NBWEndpointsRepository)field.get(null);
		repository=new NBWEndpointsRepository(result->{ result.success(value); return ()->{}; },android.os.SystemClock::elapsedRealtime,Runnable::run);
		field.set(null,repository); repository.refreshNow();
	}
	public static NBWEndpoints endpoints(String mode,String revision,String remote){
		String origin="backup".equals(mode) ? NBWEndpoints.BACKUP : NBWEndpoints.PRIMARY;
		return new NBWEndpoints(1,revision,mode,origin,origin,origin,origin,30,remote);
	}
	public static NBWEndpoints primary(){ return endpoints("primary","endpoint-a","full"); }
	public static org.joinmastodon.android.api.requests.nbw.NBWAppRequest.AuthorizationStart authorizationStart(){
		var result=new org.joinmastodon.android.api.requests.nbw.NBWAppRequest.AuthorizationStart();
		result.endpoints=primary(); result.expiresIn=600;
		result.flow="nbw-v2-flow."+"A".repeat(43)+"."+(System.currentTimeMillis()+600000L)+"."+"A".repeat(43);
		result.authorizationUrl=new android.net.Uri.Builder().scheme("https").authority("www.newbabyworld.top").path("/oauth/authorize.php")
				.appendQueryParameter("client_id","public-client").appendQueryParameter("redirect_uri",org.joinmastodon.android.api.requests.nbw.NBWLoginRequest.REDIRECT_URI)
				.appendQueryParameter("response_type","code").appendQueryParameter("state",result.flow).build().toString();
		return result;
	}
	public void publish(String mode,String revision,String remote){ value=endpoints(mode,revision,remote); repository.refreshNow(); }
	@Override public void close() throws Exception{
		var field=NBWEndpointsRepository.class.getDeclaredField("instance"); field.setAccessible(true); field.set(null,previous);
	}
}
