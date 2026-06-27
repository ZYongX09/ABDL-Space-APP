package cn.jiguang.demo.joperate.ui.main;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;

import java.util.Map;

import cn.jiguang.demo.R;
import cn.jiguang.demo.joperate.data.Data;
import cn.jiguang.joperate.api.JOperateInterface;

public class ProjectFragment extends Fragment implements View.OnClickListener {

    private static final String TAG = "ProjectFragment";

    private MainViewModel mViewModel;

    TextView text_project;
    TextView text_project_ID;
    TextView app_name;
    TextView appkey;
    TextView package_name;
    TextView text_version;


    public static ProjectFragment newInstance() {
        return new ProjectFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.joperate_project_fragment, container, false);
        text_project = view.findViewById(R.id.text_project);
        text_project_ID = view.findViewById(R.id.text_project_ID);
        app_name = view.findViewById(R.id.app_name);
        appkey = view.findViewById(R.id.appkey);
        package_name = view.findViewById(R.id.package_name);
        text_version = view.findViewById(R.id.text_version);
        view.findViewById(R.id.copy_id).setOnClickListener(this);
        view.findViewById(R.id.back).setOnClickListener(this);

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mViewModel = ViewModelProviders.of(getActivity()).get(MainViewModel.class);
        Log.d(TAG, "mViewModel:" + mViewModel);
        mViewModel.mMap.observe(this, new Observer<Map<String, Object>>() {
            @Override
            public void onChanged(@Nullable Map<String, Object> map) {
                onChangedView(map);
            }
        });

        updataData();
    }


    private void updataData() {

        Data.Project project = mViewModel.project;
        if (null != project) {
            text_project_ID.setText(project.id);
            text_project.setText(project.name);
        }

        text_version.setText(JOperateInterface.getVersion());
        package_name.setText(getContext().getPackageName());
        appkey.setText(Utils.getAppKey(getContext()));
        app_name.setText(Utils.getAPPName(getContext()));

    }

    private void onChangedView(Map<String, Object> map) {
        Log.d(TAG, "onChangedView map:" + map);
        for (String key : map.keySet()) {
            if (MainViewModel.MAP_TYPE_ON_REFRESH.equals(key)) {
                Log.d(TAG, "MAP_TYPE_ON_REFRESH:");
                JOperateInterface.getInstance(getActivity()).testDemo(1, new JOperateInterface.CallBack() {
                    @Override
                    public void onCallBack(final int code, final String msg) {
                        Activity activity = getActivity();
                        if (null == activity){
                            mViewModel.setRefreshing(false);
                            return;
                        }
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Log.d(TAG, "run: code=" + code);
                                Log.d(TAG, "run: msg=" + msg);
//                                if (true) {
                                if (0 == code) {
                                    mViewModel.setProject(msg);
                                    mViewModel.setMap(MainViewModel.MAP_TYPE_PROJECT_VALUE, true);
                                } else {
                                    ToastCustom.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                                }
                                mViewModel.setRefreshing(false);
                            }
                        });
                    }
                });
            } else if (MainViewModel.MAP_TYPE_PROJECT_VALUE.equals(key)) {
                Log.d(TAG, "MAP_TYPE_PROJECT_VALUE:");
                updataData();
                mViewModel.setRefreshing(false);
            }
        }
    }


    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.copy_id:
                Utils.copy(getContext(), text_project_ID.getText().toString());
                ToastCustom.makeText(getContext(), "内容已复制", Toast.LENGTH_LONG).show();
                break;
            case R.id.back:
                Log.d(TAG, "onClick back");
                getFragmentManager().popBackStack();
                break;
        }
    }

}