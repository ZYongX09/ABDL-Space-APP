package cn.jiguang.demo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import cn.jiguang.demo.baselibrary.ClipUtils;
import cn.jiguang.demo.baselibrary.BaseUtils;
import cn.jiguang.demo.baselibrary.ReceiverUtils;
import cn.jiguang.demo.view.TitleLayout;


public class AppInfoFragment extends Fragment {

    private static final String TAG = AppInfoFragment.class.getSimpleName();
    TextView didTv;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        ReceiverUtils.registerReceiver(getContext(), reciver, new IntentFilter("com.jiguang.demo.register"));
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.d_fragment_appinfo, container, false);
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TitleLayout titleLayout = view.findViewById(R.id.title_bar);
        titleLayout.getBackIv().setVisibility(View.GONE);

        TextView tvPkg = view.findViewById(R.id.tv_pkg);
        tvPkg.setText("包名："+getContext().getPackageName());
        view.findViewById(R.id.iv_pkg_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipUtils.copyText(v.getContext(), getContext().getPackageName());
            }
        });

        TextView appKeyTv = view.findViewById(R.id.tv_appKey_text);
        appKeyTv.setText(BaseUtils.getAppKey(getContext()));
        view.findViewById(R.id.iv_appkey_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipUtils.copyText(v.getContext(), BaseUtils.getAppKey(getContext()));
            }
        });

        TextView imeiTv = view.findViewById(R.id.tv_imei_text);
        imeiTv.setText(BaseUtils.getImei(getContext(),""));
        view.findViewById(R.id.iv_imei_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipUtils.copyText(v.getContext(), BaseUtils.getImei(getContext(),""));
            }
        });

        didTv = view.findViewById(R.id.tv_did_text);
        didTv.setText(BaseUtils.getDeviceId(getContext()));
        view.findViewById(R.id.iv_did_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipUtils.copyText(v.getContext(), BaseUtils.getDeviceId(getContext()));
            }
        });

        TextView versionTv = view.findViewById(R.id.tv_version_text);
        versionTv.setText("v"+ BaseUtils.getVersion(getContext()));
    }

    @Override
    public void onDestroyView() {
        ReceiverUtils.unregisterReceiver(getContext(), reciver);
        super.onDestroyView();
    }

    private final BroadcastReceiver reciver =  new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.e(TAG,"onReceive register");
            if(didTv!=null){
                didTv.setText(BaseUtils.getDeviceId(getContext()));
            }
        }
    };
}