package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.viewholders.SimpleListItemViewHolder;
import org.joinmastodon.android.model.viewmodel.ListItem;

import java.util.List;

import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.utils.V;

public class OpenSourceLicensesFragment extends ToolbarFragment {

    static class OpenSourceLibrary {
        String name, license, url, description;
        OpenSourceLibrary(String name, String license, String url, String description) {
            this.name = name;
            this.license = license;
            this.url = url;
            this.description = description;
        }
    }

    private static final List<OpenSourceLibrary> LIBRARIES = List.of(
        // 项目本体
        new OpenSourceLibrary("Moshidon", "GPL-3.0", "https://github.com/LucasGGamerM/moshidon", "本应用所基于的 Mastodon Android 客户端（fork 自 grishka/mastodon-android）"),
        // grishka 系列库（Mastodon Android 生态核心，沿用项目 GPL-3.0）
        new OpenSourceLibrary("AppKit (grishka)", "GPL-3.0", "https://github.com/grishka/appkit", "Android 应用基础框架：图片加载、Fragment 栈、自定义 RecyclerView 等"),
        new OpenSourceLibrary("LiteX (grishka)", "GPL-3.0", "https://github.com/grishka/LiteX", "AndroidX 组件裁剪版：recyclerview / swiperefreshlayout / browser / dynamicanimation / viewpager / palette 等"),
        // 网络与数据
        new OpenSourceLibrary("OkHttp 3.14.9", "Apache 2.0", "https://github.com/square/okhttp", "HTTP 客户端"),
        new OpenSourceLibrary("Gson 2.8.9", "Apache 2.0", "https://github.com/google/gson", "JSON 解析库"),
        new OpenSourceLibrary("Jsoup 1.14.3", "MIT", "https://github.com/jhy/jsoup", "HTML 解析器"),
        // 事件总线
        new OpenSourceLibrary("Otto 1.3.8", "Apache 2.0", "https://github.com/square/otto", "事件总线（同步）"),
        new OpenSourceLibrary("async-otto 1.0.3", "Apache 2.0", "https://github.com/PSDev/async-otto", "Otto 的异步事件分发扩展（本应用 E.java 中实际使用 AsyncBus）"),
        // 二维码
        new OpenSourceLibrary("ZXing 3.5.3", "Apache 2.0", "https://github.com/zxing/zxing", "二维码生成与解析"),
        new OpenSourceLibrary("zxing-android-embedded", "Apache 2.0", "https://github.com/journeyapps/zxing-android-embedded", "ZXing Android 嵌入式集成（扫描页）"),
        // AI 图像识别
        new OpenSourceLibrary("TensorFlow Lite 2.7.0", "Apache 2.0", "https://github.com/tensorflow/tensorflow", "NSFW 图片内容识别推理（加载 nsfw.tflite，基于 Yahoo open_nsfw VGG16 模型）"),
        // 序列化
        new OpenSourceLibrary("Parceler 1.1.12", "Apache 2.0", "https://github.com/johncarl81/parceler", "Android 对象序列化（@Parcel / Parcels.wrap）"),
        new OpenSourceLibrary("SafeParcel 1.5.0", "Apache 2.0", "https://github.com/microg/SafeParcel", "Google Play Services 风格的安全序列化框架"),
        // AndroidX
        new OpenSourceLibrary("AndroidX Annotation 1.3.0", "Apache 2.0", "https://github.com/androidx/androidx", "注解支持库"),
        new OpenSourceLibrary("AndroidX Core 1.12.0", "Apache 2.0", "https://github.com/androidx/androidx", "核心兼容库")
    );

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.open_source_licenses);
    }

    @Override
    public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup content = (ViewGroup) inflater.inflate(R.layout.fragment_open_source_licenses, container, false);

        TextView header = content.findViewById(R.id.header_text);
        header.setText("本应用使用了以下开源组件，感谢所有开源贡献者！\n\n说明：该列表按当前工程直接依赖整理，可能不包含全部传递依赖。\n注：本应用另使用极光推送（JPush）等第三方专有 SDK，详见各 SDK 官方协议。");

        ViewGroup listContainer = content.findViewById(R.id.list_container);

        for (int i = 0; i < LIBRARIES.size(); i++) {
            OpenSourceLibrary lib = LIBRARIES.get(i);
            View itemView = inflater.inflate(R.layout.item_open_source_license, listContainer, false);

            TextView nameText = itemView.findViewById(R.id.lib_name);
            TextView licenseText = itemView.findViewById(R.id.lib_license);
            TextView descText = itemView.findViewById(R.id.lib_description);
            TextView urlText = itemView.findViewById(R.id.lib_url);

            nameText.setText(lib.name);
            licenseText.setText(lib.license);
            descText.setText(lib.description);
            urlText.setText(lib.url);

            itemView.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(lib.url));
                startActivity(intent);
            });

            listContainer.addView(itemView);
        }

        return content;
    }
}
