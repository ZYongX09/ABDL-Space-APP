package org.joinmastodon.android.sponsors;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

/** Lightweight source/resource contracts; this never resolves dependencies or invokes the SDK. */
public class AlipaySdkSourceContractTest{
	private static final String ANDROID="http://schemas.android.com/apk/res/android";
	private static final String TOOLS="http://schemas.android.com/tools";

	private static String source(String file) throws Exception{ return Files.readString(Path.of(file)); }
	private static Element xml(String file) throws Exception{
		DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		return factory.newDocumentBuilder().parse(Path.of(file).toFile()).getDocumentElement();
	}

	@Test public void officialSdkIsPinnedAndExistingReleaseIdentityIsUnchanged() throws Exception{
		String gradle=source("build.gradle");
		assertTrue(gradle.contains("implementation 'com.alipay.sdk:alipaysdk-android:15.8.42'"));
		assertFalse(gradle.contains("alipaysdk-android:+"));
		assertTrue(gradle.contains("minSdk 26"));
		assertTrue(gradle.contains("versionCode 36")); assertTrue(gradle.contains("versionName \"3.3.0\""));
		assertTrue(gradle.contains("signingConfig signingConfigs.release"));
	}

	@Test public void walletVisibilityRemovesOnlySdkRegionalSandboxAndTaobaoEntries() throws Exception{
		Element manifest=xml("src/main/AndroidManifest.xml");
		Element queries=(Element)manifest.getElementsByTagName("queries").item(0);
		NodeList packages=queries.getElementsByTagName("package");
		List<String> visible=new ArrayList<>();
		for(int i=0;i<packages.getLength();i++){
			Element item=(Element)packages.item(i);
			String name=item.getAttributeNS(ANDROID, "name");
			if(!"remove".equals(item.getAttributeNS(TOOLS, "node"))) visible.add(name);
			else{
				assertFalse(item.hasAttributeNS(TOOLS, "selector"));
				assertTrue(List.of("com.eg.android.AlipayGphoneRC", "hk.alipay.wallet", "hk.alipay.walletRC").contains(name));
			}
		}
		assertEquals(List.of("com.eg.android.AlipayGphone"), visible);
		String text=source("src/main/AndroidManifest.xml");
		assertFalse(text.contains("QUERY_ALL_PACKAGES"));
		assertTrue(text.contains("android:scheme=\"http\"")); assertTrue(text.contains("android:scheme=\"https\""));
		assertFalse(text.contains("com.alipay.sdk.app.PayResultActivity"));
		assertFalse(text.contains("com.alipay.sdk.app.AlipayResultActivity"));
	}

	@Test public void nativeAdapterDoesNotPublishTestOverridesOrLogOrdersOrDeliverRawResults() throws Exception{
		String adapter=source("src/main/java/org/joinmastodon/android/sponsors/AlipaySdkAdapter.java");
		assertTrue(adapter.contains("private static final Engine NATIVE="));
		assertTrue(adapter.contains("interface Provider{")); assertFalse(adapter.contains("public interface Provider"));
		assertTrue(adapter.contains("static final class Engine{")); assertFalse(adapter.contains("public static final class Engine"));
		assertTrue(adapter.contains("new PayTask(activity).payV2(orderStr, true)"));
		assertTrue(adapter.contains("MAIN.post(task)"));
		assertTrue(adapter.contains("result.get(\"resultStatus\")"));
		for(String forbidden:List.of("result.get(\"result\")", "result.get(\"memo\")", "android.util.Log", "System.out", "printStackTrace", "BuildConfig.DEBUG", "setProvider", "setTest", "startActivity", "Intent.ACTION_VIEW")){
			assertFalse(forbidden, adapter.contains(forbidden));
		}
	}

	@Test public void brandVectorsRetainSourcePathCountsColorsAndSquareAspectWithoutTint() throws Exception{
		for(String name:new String[]{"alipay", "afdian"}){
			Element vector=xml("src/main/res/drawable/ic_sponsor_"+name+".xml");
			assertEquals("vector", vector.getTagName());
			assertEquals("24dp", vector.getAttributeNS(ANDROID, "width"));
			assertEquals("24dp", vector.getAttributeNS(ANDROID, "height"));
			assertEquals("1024", vector.getAttributeNS(ANDROID, "viewportWidth"));
			assertEquals("1024", vector.getAttributeNS(ANDROID, "viewportHeight"));
			assertFalse(vector.hasAttributeNS(ANDROID, "tint"));
			NodeList paths=vector.getElementsByTagName("path");
			assertEquals(name.equals("alipay") ? 2 : 3, paths.getLength());
			for(int i=0;i<paths.getLength();i++){
				Element path=(Element)paths.item(i);
				assertEquals(name.equals("alipay") ? (i==0 ? "#5A9EF7" : "#FFFFFF") : "#946CE6", path.getAttributeNS(ANDROID, "fillColor"));
				assertFalse(path.getAttributeNS(ANDROID, "pathData").isEmpty());
			}
			NodeList groups=vector.getElementsByTagName("group");
			assertEquals(name.equals("alipay") ? 1 : 0, groups.getLength());
			if(groups.getLength()>0){
				Element crop=(Element)groups.item(0);
				assertEquals("-150.5", crop.getAttributeNS(ANDROID, "translateX"));
				assertFalse(crop.hasAttributeNS(ANDROID, "scaleX")); assertFalse(crop.hasAttributeNS(ANDROID, "scaleY"));
			}
		}
	}
}
