package cn.jiguang.demo.baselibrary;

import android.content.Context;
import android.content.SharedPreferences;

public class SPUtils {
    private static final String NAME = "example_prefs";

    private static SharedPreferences sharedPreferences;

    private static SharedPreferences getSharedPreferences(Context context) {
        if (sharedPreferences == null) {
            sharedPreferences = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
        return sharedPreferences;
    }

    private static final String IS_SHOW_PRIVACY = "IS_SHOW_PRIVACY";
    //state格式： 最近曝光时间戳 + “," + 下发曝光时长
    public static void setIsShowPrivacy(Context context, boolean state){
        getSharedPreferences(context).edit().putBoolean(IS_SHOW_PRIVACY, state).apply();
    }
    public static Boolean isShowPrivacy(Context context){
        return getSharedPreferences(context).getBoolean(IS_SHOW_PRIVACY, false);
    }
}
