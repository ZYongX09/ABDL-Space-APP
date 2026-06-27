package cn.jiguang.demo.baselibrary;

import android.app.Application;

import com.alibaba.android.arouter.BuildConfig;
import com.alibaba.android.arouter.launcher.ARouter;
import com.hjq.toast.ToastUtils;
import com.tencent.mmkv.MMKV;

import cn.jiguang.api.utils.JCollectionAuth;
import cn.jiguang.demo.baselibrary.arouter.ServiceConstant;

/**
 * Copyright(c) 2020 极光
 * Description
 */
public class BaseApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            ARouter.openLog();
            ARouter.openDebug();
        }
        ARouter.init(this);

        if (SPUtils.isShowPrivacy(this)){
            initRouter();
        } else {
            JCollectionAuth.setAuth(this, false);
        }
        initToast();
        initKV();
    }

    private void initKV() {
        MMKV.initialize(this);
    }

    private void initToast() {
        ToastUtils.init(this);
    }

    public static void initRouter() {
        ARouter.getInstance().build(ServiceConstant.SERVICE_SHARE).navigation();
        ARouter.getInstance().build(ServiceConstant.SERVICE_LINK).navigation();
        ARouter.getInstance().build(ServiceConstant.SERVICE_PUSH).navigation();
        ARouter.getInstance().build(ServiceConstant.SERVICE_VERIFY).navigation();
        ARouter.getInstance().build(ServiceConstant.SERVICE_UNION).navigation();
        ARouter.getInstance().build(ServiceConstant.SERVICE_JAD).navigation();
        ARouter.getInstance().build(ServiceConstant.SERVICE_JOPERATE).navigation();
    }
}
