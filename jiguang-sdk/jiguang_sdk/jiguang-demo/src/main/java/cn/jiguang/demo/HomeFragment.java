package cn.jiguang.demo;

import android.content.Intent;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

public class HomeFragment extends Fragment {

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.d_fragment_home, container, false);
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView tvTop2 = view.findViewById(R.id.tv_top_2);
        SpannableString spannableString = new SpannableString("10 年 开发者服务技术沉淀");
        spannableString.setSpan(new AbsoluteSizeSpan(24, true), 0, 2, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        spannableString.setSpan(new AbsoluteSizeSpan(16, true), 3, 4, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
        tvTop2.setText(spannableString);

        TextView tvTop3 = view.findViewById(R.id.tv_top_3);
        SpannableString spannableString2 = new SpannableString("服务于 150 万+ 技术开发人员");
        spannableString2.setSpan(new AbsoluteSizeSpan(24, true), 4, 7, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        spannableString2.setSpan(new AbsoluteSizeSpan(16, true), 8, 10, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        tvTop3.setText(spannableString2);

        // jpush_click_start
        view.findViewById(R.id.layout_push).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getActivity(), cn.jiguang.demo.jpush.PushActivity.class));
            }
        });
        // jpush_click_end
        // joperate_click_start
        view.findViewById(R.id.layout_joperate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getActivity(), cn.jiguang.demo.joperate.MainActivity.class));
            }
        });
        // joperate_click_end
    }

}