package cn.jiguang.demo.joperate.ui.main;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.Map;

import cn.jiguang.demo.R;
import cn.jiguang.demo.joperate.data.adapter.PresetAdapter;
import cn.jiguang.joperate.api.JOperateInterface;

public class PresetFragment extends Fragment implements View.OnClickListener {

    private static final String TAG = "PresetFragment";
    private MainViewModel mViewModel;
    PresetAdapter adapter;
    public static PresetFragment newInstance() {
        return new PresetFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.joperate_preset_fragment, container, false);
        view.findViewById(R.id.back).setOnClickListener(this);


        RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);
        adapter = new PresetAdapter();
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
                        Log.d(TAG, "MAP_TYPE_ON_REFRESH:" );
                        peripheralProperty();
                    }
                }

            }
        });

        peripheralProperty();
    }

    private void peripheralProperty() {
        Log.d(TAG, "peripheralProperty:" );
        mViewModel.setRefreshing(true);
        JSONObject peripheralProperty = JOperateInterface.getInstance(getContext()).getPeripheralProperty();
        adapter.setData(peripheralProperty);
        adapter.notifyDataSetChanged();
        mViewModel.setRefreshing(false);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.back:
                Log.d(TAG,"onClick back");
                getFragmentManager().popBackStack();
                break;
        }
    }
}