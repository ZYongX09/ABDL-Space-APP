package cn.jiguang.demo.joperate.ui.main;

import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import cn.jiguang.demo.joperate.data.Data;


public class MainViewModel extends ViewModel {
    private static final String TAG = "MainViewModel";

    // 创建LiveData
//    public MutableLiveData<Data> mData = new MutableLiveData<>();

    public static final String MAP_TYPE_CUID_VALUE = "cuid_value";
    public static final String MAP_TYPE_PROJECT_VALUE = "project_value";
    public static final String MAP_TYPE_ADVANCED_VALUE = "advanced_value";
    public static final String MAP_TYPE_MAIN_VIEW_REFRESHING = "main_view_refreshing";
    public static final String MAP_TYPE_DATA = "data";
    public static final String MAP_TYPE_MAIN_VIEW = "main_view";
    public static final String MAP_TYPE_ON_REFRESH = "on_refresh";
    public static final String MAP_TYPE_TO_ADVANCED = "to_advanced";
    public static final String MAP_TYPE_TO_PROJECT = "to_project";
    public static final String MAP_TYPE_TO_PRESET = "to_preset";
    public MutableLiveData<Map<String, Object>> mMap = new MutableLiveData<>();


    @Override
    protected void onCleared() {
        super.onCleared();
    }

    public MainViewModel() {
        super();
//        MediatorLiveData d;
//        d.addSource();
    }

    public void setMainView() {
        HashMap<String, Object> value = new HashMap<>();
        value.put(MAP_TYPE_MAIN_VIEW, true);
        mMap.postValue(value);
    }


    public void onRefresh() {
        HashMap<String, Object> value = new HashMap<>();
        value.put(MAP_TYPE_ON_REFRESH, true);
        mMap.postValue(value);
    }

    public void setRefreshing(boolean b) {
        HashMap<String, Object> value = new HashMap<>();
        value.put(MAP_TYPE_MAIN_VIEW_REFRESHING, b);
        mMap.postValue(value);
    }

    public void setMap(String type, Object o) {
        HashMap<String, Object> value = new HashMap<>();
        value.put(type, o);
        mMap.setValue(value);
    }


    public Data.Project project;

    public void setProject(String data) {
        try {
            JSONObject jsonObject = new JSONObject(data);
            String string = jsonObject.getString("data");
            Data.Project fromJson = new Gson().fromJson(string, Data.Project.class);
            project = fromJson;
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public List<Data.Event> eventList;

    public void setEvent(String data) {
        try {
            JSONObject jsonObject = new JSONObject(data);
            String string = jsonObject.getString("data");
            List<Data.Event> eventList = new Gson().fromJson(string, new TypeToken<List<Data.Event>>() {
            }.getType());
            this.eventList = eventList;
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public List<Data.Tag> tagList;

    public void setTag(String data) {
        try {
            tagList = new ArrayList<>();
            JSONObject jsonObject = new JSONObject(data);
            JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("list");
            Log.d(TAG, "setTag:" + jsonArray);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject object = jsonArray.getJSONObject(i);
                Data.Tag tag = new Data.Tag();
                tag.displayName = object.optString("displayName");
                tag.identityName = object.optString("identityName");
                JSONArray arr = object.optJSONArray("identityValues");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < arr.length(); j++) {
                    sb.append(arr.get(j));
                    if (j != arr.length() - 1) {
                        sb.append(',');
                    }
                }
                tag.identityValues = sb.toString();
                tagList.add(tag);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public List<Data.Channels> channelList;

    public void setChannels(String data) {
        try {
            JSONObject jsonObject = new JSONObject(data);
            JSONArray channels = jsonObject.getJSONObject("data").getJSONArray("channelinfo").getJSONObject(0).getJSONArray("detail");
            Log.d(TAG, "setChannels:" + channels.toString());
            List<Data.Channels> eventList = new LinkedList<>();
            for (int i = 0; i < channels.length(); i++) {
                JSONObject channelJson = channels.getJSONObject(i);
                JSONObject contacts = channelJson.getJSONObject("contactValues");
                Log.d(TAG, "contacts:" + contacts.toString());

                Iterator iterator = contacts.keys();
                while(iterator.hasNext()){
                    String key = (String) iterator.next();
                    String value = contacts.optString(key);
                    Data.Channels channel = new Data.Channels();
                    channel.channelvalue = value;
                    channel.channelkey = key;
                    channel.channelid = channelJson.getString("channelid");
                    channel.type = channelJson.getString("type");
                    channel.channelname = channelJson.getString("channelname") + " - " + key;
                    eventList.add(channel);
                }
            }

            List<Data.Channels> android = new ArrayList<>();
            for (int i = 0; i < eventList.size(); i++) {
                Data.Channels channel = eventList.get(i);
                if (Data.Channels.PLATFORM_IOS != channel.platform){
                    android.add(channel);
                }
            }
            this.channelList = android;
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public List<Data.User> userList;

    public void setUser(String data) {
        try {
            JSONObject jsonObject = new JSONObject(data);
//            String string = jsonObject.getJSONObject("data").getJSONArray("list").getJSONObject(0).getString("list");
            JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("list");
            Log.d(TAG, "setUser:" + jsonArray);

            List<Data.User> eventList = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject object = jsonArray.getJSONObject(i);
                Data.User user = new Data.User();
                user.displayName = object.optString("displayName");
                user.key= object.optString("key");
                user.type= object.optInt("type");//属性规则 1：覆盖，2：累加，3：仅一次, 4: 追加
                user.datatype = object.optString("datatype");// 用户属性值类型: string/number/bool/datetime/list
                user.value = object.optString("value");
                eventList.add(user);
            }

            this.userList = eventList;
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}