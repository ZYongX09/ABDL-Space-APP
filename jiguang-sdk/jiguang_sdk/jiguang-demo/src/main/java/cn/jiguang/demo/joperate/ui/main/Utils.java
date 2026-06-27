package cn.jiguang.demo.joperate.ui.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import java.util.Locale;

public class Utils {
    private static final String TAG = "Utils";

    public static boolean isMobileNO(String mobileNums) {
        /**
         * 判断字符串是否符合手机号码格式
         * 移动号段: 134,135,136,137,138,139,147,150,151,152,157,158,159,170,178,182,183,184,187,188
         * 联通号段: 130,131,132,145,155,156,170,171,175,176,185,186
         * 电信号段: 133,149,153,170,173,177,180,181,189
         * @param str
         * @return 待检测的字符串
         */
        String telRegex = "^((13[0-9])|(14[5,7,9])|(15[^4])|(18[0-9])|(17[0,1,3,5,6,7,8]))\\d{8}$";// "[1]"代表下一位为数字可以是几，"[0-9]"代表可以为0-9中的一个，"[5,7,9]"表示可以是5,7,9中的任意一位,[^4]表示除4以外的任何一个,\\d{9}"代表后面是可以是0～9的数字，有9位。
        if (TextUtils.isEmpty(mobileNums))
            return false;
        else
            return mobileNums.matches(telRegex);
    }

    public static void copy(Context context, String text) {
        //获取剪贴版
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
//创建ClipData对象
//第一个参数只是一个标记，随便传入。
//第二个参数是要复制到剪贴版的内容
        ClipData clip = ClipData.newPlainText("text", text);
//传入clipdata对象.
        clipboard.setPrimaryClip(clip);

    }


    public static final String KEY_APP_CHANNEL = "JPUSH_CHANNEL";
    public static final String KEY_APP_KEY = "JPUSH_APPKEY";

    private static String APP_KEY = "";
    private static String APP_CHANNEL = null;

    public static String getAppKey(Context context) {
        if (TextUtils.isEmpty(APP_KEY)) {
            try {
                if (context != null) {
                    ApplicationInfo ai = context.getPackageManager()
                            .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
                    if (ai != null && ai.metaData != null) {
                        APP_KEY = getBundleString(ai.metaData, KEY_APP_KEY);
                        if (!TextUtils.isEmpty(APP_KEY)) {
                            APP_KEY = APP_KEY.toLowerCase(Locale.getDefault());
                        }
                    }
                } else {
                    Log.d(TAG, "[getAppKey] context is null");
                }
            } catch (Throwable e) {

            }
        }
        return APP_KEY;
    }
    public static String getBundleString(Bundle bundle, String key) {
        if (bundle == null) {
            return null;
        }
        Object obj = bundle.get(key);
        if (obj == null) {
            return null;
        } else {
            return obj.toString();
        }
    }


    private static String APP_VERSION_NAME;
    private static String APP_APP_NAME;

    public static String getAPPName(Context context) {
        if (APP_APP_NAME == null) {
            initPackageInfo(context);
        }
        return APP_APP_NAME == null ? "" : APP_APP_NAME;
    }

    private static void initPackageInfo(Context context) {
        PackageInfo pinfo;
        try {
            pinfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String name = pinfo.versionName;
            int labelRes = pinfo.applicationInfo.labelRes;
            APP_APP_NAME = context.getResources().getString(labelRes);
            if (name.length() > 30) {
                name = name.substring(0, 30);
            }
            APP_VERSION_NAME = name;
        } catch (Throwable e) {
            Log.d(TAG, "NO versionName defined in manifest.");
        }
    }



}
