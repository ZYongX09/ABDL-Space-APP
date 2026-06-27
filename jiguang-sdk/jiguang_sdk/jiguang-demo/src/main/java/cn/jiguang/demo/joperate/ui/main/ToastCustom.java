package cn.jiguang.demo.joperate.ui.main;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import cn.jiguang.demo.R;


public class ToastCustom {
    public static Toast makeText(Context context, String text, int lengthLong) {
        //Inflater意思是充气
//LayoutInflater这个类用来实例化XML文件到其相应的视图对象的布局
        LayoutInflater inflater = LayoutInflater.from(context);

//通过制定XML文件及布局ID来填充一个视图对象
        View layout = inflater.inflate(R.layout.joperate_toast_custom, null);
        TextView textView = (TextView) layout.findViewById(R.id.text);
        textView.setText(text);
// 自定义一个Toast
        Toast toast = new Toast(context);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.setDuration(lengthLong);
        toast.setView(layout);
        return toast;
    }
}
