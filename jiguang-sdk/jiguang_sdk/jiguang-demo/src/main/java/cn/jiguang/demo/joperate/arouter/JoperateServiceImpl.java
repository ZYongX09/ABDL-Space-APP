package cn.jiguang.demo.joperate.arouter;

import android.content.Context;
import android.util.Log;

import com.alibaba.android.arouter.facade.annotation.Route;

import cn.jiguang.demo.baselibrary.arouter.InitService;
import cn.jiguang.demo.baselibrary.arouter.ServiceConstant;
import cn.jiguang.joperate.api.JOperateInterface;


@Route(path = ServiceConstant.SERVICE_JOPERATE)
public class JoperateServiceImpl implements InitService {

    private static final String TAG = "JoperateServiceImpl";


    @Override
    public void init(final Context context) {
        Log.i(TAG, "JoperateServiceImpl init");
        JOperateInterface.setDebug(true);
        JOperateInterface.getInstance(context).initialize();
        JOperateInterface.getInstance(context).operationStart();
    }

}
