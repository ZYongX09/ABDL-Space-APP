package org.joinmastodon.android.fragments.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.R;

import java.util.List;

import me.grishka.appkit.fragments.ToolbarFragment;

public class OpenSourceLicensesFragment extends ToolbarFragment {

	static class OpenSourceLibrary {
		String name, version, license, url;
		OpenSourceLibrary(String name, String version, String license, String url) {
			this.name = name;
			this.version = version;
			this.license = license;
			this.url = url;
		}
	}

	private static final List<OpenSourceLibrary> LIBRARIES = List.of(
		// 源码/图形改编：不是 Gradle 二进制依赖；版本只在来源有记录时填写。
		new OpenSourceLibrary("Moshidon", null, "GPL-3.0", "https://github.com/LucasGGamerM/moshidon"),
		new OpenSourceLibrary("2FAS Security (adapted source)", "5.6.0", "GPL-3.0-only", "https://github.com/twofas/2fas-android/tree/119ead28ed8d3d2215afd8f55428c1586401149b"),
		new OpenSourceLibrary("FlowReader (reader-core)", "c56512d1b55aaf332fbd7ab10c0d94f260a75992", "GPL-3.0", "https://github.com/HuZaiGong/flowreader/tree/c56512d1b55aaf332fbd7ab10c0d94f260a75992"),
		new OpenSourceLibrary("miuix liquid effects / AndroidLiquidGlass (adapted effects, not a binary dependency)", null, "Apache-2.0", "https://github.com/Kyant0/AndroidLiquidGlass"),
		new OpenSourceLibrary("itshover (adapted animated icons)", null, "Apache-2.0", "https://github.com/itshover/itshover"),
		new OpenSourceLibrary("Diff Match and Patch (bundled source)", null, "Apache-2.0", "https://github.com/google/diff-match-patch"),
		// 二进制库：按 releaseRuntimeClasspath 的实际解析版本，而非仅按 Gradle 请求版本。
		new OpenSourceLibrary("miuix", "0.9.3", "Apache-2.0", "https://github.com/compose-miuix-ui/miuix"),
		new OpenSourceLibrary("AppKit (grishka)", "1.4.8", "Unlicense", "https://github.com/grishka/appkit"),
		new OpenSourceLibrary("LiteX RecyclerView", "1.2.1.1", "Apache-2.0", "https://github.com/grishka/LiteX"),
		new OpenSourceLibrary("LiteX SwipeRefreshLayout", "1.2.0-beta01", "Apache-2.0", "https://github.com/grishka/LiteX"),
		new OpenSourceLibrary("LiteX Browser", "1.4.0", "Apache-2.0", "https://github.com/grishka/LiteX"),
		new OpenSourceLibrary("LiteX Concurrent", "1.1.0", "Apache-2.0", "https://github.com/grishka/LiteX"),
		new OpenSourceLibrary("LiteX DynamicAnimation", "1.1.0-alpha03", "Apache-2.0", "https://github.com/grishka/LiteX"),
		new OpenSourceLibrary("LiteX ViewPager / ViewPager2 / Palette", "1.0.0", "Apache-2.0", "https://github.com/grishka/LiteX"),
		// 网络与数据
		new OpenSourceLibrary("OkHttp", "3.14.9", "Apache-2.0", "https://github.com/square/okhttp"),
		new OpenSourceLibrary("Okio", "1.17.2", "Apache-2.0", "https://github.com/square/okio"),
		new OpenSourceLibrary("Gson", "2.8.9", "Apache-2.0", "https://github.com/google/gson"),
		new OpenSourceLibrary("Jsoup", "1.18.3", "MIT", "https://github.com/jhy/jsoup"),
		// 事件总线
		new OpenSourceLibrary("Otto", "1.3.8", "Apache-2.0", "https://github.com/square/otto"),
		new OpenSourceLibrary("async-otto", "1.0.3", "Apache-2.0", "https://github.com/PSDev/async-otto"),
		// 二维码
		new OpenSourceLibrary("ZXing Core", "3.5.3", "Apache-2.0", "https://github.com/zxing/zxing"),
		new OpenSourceLibrary("zxing-android-embedded", "4.3.0", "Apache-2.0", "https://github.com/journeyapps/zxing-android-embedded"),
		// AI 图像识别
		new OpenSourceLibrary("TensorFlow Lite", "2.16.1", "Apache-2.0", "https://github.com/tensorflow/tensorflow"),
		// 序列化
		new OpenSourceLibrary("Parceler", "1.1.12", "Apache-2.0", "https://github.com/johncarl81/parceler"),
		new OpenSourceLibrary("SafeParcel", "1.5.0", "Apache-2.0", "https://github.com/microg/SafeParcel"),
		// Kotlin / Compose 生态
		new OpenSourceLibrary("Kotlin", "2.4.10", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
		new OpenSourceLibrary("kotlinx-coroutines", "1.9.0", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
		new OpenSourceLibrary("kotlinx-serialization", "1.7.3", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
		new OpenSourceLibrary("JetBrains Annotations", "23.0.0", "Apache-2.0", "https://github.com/JetBrains/java-annotations"),
		new OpenSourceLibrary("Jetpack Compose UI / Foundation / Runtime (AndroidX)", "1.11.2", "Apache-2.0", "https://github.com/androidx/androidx"),
		new OpenSourceLibrary("Compose Material3 / Window Size Class (AndroidX)", "1.4.0", "Apache-2.0", "https://github.com/androidx/androidx"),
		new OpenSourceLibrary("Compose Material Icons (AndroidX)", "1.7.6", "Apache-2.0", "https://github.com/androidx/androidx"),
		new OpenSourceLibrary("Compose Material Ripple (AndroidX)", "1.8.1", "Apache-2.0", "https://github.com/androidx/androidx"),
		new OpenSourceLibrary("Compose Multiplatform UI / Foundation / Runtime (JetBrains)", "1.11.1", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform"),
		new OpenSourceLibrary("Compose Multiplatform Material3 Window Size Class (JetBrains)", "1.9.0", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform"),
		new OpenSourceLibrary("JetBrains AndroidX Lifecycle", "2.9.6", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform-core"),
		new OpenSourceLibrary("JetBrains AndroidX SavedState", "1.3.6", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform-core"),
		new OpenSourceLibrary("MaterialKolor Material Color Utilities (Kotlin port)", "4.1.1", "MIT", "https://github.com/jordond/MaterialKolor"),
		new OpenSourceLibrary("Poko Annotations", "0.21.1", "Apache-2.0", "https://github.com/drewdamilton/poko"),
		new OpenSourceLibrary("JSpecify", "1.0.0", "Apache-2.0", "https://github.com/jspecify/jspecify"),
		// AndroidX
		new OpenSourceLibrary("AndroidX Activity", "1.9.3", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/activity"),
		new OpenSourceLibrary("AndroidX Fragment", "1.8.5", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/fragment"),
		new OpenSourceLibrary("AndroidX Core", "1.16.0", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/core"),
		new OpenSourceLibrary("AndroidX Lifecycle", "2.9.4", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
		new OpenSourceLibrary("AndroidX Room", "2.8.3", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/room"),
		new OpenSourceLibrary("AndroidX SQLite", "2.6.1", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/sqlite"),
		new OpenSourceLibrary("AndroidX WorkManager", "2.10.0", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/work"),
		new OpenSourceLibrary("AndroidX Biometric", "1.1.0", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/biometric"),
		new OpenSourceLibrary("AndroidX NavigationEvent / NavigationEvent Compose", "1.0.2", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/navigationevent"),
		new OpenSourceLibrary("AndroidX SavedState", "1.4.0", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/savedstate"),
		new OpenSourceLibrary("AndroidX ExifInterface", "1.3.7", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/exifinterface"),
		new OpenSourceLibrary("AndroidX 基础组件", null, "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
		// 工具
		new OpenSourceLibrary("Desugar JDK Libraries", "2.0.3", "GPL-2.0 with Classpath Exception", "https://github.com/google/desugar_jdk_libs")
	);

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setTitle(R.string.open_source_licenses);
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		ViewGroup content = (ViewGroup) inflater.inflate(R.layout.fragment_open_source_licenses, container, false);

		TextView footer = content.findViewById(R.id.footer_note);
		footer.setText(R.string.third_party_licenses_footer);

		ViewGroup listContainer = content.findViewById(R.id.list_container);

		for (int i = 0; i < LIBRARIES.size(); i++) {
			OpenSourceLibrary lib = LIBRARIES.get(i);
			View itemView = inflater.inflate(R.layout.item_open_source_license, listContainer, false);

			TextView nameText = itemView.findViewById(R.id.lib_name);
			TextView summaryText = itemView.findViewById(R.id.lib_summary);

			nameText.setText(lib.name);
			summaryText.setText(lib.version == null ? lib.license : lib.version + ", " + lib.license);

			itemView.setOnClickListener(v -> {
				Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(lib.url));
				try {
					startActivity(intent);
				} catch (ActivityNotFoundException e) {
					Toast.makeText(v.getContext(), R.string.no_app_to_handle_action, Toast.LENGTH_SHORT).show();
				}
			});

			listContainer.addView(itemView);
		}

		return content;
	}
}
