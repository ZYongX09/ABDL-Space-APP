package cn.jiguang.demo.joperate.data;

import com.google.gson.Gson;

import java.util.List;

public class Data {
    private static final String TAG = "Data";
    Project project;
    List<Event> events;
    List<Tag> tags;
    List<Channels> channels;
    List<User> user;

//    public static Data getTest() {
//
//        Data data = new Data();
//
//        ArrayList<Event> events = new ArrayList<>();
//        for (int i = 0; i < 7; i++) {
//            Event event = new Event();
//            event.displayName = "你好！" + i;
//            event.name = "" + i;
//            events.add(event);
//        }
//        data.setEvents(events);
//
//        Tag tag = new Tag();
////        tag.phone = "15507596788";
////        tag.loginid = "132oiru4832u8";
//        data.tag = tag;
//
//        List<User> user = new ArrayList<>();
//
//        for (int i = 0; i < 10; i++) {
//            User u = new User();
//            u.displayName = "user" + i;
//            u.key = i + "";
//
//            if (2 != i) {
//                u.value = "value" + i;
//            }
//
//            if (2 == i) {
//                u.displayName = "sys_name7";
//                u.key = "jackson";
//            }
//
//            if (4 == i) {
//                u.displayName = "jackson";
//                u.key = "jackson";
//            }
//
//            u.type = i % 4;
//            user.add(u);
//        }
//        data.setUser(user);
//
//
//        List<Channels> channels = new ArrayList<>();
//
//        for (int i = 0; i < 10; i++) {
//            Channels c = new Channels();
//
//            c.channelname = "name" + i;
//            c.channelid = "" + i;
//            if (3 != i) {
//                c.channelvalue = "v" + i;
//            }
//
//            if (4 == i) {
//                c.channelname = "666666";
//                c.channelid = "666666";
//            }
//
//            c.type = (i + 1) % 7 +"";
//
//            channels.add(c);
//        }
//        data.setChannels(channels);
//
//        String toJson = new Gson().toJson(data);
//        Log.d(TAG, "getTest:" + toJson);
//
////        data = fromJson("{\"channels\":[{\"key\":\"0\",\"name\":\"name0\",\"type\":1,\"value\":\"v0\"},{\"key\":\"1\",\"name\":\"name1\",\"type\":2,\"value\":\"v1\"},{\"key\":\"2\",\"name\":\"name2\",\"type\":3,\"value\":\"v2\"},{\"key\":\"3\",\"name\":\"name3\",\"type\":4},{\"key\":\"4\",\"name\":\"name4\",\"type\":5,\"value\":\"v4\"},{\"key\":\"5\",\"name\":\"name5\",\"type\":6,\"value\":\"v5\"},{\"key\":\"6\",\"name\":\"name6\",\"type\":0,\"value\":\"v6\"},{\"key\":\"7\",\"name\":\"name7\",\"type\":1,\"value\":\"v7\"},{\"key\":\"8\",\"name\":\"name8\",\"type\":2,\"value\":\"v8\"},{\"key\":\"9\",\"name\":\"name9\",\"type\":3,\"value\":\"v9\"}],\"events\":[{\"key\":\"0\",\"name\":\"你好！0\"},{\"key\":\"1\",\"name\":\"你好！1\"},{\"key\":\"2\",\"name\":\"你好！2\"},{\"key\":\"3\",\"name\":\"你好！3\"},{\"key\":\"4\",\"name\":\"你好！4\"},{\"key\":\"5\",\"name\":\"你好！5\"},{\"key\":\"6\",\"name\":\"你好！6\"}],\"tag\":{\"loginId\":\"132oiru4832u8\",\"phone\":\"15507596788\"},\"user\":[{\"key\":\"0\",\"name\":\"user0\",\"type\":0,\"value\":\"value0\"},{\"key\":\"1\",\"name\":\"user1\",\"type\":1,\"value\":\"value1\"},{\"key\":\"2\",\"name\":\"user2\",\"type\":2},{\"key\":\"3\",\"name\":\"user3\",\"type\":3,\"value\":\"value3\"},{\"key\":\"4\",\"name\":\"user4\",\"type\":0,\"value\":\"value4\"},{\"key\":\"5\",\"name\":\"user5\",\"type\":1,\"value\":\"value5\"},{\"key\":\"6\",\"name\":\"user6\",\"type\":2,\"value\":\"value6\"},{\"key\":\"7\",\"name\":\"user7\",\"type\":3,\"value\":\"value7\"},{\"key\":\"8\",\"name\":\"user8\",\"type\":0,\"value\":\"value8\"},{\"key\":\"9\",\"name\":\"user9\",\"type\":1,\"value\":\"value9\"}]}");
//        return data;
//    }


    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public List<Event> getEvents() {
        return events;
    }

    public void setEvents(List<Event> events) {
        this.events = events;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public List<Channels> getChannels() {
        return channels;
    }

    public void setChannels(List<Channels> channels) {
        this.channels = channels;
    }

    public List<User> getUser() {
        return user;
    }

    public void setUser(List<User> user) {
        this.user = user;
    }


    public class Project {
        public String id;//项目名称
        public String name;//项目ID
        public long eventStoreCount;//项目存储事件总量
    }

    public static class Event {
        public String displayName;//事件显示名称
        public String name;//事件key
    }

    public static class Tag {
        public String identityName;//用户标识名
        public String displayName;//展示名
        public String identityValues; //值;
    }

    public static class Channels {
//        通道类型:PUSH/WECHATTMP/WECHATOA/DINGTALK/ALIPAYLIFE/SMS/EMAIL
        public static final String TYPE_APP = "PUSH";//app通道//不可修改
        public static final String TYPE_DD = "DINGTALK";//钉钉
        public static final String TYPE_DX = "SMS";//短信
        public static final String TYPE_WXGZH = "WECHATOA";//微信公众号
        public static final String TYPE_WXXCX = "WECHATTMP";//微信小程序
        public static final String TYPE_YJ = "EMAIL";//邮件
        public static final String TYPE_ZFB = "ALIPAYLIFE";//支付宝

        public static final int PLATFORM_ANDROID = 0;
        public static final int PLATFORM_IOS = 1;

        public String channelid;//key
        public String channelvalue;//value //没有值可为""或null
        public String channelname;//显示名
        public String channelkey;//通道标签
        public String type;//通道类型:PUSH/WECHATTMP/WECHATOA/DINGTALK/ALIPAYLIFE/SMS/EMAIL
        public int platform;//0:android,1:ios
    }

    public static class User {
        public static final int TYPE_set = 1;//覆盖
        public static final int TYPE_add = 2;//累计
        public static final int TYPE_one = 3;//不可修改（首次）
        public static final int TYPE_append = 4;//追加（数组类型）

        public static final String DATA_TYPE_STRING = "string";
        public static final String DATA_TYPE_NUMBER = "number";
        public static final String DATA_TYPE_BOOL = "bool";
        public static final String DATA_TYPE_DATETIME = "datetime";
        public static final String DATA_TYPE_LIST = "list";

        public String displayName;
        public String key;
        public String value;
        public int type;//属性规则 1：覆盖，2：累加，3：仅一次, 4: 追加
        public String datatype;// 用户属性值类型: string/number/bool/datetime/list
    }

    public static Data fromJson(String json) {
        Data data = new Gson().fromJson(json, Data.class);
        return data;
    }
}
