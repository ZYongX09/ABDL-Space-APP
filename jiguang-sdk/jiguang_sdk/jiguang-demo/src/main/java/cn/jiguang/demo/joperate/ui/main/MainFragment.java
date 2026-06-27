package cn.jiguang.demo.joperate.ui.main;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;

import com.google.zxing.integration.android.IntentIntegrator;

import java.util.Map;

import cn.jiguang.demo.R;
import cn.jiguang.demo.joperate.MyCaptureActivity;
import cn.jiguang.joperate.api.JOperateInterface;

public class MainFragment extends Fragment implements View.OnClickListener {

    private static final String TAG = "MainFragment";


    private MainViewModel mViewModel;
    TextView cuid_content;
    ImageView image_debug_dot;
    TextView text_debug_switch;
    Button debug_button;

    boolean reportDebug = false;

    public static MainFragment newInstance() {
        return new MainFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.joperate_main_fragment, container, false);
        cuid_content = view.findViewById(R.id.cuid_content);

        image_debug_dot = view.findViewById(R.id.image_debug_dot);
        text_debug_switch = view.findViewById(R.id.text_debug_switch);
        debug_button = view.findViewById(R.id.debug_button);

        View view_debug = view.findViewById(R.id.view_debug);
        view_debug.setOnClickListener(this);
        ImageView cuid_copy = view.findViewById(R.id.cuid_copy);
        cuid_copy.setOnClickListener(this);

        view.findViewById(R.id.project_button).setOnClickListener(this);
        view.findViewById(R.id.event_button).setOnClickListener(this);

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mViewModel = ViewModelProviders.of(getActivity()).get(MainViewModel.class);
        Log.d(TAG, "mViewModel:" + mViewModel);
        mViewModel.mMap.observe(this, new Observer<Map<String, Object>>() {
            @Override
            public void onChanged(@Nullable Map<String, Object> map) {
                onChangedView(map);
            }
        });
    }


    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "onStart:");
        updataData();
    }

    private void updataData() {
        Log.d(TAG, "updataData:");
        mViewModel.setRefreshing(true);
        String cuid = JOperateInterface.getInstance(getContext()).getCuid();
        if (null == cuid) {
            cuid = "";
        }
        Log.d(TAG, "updataData cuid:" + cuid);
        cuid_content.setText(cuid);
//        mViewModel.setMap(MainViewModel.MAP_TYPE_CUID_VALUE, cuid);
        setButtonView();


        mViewModel.setRefreshing(false);
    }

    private void setButtonView() {
        reportDebug = JOperateInterface.getInstance(getContext()).getReportDebug();

        Log.d(TAG, "setButtonView reportDebug:" +reportDebug);
        if (reportDebug) {
            image_debug_dot.setBackgroundResource(R.drawable.joperate_main_dot_b);
            text_debug_switch.setText("已开启");
            text_debug_switch.setTextColor(Color.parseColor("#0084F6"));
            debug_button.setText("高级功能（调试）");
            Drawable drawable = getResources().getDrawable(
                    R.mipmap.joperate_icon_advanced);
            // / 这一步必须要做,否则不会显示.
            drawable.setBounds(0, 0, drawable.getMinimumWidth(),
                    drawable.getMinimumHeight());
            debug_button.setCompoundDrawables(drawable, null, null, null);
        } else {
            image_debug_dot.setBackgroundResource(R.drawable.joperate_main_dot);
            text_debug_switch.setText("未开启");
            text_debug_switch.setTextColor(Color.parseColor("#ff8d939d"));
            debug_button.setText("扫码进入数据校验模式");
            Drawable drawable = getResources().getDrawable(
                    R.mipmap.joperate_scan_code);
            // / 这一步必须要做,否则不会显示.
            drawable.setBounds(0, 0, drawable.getMinimumWidth(),
                    drawable.getMinimumHeight());
            debug_button.setCompoundDrawables(drawable, null, null, null);
        }
    }

    private void onChangedView(Map<String, Object> map) {
        Log.d(TAG, "onChangedView map:" + map);
        for (String key : map.keySet()) {
            if (MainViewModel.MAP_TYPE_CUID_VALUE.equals(key)) {
                cuid_content.setText((String) map.get(key));
            } else if (MainViewModel.MAP_TYPE_ON_REFRESH.equals(key)) {
                Log.d(TAG, "MAP_TYPE_ON_REFRESH:");
                updataData();
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.cuid_copy:
                Utils.copy(getContext(), cuid_content.getText().toString());
                ToastCustom.makeText(getContext(), "CUID已复制", Toast.LENGTH_LONG).show();
                break;
            case R.id.view_debug:
                if (reportDebug) {
                    mViewModel.setMap(MainViewModel.MAP_TYPE_TO_ADVANCED, true);
                } else {
                    new IntentIntegrator(getActivity())
                            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)// 扫码的类型,可选：一维码，二维码，一/二维码
                            .setPrompt("请对准二维码")// 设置提示语
                            .setCameraId(0)// 选择摄像头,可使用前置或者后置
                            .setBeepEnabled(true)// 是否开启声音,扫完码之后会"哔"的一声
//                            .setBarcodeImageEnabled(true)// 扫完码之后生成二维码的图片
                            .setCaptureActivity(MyCaptureActivity.class)
                            .initiateScan();// 初始化扫码
//                    reportDebug=true;
                }
                break;
            case R.id.project_button:
                mViewModel.setMap(MainViewModel.MAP_TYPE_TO_PROJECT, true);
                break;
            case R.id.event_button:
                mViewModel.setMap(MainViewModel.MAP_TYPE_TO_PRESET, true);
                break;
        }
    }

}