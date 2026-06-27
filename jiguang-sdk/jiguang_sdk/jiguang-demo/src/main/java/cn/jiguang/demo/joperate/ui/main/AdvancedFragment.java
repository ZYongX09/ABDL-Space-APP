package cn.jiguang.demo.joperate.ui.main;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Map;

import cn.jiguang.demo.R;
import cn.jiguang.demo.joperate.data.Data;
import cn.jiguang.demo.joperate.data.adapter.AdvanceAdapter;
import cn.jiguang.joperate.api.JOperateInterface;


public class AdvancedFragment extends Fragment implements View.OnClickListener {

    private static final String TAG = "AdvancedFragment";
    private MainViewModel mViewModel;
    AdvanceAdapter adapter;

    public static AdvancedFragment newInstance() {
        return new AdvancedFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.joperate_advanced_fragment, container, false);
        view.findViewById(R.id.back).setOnClickListener(this);


        RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);
        adapter = new AdvanceAdapter(getContext());
//        adapter.setData(Data.getTest());
        recyclerView.setAdapter(adapter);

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mViewModel = ViewModelProviders.of(getActivity()).get(MainViewModel.class);
        mViewModel.mMap.observe(this, new Observer<Map<String, Object>>() {
            @Override
            public void onChanged(@Nullable Map<String, Object> map) {
                for (String key : map.keySet()) {
                    if (MainViewModel.MAP_TYPE_ON_REFRESH.equals(key)) {
                        Log.d(TAG, "MAP_TYPE_ON_REFRESH:");
                        testDemoDataEvent();
                    }
                }

            }
        });
        testDemoDataEvent();
    }

    private void testDemoDataEvent() {
        Log.d(TAG, "testDemoDataEvent:");
        mViewModel.setRefreshing(true);
        JOperateInterface.getInstance(getContext()).testDemo(2,new JOperateInterface.CallBack() {
            @Override
            public void onCallBack(int code, final String msg) {
                Log.d(TAG, "onCallBack: " + msg);
                if (0 == code) {
                    mViewModel.setEvent(msg);
                    testDemoDataTag();
                } else {
                    showToast(msg);
                    mViewModel.setRefreshing(false);
                }
            }
        });
    }

    private void testDemoDataTag() {
        Log.d(TAG, "testDemoDataTag:");
        mViewModel.setRefreshing(true);
        JOperateInterface.getInstance(getContext()).testDemo(3, new JOperateInterface.CallBack() {
            @Override
            public void onCallBack(int code, final String msg) {
                Log.d(TAG, "onCallBack: " + msg);
                if (0 == code) {
                    mViewModel.setTag(msg);
                    testDemoDataChannel();
                } else {
                    showToast(msg);
                    mViewModel.setRefreshing(false);
                }
            }
        });
    }

    private void testDemoDataChannel() {
        Log.d(TAG, "testDemoDataChannel:");
        mViewModel.setRefreshing(true);
        JOperateInterface.getInstance(getContext()).testDemo(4, new JOperateInterface.CallBack() {
            @Override
            public void onCallBack(int code, final String msg) {
                Log.d(TAG, "onCallBack: " + msg);
                if (0 == code) {
                    mViewModel.setChannels(msg);
                    testDemoDataUserAttribute();
                } else {
                    showToast(msg);
                    mViewModel.setRefreshing(false);
                }
            }
        });
    }

    private void showToast(final String msg) {
        Activity activity = getActivity();
        if (null == activity) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ToastCustom.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void testDemoDataUserAttribute() {
        Log.d(TAG, "testDemoDataUserAttribute:");
        mViewModel.setRefreshing(true);
        JOperateInterface.getInstance(getContext()).testDemo(5, new JOperateInterface.CallBack() {
            @Override
            public void onCallBack(int code, final String msg) {
                Log.d(TAG, "onCallBack: " + msg);
                if (0 == code) {
                    mViewModel.setUser(msg);

                    Activity activity = getActivity();
                    if (null == activity) {
                        mViewModel.setRefreshing(false);
                        return;
                    }
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Data data = new Data();
                            data.setEvents(mViewModel.eventList);
                            data.setTags(mViewModel.tagList);
//                            test();
                            data.setChannels(mViewModel.channelList);
                            data.setUser(mViewModel.userList);
                            adapter.setData(data);
                            adapter.notifyDataSetChanged();
                        }


                    });
                } else {
                    showToast(msg);
                }
                mViewModel.setRefreshing(false);
            }
        });
    }


    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.back:
                Log.d(TAG, "onClick back");
                getFragmentManager().popBackStack();
                break;
        }
    }

    private void test() {
        mViewModel.channelList = new ArrayList<>();
        Data.Channels channels = new Data.Channels();
        channels.channelid = "13";
        channels.channelname = "13";
        mViewModel.channelList.add(channels);

        channels = new Data.Channels();
        channels.channelid = "74";
        channels.channelname = "74";
        mViewModel.channelList.add(channels);

        channels = new Data.Channels();
        channels.channelid = "75";
        channels.channelname = "75";
        mViewModel.channelList.add(channels);
    }
}