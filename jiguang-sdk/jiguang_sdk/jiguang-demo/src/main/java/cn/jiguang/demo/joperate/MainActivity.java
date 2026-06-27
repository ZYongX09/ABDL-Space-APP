package cn.jiguang.demo.joperate;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.Map;

import cn.jiguang.demo.R;
import cn.jiguang.demo.joperate.ui.main.AdvancedFragment;
import cn.jiguang.demo.joperate.ui.main.MainFragment;
import cn.jiguang.demo.joperate.ui.main.MainFragmentEmpty;
import cn.jiguang.demo.joperate.ui.main.MainViewModel;
import cn.jiguang.demo.joperate.ui.main.PresetFragment;
import cn.jiguang.demo.joperate.ui.main.ProjectFragment;
import cn.jiguang.demo.joperate.ui.main.ToastCustom;
import cn.jiguang.joperate.api.JOperateInterface;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    MainViewModel mViewModel;
    SwipeRefreshLayout swipeRefreshLayout;
    boolean isMainFragment = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.joperate_main_activity);
        initViewModel();
        initView(savedInstanceState);
//        showGPSContacts();
        testDemoData();
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                mViewModel.onRefresh();
            }
        });
    }

    private void testDemoData() {
        Log.d(TAG, "testDemoData:");
        swipeRefreshLayout.setRefreshing(true);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                JOperateInterface.getInstance(getApplicationContext()).testDemo(1, new JOperateInterface.CallBack() {
                    @Override
                    public void onCallBack(final int code, final String msg) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Log.d(TAG, "run: code=" + code);
                                Log.d(TAG, "run: msg=" + msg);
                                if (0 == code) {
                                    mViewModel.setProject(msg);
                                    mViewModel.setMainView();
                                } else {
                                    ToastCustom.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                                }
                                swipeRefreshLayout.setRefreshing(false);
                            }
                        });
                    }
                });
            }
        }, 10);
    }

    private void initView(Bundle savedInstanceState) {
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        if (savedInstanceState == null) {
            FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
            fragmentTransaction
                    .replace(R.id.container, MainFragmentEmpty.newInstance())
                    .commit();
        }
    }

    private void initViewModel() {
        mViewModel = ViewModelProviders.of(this).get(MainViewModel.class);
        Log.d(TAG, "mViewModel:" + mViewModel);
        mViewModel.mMap.observe(this, new Observer<Map<String, Object>>() {
            @Override
            public void onChanged(@Nullable Map<String, Object> map) {
                MainActivity.this.onChanged(map);
            }
        });
    }

    private void onChanged(@Nullable Map<String, Object> map) {
        for (String key : map.keySet()) {
            if (MainViewModel.MAP_TYPE_MAIN_VIEW.equals(key)) {
                if (isMainFragment) {
                    return;
                }
                isMainFragment = true;
                FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
                fragmentTransaction
                        .replace(R.id.container, MainFragment.newInstance())
//                        .addToBackStack(null)
                        .commit();
            } else if (MainViewModel.MAP_TYPE_MAIN_VIEW_REFRESHING.equals(key)) {
                swipeRefreshLayout.setRefreshing((Boolean) map.get(key));
            } else if (MainViewModel.MAP_TYPE_ON_REFRESH.equals(key)) {
                Log.d(TAG, "MAP_TYPE_ON_REFRESH:");
                if (isMainFragment) {
                    return;
                }
                testDemoData();
            } else if (MainViewModel.MAP_TYPE_TO_ADVANCED.equals(key)) {
                FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
                fragmentTransaction
                        .replace(R.id.container, AdvancedFragment.newInstance())
                        .addToBackStack(null)
                        .commit();
            } else if (MainViewModel.MAP_TYPE_TO_PROJECT.equals(key)) {
                FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
                fragmentTransaction
                        .replace(R.id.container, ProjectFragment.newInstance())
                        .addToBackStack(null)
                        .commit();
            } else if (MainViewModel.MAP_TYPE_TO_PRESET.equals(key)) {
                FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
                fragmentTransaction
                        .replace(R.id.container, PresetFragment.newInstance())
                        .addToBackStack(null)
                        .commit();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String contents = result.getContents();
            if (contents == null) {
                ToastCustom.makeText(this, "Cancelled", Toast.LENGTH_LONG).show();
            } else {
                if (contents.startsWith("http")) {
                    Uri uri = Uri.parse(contents);
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        intent.addCategory(Intent.CATEGORY_BROWSABLE);
                        intent.setClassName("com.android.browser", "com.android.browser.BrowserActivity");
                        startActivity(intent);
                    } catch (Throwable e) {
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        intent.addCategory(Intent.CATEGORY_BROWSABLE);
                        startActivity(intent);
                    }

                } else {
//                    Intent intent = new Intent(Intent.ACTION_VIEW);
//                    intent.setData(Uri.parse("abcde://joperate_debug?token=fghij"));
//                    JOperateInterface.getInstance(getApplicationContext()).setReportDebug(data);
                    ToastCustom.makeText(this, "Scanned: " + contents, Toast.LENGTH_LONG).show();
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }



    static final String[] LOCATIONGPS = new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE};
    /**
     * 检测GPS、位置权限是否开启
     */
    public void showGPSContacts() {
        LocationManager lm = (LocationManager) this.getSystemService(this.LOCATION_SERVICE);
        boolean ok = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (ok) {//开了定位服务
            if (Build.VERSION.SDK_INT >= 23) { //判断是否为android6.0系统版本，如果是，需要动态添加权限
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {// 没有权限，申请权限。
                    ActivityCompat.requestPermissions(this, LOCATIONGPS,
                            100);
                }
            }
        } else {
            Toast.makeText(this, "系统检测到未开启GPS定位服务,请开启", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivityForResult(intent, 1315);
        }
    }
}