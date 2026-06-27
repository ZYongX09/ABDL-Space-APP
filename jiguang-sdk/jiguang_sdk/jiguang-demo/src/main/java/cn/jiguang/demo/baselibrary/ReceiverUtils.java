package cn.jiguang.demo.baselibrary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

public class ReceiverUtils {
    public static void registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter, String broadcastPermission, Handler scheduler) {
        if(Build.VERSION.SDK_INT>=33){
            context.getApplicationContext().registerReceiver(receiver,filter,broadcastPermission,scheduler,2);
        }else {
            context.getApplicationContext().registerReceiver(receiver,filter,broadcastPermission,scheduler);
        }
    }
    public static void registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if(Build.VERSION.SDK_INT>=33){
            context.getApplicationContext().registerReceiver(receiver,filter,2);
        } else {
            context.getApplicationContext().registerReceiver(receiver,filter);
        }
    }

    public static void unregisterReceiver(Context context, BroadcastReceiver receiver){
        context.getApplicationContext().unregisterReceiver(receiver);
    }
}
