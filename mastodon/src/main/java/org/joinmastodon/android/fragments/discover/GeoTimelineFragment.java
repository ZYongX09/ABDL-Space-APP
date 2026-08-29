package org.joinmastodon.android.fragments.discover;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.timelines.GetGeoTimeline;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.StatusListFragment;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.utils.LocationUtils;
import org.joinmastodon.android.utils.ProvidesAssistContent;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.api.SimpleCallback;
import me.grishka.appkit.utils.MergeRecyclerAdapter;
import org.joinmastodon.android.ui.utils.HideableSingleViewRecyclerAdapter;

/** 同城时间线 — 展示指定省（可选市/区）的公开根帖 */
public class GeoTimelineFragment extends StatusListFragment implements ProvidesAssistContent.ProvidesWebUri{
	private static final int LOCATION_PERMISSION_REQUEST=7010;
	private String province, city, district;
	private HideableSingleViewRecyclerAdapter ipHintAdapter;
	private boolean hadPermissionOnPause=false;

	public static GeoTimelineFragment newInstance(String province){
		return newInstance(province, null, null);
	}

	public static GeoTimelineFragment newInstance(String province, String city, String district){
		GeoTimelineFragment f=new GeoTimelineFragment();
		Bundle args=new Bundle();
		args.putString("province", province);
		args.putString("city", city);
		args.putString("district", district);
		f.setArguments(args);
		return f;
	}

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		province=getArguments().getString("province");
		city=getArguments().getString("city");
		district=getArguments().getString("district");
	}

	@Override
	protected void doLoadData(int offset, int count){
		currentRequest=new GetGeoTimeline(province, city, district, getMaxID(), count)
				.setCallback(new SimpleCallback<>(this){
					@Override
					public void onSuccess(List<Status> result){
						if(getActivity()==null) return;
						boolean more=applyMaxID(result);
						AccountSessionManager.get(accountID).filterStatuses(result, getFilterContext());
						onDataLoaded(result, more);
						updateIpHintVisibility();
					}
				})
				.exec(accountID);
	}

	@Override
	public void onResume(){
		super.onResume();
		// 用户从系统设置/权限弹框回到 app 后，检测权限是否从无变有 → 触发 GPS 定位刷新
		boolean nowHasPermission=hasLocationPermission();
		if(nowHasPermission && !hadPermissionOnPause){
			triggerGpsRefresh();
		}
		hadPermissionOnPause=nowHasPermission;
	}

	@Override
	public void onPause(){
		super.onPause();
		hadPermissionOnPause=hasLocationPermission();
	}

	private void triggerGpsRefresh(){
		LocationUtils.fetchAndResolve(getActivity(), loc->{
			if(loc!=null){
				new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
					if(ipHintAdapter!=null) ipHintAdapter.setVisible(false);
					refresh();
				});
			}
		});
	}

	@Override
	protected RecyclerView.Adapter<?> getAdapter(){
		MergeRecyclerAdapter adapter=new MergeRecyclerAdapter();
		View hintView=getActivity().getLayoutInflater().inflate(R.layout.item_geo_ip_hint, list, false);
		TextView hintText=hintView.findViewById(R.id.text);
		hintText.setText(getString(R.string.geo_ip_hint_text, province));
		hintView.findViewById(R.id.button_dismiss).setOnClickListener(v->{ if(ipHintAdapter!=null) ipHintAdapter.setVisible(false); });
		hintView.findViewById(R.id.button_grant).setOnClickListener(v->requestLocationPermission());
		ipHintAdapter=new HideableSingleViewRecyclerAdapter(hintView);
		ipHintAdapter.setVisible(false);
		adapter.addAdapter(ipHintAdapter);
		adapter.addAdapter(super.getAdapter());
		return adapter;
	}

	/** 申请定位权限：非永久拒绝弹系统对话框；永久拒绝则跳系统设置页 */
	private void requestLocationPermission(){
		if(hasLocationPermission()){
			triggerGpsRefresh();
			return;
		}
		boolean shouldShow=ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION);
		if(shouldShow){
			// 用户拒绝过但未永久拒绝 → 弹系统权限对话框
			ActivityCompat.requestPermissions(getActivity(),
					new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
					LOCATION_PERMISSION_REQUEST);
		}else{
			// 首次请求 或 用户永久拒绝（USER_FIXED）→ 跳应用设置页让用户手动开启
			Toast.makeText(getActivity(), R.string.geo_ip_hint_open_settings_toast, Toast.LENGTH_LONG).show();
			Intent intent=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
			intent.setData(Uri.fromParts("package", getActivity().getPackageName(), null));
			startActivity(intent);
		}
	}

	/** 当缓存位置无 district（来自 IP 属地）且无定位权限时显示提示 */
	private void updateIpHintVisibility(){
		if(ipHintAdapter==null) return;
		boolean fromIP;
		if(hasLocationPermission()){
			fromIP=false;
		}else{
			LocationUtils.ResolvedLocation loc=LocationUtils.getCachedLocationForce(getActivity());
			fromIP=loc!=null && loc.district()==null;
		}
		ipHintAdapter.setVisible(fromIP);
	}

	private boolean hasLocationPermission(){
		return ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
				|| ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
	}

	@Override
	public Uri getWebUri(Uri.Builder base){
		return base.appendPath("timelines").appendPath("geo")
				.appendQueryParameter("province", province).build();
	}

	@Override
	protected FilterContext getFilterContext(){
		return FilterContext.PUBLIC;
	}
}
