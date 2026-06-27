package cn.jiguang.demo.joperate.data.adapter;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import cn.jiguang.demo.R;
import cn.jiguang.demo.joperate.data.Data;
import cn.jiguang.demo.joperate.ui.main.CustomDialog;
import cn.jiguang.demo.joperate.ui.main.Utils;
import cn.jiguang.joperate.api.JOperateInterface;

public class AdvanceAdapter extends RecyclerView.Adapter<AdvanceAdapter.MyViewHolder> {
    private static final String TAG = "AdvanceAdapter";
    Data data;

    List<Object> dataList = new ArrayList<>();

    final static int TYPE_CUSTOM = 1;
    final static int TYPE_TAG_LIST = 2;
    final static int TYPE_CHANNELS_TITLE = 3;
    final static int TYPE_CHANNELS_EMPTY = 4;
    final static int TYPE_CHANNELS_LIST = 5;
    final static int TYPE_USER_TITLE = 6;
    final static int TYPE_USER_LIST = 7;
    final static int TYPE_TAG_TITLE = 8;

    Handler handler;

    private class MyHandler extends Handler {
        Context context;

        MyHandler(Context context) {
            this.context = context;
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case 1:
                    if (0 == msg.arg1) {
                        AdvanceAdapter.this.notifyDataSetChanged();
                    }
                    Toast.makeText(context, String.valueOf(msg.obj), Toast.LENGTH_LONG).show();
                    break;
            }

        }
    }

    public AdvanceAdapter(Context context) {
        handler = new MyHandler(context.getApplicationContext());
    }


    public void onCallBack(String title, int code, String msg) {
        if (0 == code) {
            msg = String.format("设置「%s」成功", title);
        } else {
            msg = String.format("设置「%s」失败", title) + "：" + msg;
        }

        Message obtain = AdvanceAdapter.this.handler.obtainMessage(1);
        obtain.arg1 = code;
        obtain.obj = msg;
        AdvanceAdapter.this.handler.sendMessage(obtain);
    }

    public void setData(Data data) {
        this.data = data;
        bulidData();
    }

    @NonNull
    @Override
    public MyViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int type) {
        Log.d(TAG, "onCreateViewHolder type:" + type);
        if (TYPE_CUSTOM == type) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_custom_events, viewGroup, false);
            return new CustomViewHolder(view);
        } else if (TYPE_TAG_TITLE == type) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_tag_title, viewGroup, false);
            return new TitleViewHolder(view);
        }  else if (TYPE_TAG_LIST == type) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_tag_list, viewGroup, false);
            return new TagViewHolder(view);
        } else if (TYPE_CHANNELS_TITLE == type) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_channel_title, viewGroup, false);
            return new TitleViewHolder(view);
        } else if (TYPE_CHANNELS_EMPTY == type) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_channels_empty, viewGroup, false);
            return new ChannelsViewHolderEmpty(view);
        } else if (TYPE_USER_TITLE == type) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_user_title, viewGroup, false);
            return new TitleViewHolder(view);
        } else if (TYPE_USER_LIST == type) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_user_list, viewGroup, false);
            return new UserListViewHolder(view);
        } else if (TYPE_CHANNELS_LIST == type) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_channels_list, viewGroup, false);
            return new ChannelsListViewHolder(view);
        }
        View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.joperate_custom_events, viewGroup, false);
        return new CustomViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MyViewHolder viewHolder, int position) {
//编辑类型  不可编辑，无数据，可编辑
        Log.d(TAG, "onBindViewHolder position:" + position);
        Log.d(TAG, "onBindViewHolder viewHolder:" + viewHolder);
        viewHolder.setData(data, dataList.get(position));
    }

    @Override
    public int getItemViewType(int position) {
        Object o = dataList.get(position);
        if (o instanceof Integer) {
            return (int) o;
        } else if (o instanceof Data.Channels) {
            return TYPE_CHANNELS_LIST;
        } else if (o instanceof Data.Tag) {
            return TYPE_TAG_LIST;
        }  else {
            return TYPE_USER_LIST;
        }
    }


    @Override
    public int getItemCount() {
        return dataList.size();
    }

    private void bulidData() {
        dataList.clear();
        dataList.add(TYPE_CUSTOM);
        dataList.add(TYPE_TAG_TITLE);
        if (null == data || null == data.getTags() || data.getTags().size() == 0) {
            Log.d(TAG, "data tags is null");
        } else {
            dataList.addAll(data.getTags());
        }

        dataList.add(TYPE_CHANNELS_TITLE);

        if (null == data || null == data.getChannels() || data.getChannels().size() == 0) {
            dataList.add(TYPE_CHANNELS_EMPTY);
        } else {
            dataList.addAll(data.getChannels());
        }

        dataList.add(TYPE_USER_TITLE);
        if (null == data || null == data.getUser() || data.getUser().size() == 0) {
            Log.d(TAG, "data user is null");
        } else {
            Log.d(TAG, "data user size:" + data.getUser().size());
            dataList.addAll(data.getUser());
        }
    }

    static class ChannelsViewHolderEmpty extends MyViewHolder {

        public ChannelsViewHolderEmpty(@NonNull View itemView) {
            super(itemView);
        }

        @Override
        public void setData(Data data, Object o) {

        }
    }

    class ChannelsListViewHolder extends MyViewHolder {
        private final Map<String, Integer> iconMap = new HashMap<>();

        {
            iconMap.put(Data.Channels.TYPE_APP, R.mipmap.joperate_channels_apptz);
            iconMap.put(Data.Channels.TYPE_DD, R.mipmap.joperate_channels_dd);
            iconMap.put(Data.Channels.TYPE_DX, R.mipmap.joperate_channels_dx);
            iconMap.put(Data.Channels.TYPE_WXGZH, R.mipmap.joperate_channels_wxgzh);
            iconMap.put(Data.Channels.TYPE_WXXCX, R.mipmap.joperate_channels_wxxcx);
            iconMap.put(Data.Channels.TYPE_ZFB, R.mipmap.joperate_channels_zfb);
            iconMap.put(Data.Channels.TYPE_YJ, R.mipmap.joperate_channels_yj);
        }

        ImageView image_icon;
        TextView text_name;
        TextView text_id;
        TextView text_value;
        ImageView image;
        LinearLayout linearLayout_click;

        public ChannelsListViewHolder(@NonNull View itemView) {
            super(itemView);
            image_icon = itemView.findViewById(R.id.image_icon);
            text_name = itemView.findViewById(R.id.text_name);
            text_id = itemView.findViewById(R.id.text_id);
            text_value = itemView.findViewById(R.id.text_value);
            image = itemView.findViewById(R.id.image);
            linearLayout_click = itemView.findViewById(R.id.linearLayout_click);
        }

        @Override
        public void setData(Data data, Object o) {
            if (o instanceof Data.Channels) {
                setData((Data.Channels) o);
            } else {

            }
        }

        private void setData(final Data.Channels channels) {
            setViewChannels(channels);
//            if (!Data.Channels.TYPE_APP.equals(channels.type)) {
                linearLayout_click.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final CustomDialog.Builder builder = new CustomDialog.Builder(v.getContext());
                        String title = String.format("设置「%s」通道值", channels.channelname);
                        builder.setTitle(title);
                        builder.setHint("请输入通道值");
                        builder.setEdit(channels.channelvalue);
                        Integer resid = iconMap.get(channels.type);
                        if (null != resid) {
                            builder.setIocn(resid);
                        }
                        builder.setButtonConfirm(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                final String editString = builder.getEditString();
                                Log.d(TAG, "getEditString:" + editString);
                                if (Data.Channels.TYPE_DX.equals(channels.type)) {
                                    if (!Utils.isMobileNO(builder.getEditString())) {
                                        return;
                                    }
                                }


                                JSONObject data = new JSONObject();
                                try {
                                    JSONObject contact = new JSONObject();
                                    contact.put(channels.channelkey, editString);
                                    data.put(channels.channelid, contact);
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                                JOperateInterface.getInstance(v.getContext()).setChannel(data, new JOperateInterface.CallBack() {
                                    @Override
                                    public void onCallBack(int code, String msg) {

                                        if (0 == code) {
                                            channels.channelvalue = editString;
                                        }
                                        AdvanceAdapter.this.onCallBack(channels.channelname, code, msg);
                                    }
                                });
//                                setViewChannels(channels);
                            }
                        });
                        if (Data.Channels.TYPE_DX.equals(channels.type)) {
                            builder.addTextChangedListener(new TextWatcher() {
                                @Override
                                public void beforeTextChanged(CharSequence s, int start, int count, int after) {

                                }

                                @Override
                                public void onTextChanged(CharSequence s, int start, int before, int count) {

                                }

                                @Override
                                public void afterTextChanged(Editable s) {
                                    if (!Utils.isMobileNO(s.toString())) {
                                        builder.setWarning("手机号码格式错误");
                                    } else {
                                        builder.setWarning(null);
                                    }
                                }
                            });
                        }
                        CustomDialog customDialog = builder.create();
                        customDialog.show();
                    }
                });
//            } else {
//                linearLayout_click.setOnClickListener(null);
//            }
        }

        private void setViewChannels(Data.Channels channels) {
            text_name.setText(channels.channelname);
            text_id.setText(channels.channelid);
            if (TextUtils.isEmpty(channels.channelvalue)) {
                text_value.setText("请填写");
                text_value.setTextColor(Color.parseColor("#8D939D"));
                image.setBackgroundResource(R.mipmap.joperate_icon_jiantou);
            } else {
                text_value.setText(channels.channelvalue);
                text_value.setTextColor(Color.parseColor("#253044"));
                image.setBackgroundResource(R.mipmap.joperate_icon_bianji);
            }


            Integer resid = iconMap.get(channels.type);
            if (null != resid) {
                image_icon.setBackgroundResource(resid);
            }

//            if (Data.Channels.TYPE_APP.equals(channels.type)) {
//                image.setVisibility(View.GONE);
//            } else {
//                image.setVisibility(View.VISIBLE);
//            }
        }
    }

    class UserListViewHolder extends MyViewHolder {
        TextView text_name;
        TextView text_value;
        ImageView image;
        RelativeLayout linearLayout_click;

        public UserListViewHolder(@NonNull View itemView) {
            super(itemView);
            text_name = itemView.findViewById(R.id.text_name);
            text_value = itemView.findViewById(R.id.text_value);
            image = itemView.findViewById(R.id.image);
            linearLayout_click = itemView.findViewById(R.id.linearLayout_click);
        }

        @Override
        public void setData(Data data, Object o) {
            if (o instanceof Data.User) {
                setData((Data.User) o);
            } else {

            }
        }

        private void setData(final Data.User user) {
            linearLayout_click.setOnClickListener(null);
            setViewUser(user);
            if (!(Data.User.TYPE_one == user.type && !TextUtils.isEmpty(user.value))) {
                linearLayout_click.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final CustomDialog.Builder builder = new CustomDialog.Builder(v.getContext());
                        String title = String.format("设置「%s」属性值", user.displayName);
                        builder.setTitle(title);
                        builder.setHint("请输入属性值");
                        builder.setEdit(user.value);
                        builder.setButtonConfirm(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                final String editString = builder.getEditString();
                                Log.d(TAG, "getEditString:" + editString);

                                JOperateInterface.CallBack callBack = new JOperateInterface.CallBack() {
                                    @Override
                                    public void onCallBack(int code, String msg) {
                                        if (0 == code) {
                                            String value = editString;
                                            if (Data.User.DATA_TYPE_LIST.equals(user.datatype)) {
                                                JSONArray jsonArray = new JSONArray();
                                                String[] split = value.split("\n");
                                                for (int i = 0; i < split.length; i++) {
                                                    jsonArray.put(split[i]);
                                                }
                                                value = jsonArray.toString();
                                            }
                                            user.value = value;
                                        }
                                        AdvanceAdapter.this.onCallBack(user.displayName, code, msg);
                                    }
                                };

                                if (Data.User.TYPE_set == user.type || Data.User.TYPE_one == user.type) {

                                    JSONObject properties = new JSONObject();

                                    if (Data.User.DATA_TYPE_LIST.equals(user.datatype)) {
                                        String[] split = editString.split("\n");
                                        if (split.length == 0) {
                                            return;
                                        }
                                        JSONArray jsonArray = new JSONArray();
                                        for (int i = 0; i < split.length; i++) {
                                            jsonArray.put(split[i]);
                                        }
                                        try {
                                            properties.put(user.key, jsonArray);
                                        } catch (JSONException e) {
                                            e.printStackTrace();
                                        }

                                    } else if (Data.User.DATA_TYPE_BOOL.equals(user.datatype)) {
                                        try {
                                            properties.put(user.key, Integer.parseInt(editString));
                                        } catch (Throwable e) {
                                            e.printStackTrace();
                                        }
                                    } else {
                                        try {
                                            properties.put(user.key, editString);
                                        } catch (JSONException e) {
                                            e.printStackTrace();
                                        }
                                    }

                                    JOperateInterface.getInstance(v.getContext()).profileSet(properties, callBack);
                                } else if (Data.User.TYPE_add == user.type) {
                                    try {
                                        Double aDouble = Double.valueOf(editString);
//                                        user.value = editString;
                                        JOperateInterface.getInstance(v.getContext()).profileIncrement(user.key, aDouble, callBack);
                                    } catch (Throwable e) {
                                        e.printStackTrace();
                                    }
                                } else if (Data.User.TYPE_append == user.type) {
                                    String[] split = editString.split("\n");
                                    if (split.length == 0) {
                                        return;
                                    }
                                    HashSet<String> values = new HashSet<>();
                                    for (int i = 0; i < split.length; i++) {
                                        values.add(split[i]);
                                    }
                                    JOperateInterface.getInstance(v.getContext()).profileAppend(user.key, values, callBack);
//                                    JOperateInterface.getInstance(v.getContext()).profileUnset(user.key,null);
                                }
//                                setViewUser(user);
                            }
                        });
                        if (Data.User.DATA_TYPE_LIST.equals(user.datatype)) {
                            builder.setHint("请输入数组属性值, 分行隔离，每行一个值");
                            builder.setWarning("请输入数组属性值, 分行隔离，每行一个值");
                            if (!TextUtils.isEmpty(user.value)) {
                                try {
                                    JSONArray jsonArray = new JSONArray(user.value);
                                    StringBuffer stringBuffer = new StringBuffer();
                                    for (int i = 0; i < jsonArray.length(); i++) {
                                        stringBuffer.append(jsonArray.get(i));
                                        stringBuffer.append("\n");
                                    }
                                    builder.setEdit(stringBuffer.toString());
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                        } else if (Data.User.DATA_TYPE_BOOL.equals(user.datatype)) {
                            builder.setHint("true为1, false为0");
                            builder.setWarning("true为1, false为0");
                        }

                        CustomDialog customDialog = builder.create();
                        customDialog.show();
                    }
                });
            }

        }

        private void setViewUser(Data.User user) {
            text_name.setText(user.displayName);
            if (TextUtils.isEmpty(user.value)) {
                text_value.setText("请填写");
                text_value.setTextColor(Color.parseColor("#8D939D"));
                image.setBackgroundResource(R.mipmap.joperate_icon_jiantou);
            } else {
                text_value.setText(user.value);
                text_value.setTextColor(Color.parseColor("#253044"));
                image.setBackgroundResource(R.mipmap.joperate_icon_bianji);
            }
            if (Data.User.TYPE_one == user.type && !TextUtils.isEmpty(user.value)) {
                image.setVisibility(View.GONE);
            } else {
                image.setVisibility(View.VISIBLE);
            }
        }
    }

    static class TitleViewHolder extends MyViewHolder {

        public TitleViewHolder(@NonNull View itemView) {
            super(itemView);
        }

        @Override
        public void setData(Data data, Object o) {

        }
    }

//    class TagViewHolder extends MyViewHolder {
//        LinearLayout linearLayout_click_loginid;
//        TextView text_value_loginid;
//        ImageView image_loginid;
//
//        LinearLayout linearLayout_click_phone;
//        TextView text_value_phone;
//        ImageView image_phone;
//
//
//        public TagViewHolder(@NonNull View itemView) {
//            super(itemView);
//            linearLayout_click_loginid = itemView.findViewById(R.id.linearLayout_click_loginid);
//            text_value_loginid = itemView.findViewById(R.id.text_value_loginid);
//            image_loginid = itemView.findViewById(R.id.image_loginid);
//
//            linearLayout_click_phone = itemView.findViewById(R.id.linearLayout_click_phone);
//            text_value_phone = itemView.findViewById(R.id.text_value_phone);
//            image_phone = itemView.findViewById(R.id.image_phone);
//
//        }
//
//        @Override
//        public void setData(Data data, Object o) {
//            Data.Tag tag = null;
////            if (null != data) {
////                tag = data.getTag();
////            }
////            setData(tag);
//        }
//
//        private void setData(Data.Tag tag) {
////            setLoginID(tag);
////            setPhone(tag);
//        }
//
//
//        private void setPhone(final Data.Tag tag) {
//            setVeiwPhone(tag);
//            linearLayout_click_phone.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View v) {
//                    final CustomDialog.Builder builder = new CustomDialog.Builder(v.getContext());
//                    builder.setTitle("手机号");
//                    builder.setHint("请输入手机号");
////                    builder.setEdit(tag.phone);
//                    builder.setButtonConfirm(new View.OnClickListener() {
//                        @Override
//                        public void onClick(View v) {
//                            Log.d(TAG, "phone :" + builder.getEditString());
//                            if (!Utils.isMobileNO(builder.getEditString())) {
//                                return;
//                            }
//                            JSONObject property = new JSONObject();
//                            final Context context = v.getContext().getApplicationContext();
//                            JOperateInterface.getInstance(context).login(property, new JOperateInterface.CallBack() {
//                                @Override
//                                public void onCallBack(int code, String msg) {
//                                    Log.d(TAG, "phone onCallBack code: " + code);
//                                    Log.d(TAG, "phone onCallBack msg: " + msg);
//
//                                    if (0 == code) {
////                                        tag.phone = builder.getEditString();
//                                    }
//
//                                    AdvanceAdapter.this.onCallBack("手机号", code, msg);
//
//
//                                }
//                            });
////                            setVeiwPhone(tag);
//                        }
//                    });
//                    builder.addTextChangedListener(new TextWatcher() {
//                        @Override
//                        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
//
//                        }
//
//                        @Override
//                        public void onTextChanged(CharSequence s, int start, int before, int count) {
//
//                        }
//
//                        @Override
//                        public void afterTextChanged(Editable s) {
//                            if (!Utils.isMobileNO(s.toString())) {
//                                builder.setWarning("手机号码格式错误");
//                            } else {
//                                builder.setWarning(null);
//                            }
//                        }
//                    });
//                    CustomDialog customDialog = builder.create();
//                    customDialog.show();
//                }
//            });
//        }
//
//        private void setVeiwPhone(Data.Tag tag) {
////            if (null == tag || TextUtils.isEmpty(tag.phone)) {
////                text_value_phone.setText("请填写");
////                text_value_phone.setTextColor(Color.parseColor("#8D939D"));
////                image_phone.setBackgroundResource(R.mipmap.icon_jiantou);
////            } else {
////                text_value_phone.setText(tag.phone);
////                text_value_phone.setTextColor(Color.parseColor("#253044"));
////                image_phone.setBackgroundResource(R.mipmap.icon_bianji);
////            }
//        }
//
//        private void setLoginID(final Data.Tag tag) {
//            setViewLoginId(tag);
//            linearLayout_click_loginid.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View v) {
//                    final CustomDialog.Builder builder = new CustomDialog.Builder(v.getContext());
//                    builder.setTitle("登录ID");
//                    builder.setHint("请输入登录ID");
////                    builder.setEdit(tag.loginid);
//                    builder.setButtonConfirm(new View.OnClickListener() {
//                        @Override
//                        public void onClick(View v) {
//                            Log.d(TAG, "loginId :" + builder.getEditString());
////                            tag.loginid = builder.getEditString();
//                            JSONObject property = new JSONObject();
//                            JOperateInterface.getInstance(v.getContext()).login(property, new JOperateInterface.CallBack() {
//                                @Override
//                                public void onCallBack(int code, String msg) {
//                                    Log.d(TAG, "loginId onCallBack code: " + code);
//                                    Log.d(TAG, "loginId onCallBack msg: " + msg);
//
//                                    if (0 == code) {
////                                        tag.loginid = builder.getEditString();
//                                    }
//
//                                    AdvanceAdapter.this.onCallBack("登录ID", code, msg);
//                                }
//                            });
////                            setViewLoginId(tag);
//                        }
//                    });
//                    CustomDialog customDialog = builder.create();
//                    customDialog.show();
//                }
//            });
//        }
//
//        private void setViewLoginId(Data.Tag tag) {
////            if (null == tag || TextUtils.isEmpty(tag.loginid)) {
////                text_value_loginid.setText("请填写");
////                text_value_loginid.setTextColor(Color.parseColor("#8D939D"));
////                image_loginid.setBackgroundResource(R.mipmap.icon_jiantou);
////            } else {
////                text_value_loginid.setText(tag.loginid);
////                text_value_loginid.setTextColor(Color.parseColor("#253044"));
////                image_loginid.setBackgroundResource(R.mipmap.icon_bianji);
////            }
//        }
//    }


    class TagViewHolder extends MyViewHolder {
        TextView text_name;
        TextView text_value;
        ImageView image;
        RelativeLayout linearLayout_click;

        public TagViewHolder(@NonNull View itemView) {
            super(itemView);
            text_name = itemView.findViewById(R.id.text_name);
            text_value = itemView.findViewById(R.id.text_value);
            image = itemView.findViewById(R.id.image);
            linearLayout_click = itemView.findViewById(R.id.linearLayout_click);
        }

        @Override
        public void setData(Data data, Object o) {
            if (o instanceof Data.Tag) {
                setData((Data.Tag) o);
            } else {

            }
        }

        private void setData(final Data.Tag tag) {
            linearLayout_click.setOnClickListener(null);
            setViewTag(tag);
            linearLayout_click.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final CustomDialog.Builder builder = new CustomDialog.Builder(v.getContext());
                    String title = tag.displayName;
                    builder.setTitle(title);
                    builder.setHint("请输入标识值，一次只能设置一个标识值");
                    builder.setEdit("");
                    builder.setButtonConfirm(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {

                            Log.d(TAG, "tag :" + builder.getEditString());
                            JSONObject property = new JSONObject();
                            try {
                                property.put(tag.identityName, builder.getEditString());
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                            JOperateInterface.getInstance(v.getContext()).login(property, new JOperateInterface.CallBack() {
                                @Override
                                public void onCallBack(int code, String msg) {
                                    Log.d(TAG, "login onCallBack code: " + code);
                                    Log.d(TAG, "login onCallBack msg: " + msg);
                                    if (0 == code) {
                                        if (tag.identityValues != null && tag.identityValues.length() > 0) {
                                            tag.identityValues = tag.identityValues + "," + builder.getEditString();;
                                        } else {
                                            tag.identityValues = builder.getEditString();
                                        }
                                    }
                                    AdvanceAdapter.this.onCallBack(tag.displayName, code, msg);
                                }
                            });
                        }
                    });

                    CustomDialog customDialog = builder.create();
                    customDialog.show();
                }
            });
        }

        private void setViewTag(Data.Tag tag) {
            text_name.setText(tag.displayName);
            if (TextUtils.isEmpty(tag.identityValues)) {
                text_value.setText("请填写");
                text_value.setTextColor(Color.parseColor("#8D939D"));
                image.setBackgroundResource(R.mipmap.joperate_icon_jiantou);
            } else {
                text_value.setText(tag.identityValues);
                text_value.setTextColor(Color.parseColor("#253044"));
                image.setBackgroundResource(R.mipmap.joperate_icon_bianji);
            }
        }
    }

    static class CustomViewHolder extends MyViewHolder {
        LinearLayout linearLayout_all;
        LinearLayout linearLayout_bottom;

        public CustomViewHolder(View view) {
            super(view);
            linearLayout_all = view.findViewById(R.id.linearLayout_all);
            linearLayout_bottom = view.findViewById(R.id.linearLayout_bottom);
        }

        @Override
        public void setData(Data data, Object o) {
            List<Data.Event> events = null;
            if (null != data) {
                events = data.getEvents();
            }
            setData(events);
        }

        private void setData(List<Data.Event> eventList) {
            Log.d(TAG, "setData  eventList:" + eventList);
            linearLayout_all.removeAllViews();
            linearLayout_all.requestLayout();
            if (null == eventList || eventList.size() == 0) {
                linearLayout_bottom.setVisibility(View.INVISIBLE);
                View view = LayoutInflater.from(linearLayout_all.getContext()).inflate(R.layout.joperate_custom_events_empty, null, false);
                linearLayout_all.addView(view);
            } else {
                linearLayout_bottom.setVisibility(View.VISIBLE);
                for (int i = 0; i < eventList.size() && i < 8; i = i + 2) {
                    View view = LayoutInflater.from(linearLayout_all.getContext()).inflate(R.layout.joperate_custom_events_list, linearLayout_all, false);
                    Data.Event event = eventList.get(i);
                    Button button1 = view.findViewById(R.id.button_1);
                    button1.setVisibility(View.VISIBLE);
                    button1.setText(event.displayName);
                    button1.setOnClickListener(new CustomButtonClick(event));

                    Button button2 = view.findViewById(R.id.button_2);
                    if (eventList.size() - i > 1) {
                        Data.Event event2 = eventList.get(i + 1);
                        button2.setVisibility(View.VISIBLE);
                        button2.setText(event2.displayName);
                        button2.setOnClickListener(new CustomButtonClick(event2));
                    } else {
                        button2.setVisibility(View.INVISIBLE);
                        button2.setOnClickListener(null);
                    }
                    linearLayout_all.addView(view);
                }
            }
            linearLayout_all.requestLayout();
//            linearLayout_all.setLayoutParams(
//                    new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        }

    }

    public static class CustomButtonClick implements View.OnClickListener {
        Data.Event event;

        public CustomButtonClick(Data.Event event) {
            this.event = event;
        }

        @Override
        public void onClick(View v) {
            Log.d(TAG, "onClick name:" + event.displayName + ",key:" + event.name);
            JOperateInterface.getInstance(v.getContext()).onEvent(event.name, new JSONObject());
        }
    }


    static abstract class MyViewHolder extends RecyclerView.ViewHolder {

        public MyViewHolder(@NonNull View itemView) {
            super(itemView);
        }

        public abstract void setData(Data data, Object o);
    }
}
