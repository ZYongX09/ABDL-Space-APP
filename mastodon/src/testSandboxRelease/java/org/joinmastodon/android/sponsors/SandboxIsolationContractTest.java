package org.joinmastodon.android.sponsors;

import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

/** Source allowlist contracts supplement (not replace) the parent's final merged Manifest/APK gate. */
public class SandboxIsolationContractTest{
	private static final String ANDROID="http://schemas.android.com/apk/res/android", TOOLS="http://schemas.android.com/tools";
	private static String source(String file) throws Exception{ return Files.readString(Path.of(file)); }
	private static Element xml(String path) throws Exception{
		DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		return factory.newDocumentBuilder().parse(Path.of(path).toFile()).getDocumentElement();
	}
	@Test public void variantRetainsReleaseSigningNondebugIdentityAndSkipsMapFileBeforeReading() throws Exception{
		String gradle=source("build.gradle");
		assertTrue(gradle.contains("sandboxRelease {\n\t\t\tinitWith release"));
		assertTrue(gradle.contains("applicationIdSuffix '.sandbox'")); assertTrue(gradle.contains("debuggable false"));
		assertTrue(gradle.contains("signingConfig signingConfigs.release")); assertTrue(gradle.contains("SANDBOX_ORIGIN"));
		assertTrue(gradle.contains("sandboxTaskRequested ? [:] : readMapCredentialsFile(mapCredentialsFile())"));
		assertTrue(gradle.contains("if (sandboxTaskRequested) return ''")); assertTrue(gradle.contains("assertSandboxConfiguration"));
		assertTrue(gradle.contains("tasks.register('assertSandboxConfiguration')"));
	}
	@Test public void wholeApplicationAndQueriesAreReplacedWithOnlyLauncherAndSixOfficialActivities() throws Exception{
		Element manifest=xml("src/sandboxRelease/AndroidManifest.xml");
		Element app=(Element)manifest.getElementsByTagName("application").item(0);
		assertEquals("replace", app.getAttributeNS(TOOLS, "node"));
		assertEquals("org.joinmastodon.android.sponsors.SandboxApplication", app.getAttributeNS(ANDROID, "name"));
		assertEquals("false", app.getAttributeNS(ANDROID, "allowBackup")); assertEquals("false", app.getAttributeNS(ANDROID, "debuggable"));
		assertEquals("false", app.getAttributeNS(ANDROID, "usesCleartextTraffic"));
		for(String tag:List.of("provider", "service", "receiver", "activity-alias", "meta-data")) assertEquals(0, app.getElementsByTagName(tag).getLength());
		var activities=app.getElementsByTagName("activity"); Set<String> names=new HashSet<>();
		for(int i=0;i<activities.getLength();i++) names.add(((Element)activities.item(i)).getAttributeNS(ANDROID, "name"));
		assertEquals(Set.of("org.joinmastodon.android.sponsors.SandboxSponsorActivity", "com.alipay.sdk.app.H5PayActivity", "com.alipay.sdk.app.H5AuthActivity",
				"com.alipay.sdk.app.PayResultActivity", "com.alipay.sdk.app.AlipayResultActivity", "com.alipay.sdk.app.H5OpenAuthActivity", "com.alipay.sdk.app.APayEntranceActivity"), names);
		assertEquals(1, app.getElementsByTagName("intent-filter").getLength());
		Element library=(Element)app.getElementsByTagName("uses-library").item(0);
		assertEquals("org.apache.http.legacy", library.getAttributeNS(ANDROID, "name")); assertEquals("false", library.getAttributeNS(ANDROID, "required"));
		Element queries=(Element)manifest.getElementsByTagName("queries").item(0); assertEquals("replace", queries.getAttributeNS(TOOLS, "node"));
		assertEquals(1, queries.getElementsByTagName("package").getLength());
		assertEquals("com.eg.android.AlipayGphoneRC", ((Element)queries.getElementsByTagName("package").item(0)).getAttributeNS(ANDROID, "name"));
	}
	@Test public void sandboxCodeHasNoProductionAccountsRefreshBrowserLoggingOrSslBypass() throws Exception{
		Path root=Path.of("src/sandboxRelease/java/org/joinmastodon/android/sponsors");
		try(var files=Files.list(root)){
			for(Path path:files.toList()){
				String code=Files.readString(path);
				for(String forbidden:List.of("AccountSessionManager", "SponsorRefresh", "MastodonAPIController", ".exec(", "android.util.Log", "System.out", "printStackTrace", "sslSocketFactory", "hostnameVerifier", "Intent.ACTION_VIEW", "getIntent(", "startActivity("))
					assertFalse(path+" contains "+forbidden, code.contains(forbidden));
			}
		}
		String application=Files.readString(root.resolve("SandboxApplication.java")); assertTrue(application.contains("extends Application"));
		assertTrue(application.contains("EnvUtils.setEnv(EnvUtils.EnvEnum.SANDBOX)")); assertFalse(application.contains("extends MastodonApp"));
		String launcher=Files.readString(root.resolve("SandboxAlipayLauncher.java"));
		assertTrue(launcher.contains("new AlipaySdkAdapter.Engine(")); assertTrue(launcher.contains("com.eg.android.AlipayGphoneRC"));
		assertFalse(launcher.contains("\"com.eg.android.AlipayGphone\""));
		String formal=source("src/main/java/org/joinmastodon/android/sponsors/AlipaySdkAdapter.java");
		assertTrue(formal.contains("WALLET_PACKAGE=\"com.eg.android.AlipayGphone\"")); assertFalse(formal.contains("EnvUtils"));
	}
}
