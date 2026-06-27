package cn.jiguang.demo.joperate.data.adapter;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.jiguang.demo.R;


public class PresetAdapter extends RecyclerView.Adapter<PresetAdapter.ViewHolder> {
    private static final String TAG = "PresetAdapter";
    JSONObject data;
    List<String> dataListKey = new ArrayList<>();
    Map<String, String> dataMap = new HashMap<>();

    {
        put("jg_os_language", "系统语言");
        put("jg_mac_address", "Mac地址");
        put("jg_platform_type", "平台类型");
        put("jg_manufacturer", "设备厂商");
        put("jg_devices_model", "设备型号");
        put("jg_rom_version", "Rom版本号");
        put("jg_os", "操作系统");
        put("jg_os_version", "系统版本号");
        put("jg_screen_size", "屏幕尺寸");
        put("jg_screen_width", "屏幕宽度");
        put("jg_screen_height", "屏幕高度");
        put("jg_wifi", "是否使用wifi");
        put("jg_ssid", "WiFI名");
        put("jg_latitude", "GPS纬度");
        put("jg_longitude", "GPS经度");
        put("jg_network_type", "网络类型");
        put("jg_carrier", "移动运营商");
        put("jg_app_name", "应用名称");
        put("jg_operate_sdk_ver", "sdk版本号");
        put("jg_app_version", "app版本号");
        put("jg_app_key", "appkey");
        put("jg_channel_source", "应用渠道");
        put("jg_device_id", "设备id");
    }

    private void put(String key, String v) {
        dataListKey.add(key);
        dataMap.put(key, v);
    }


    public PresetAdapter() {
    }

    public void setData(JSONObject data) {
        Log.d(TAG,"setData:"+data);
        if (null == data) {
            data = new JSONObject();
        }
        this.data = data;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int type) {
        Log.d(TAG, "onCreateViewHolder type:" + type);

        View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_preset_list, viewGroup, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, int position) {
//编辑类型  不可编辑，无数据，可编辑
        Log.d(TAG, "onBindViewHolder position:" + position);
        Log.d(TAG, "onBindViewHolder viewHolder:" + viewHolder);
        String key = dataListKey.get(position);
        viewHolder.setData(dataMap.get(key), data.optString(key));
    }

    @Override
    public int getItemCount() {
        return dataListKey.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text_name;
        TextView text_value;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            text_name = itemView.findViewById(R.id.text_name);
            text_value = itemView.findViewById(R.id.text_value);
        }

        public void setData(String name, String value) {
            if (null == name) {
                name = "";
            }
            if (null == value) {
                value = "";
            }

            text_name.setText(name);
            text_value.setText(value);
        }
    }


}
