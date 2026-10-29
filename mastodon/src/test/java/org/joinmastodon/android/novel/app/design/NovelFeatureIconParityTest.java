package org.joinmastodon.android.novel.app.design;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NovelFeatureIconParityTest{
	@Test
	public void sourceSvgAndRuntimeVectorUseExactlyTheSameTracedContours() throws Exception{
		Path module=Path.of(System.getProperty("user.dir"));
		var factory=DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		var builder=factory.newDocumentBuilder();
		var svg=builder.parse(module.resolve("../assets/novel-icon.svg").toFile());
		var vector=builder.parse(module.resolve("src/main/res/drawable/ic_novel_rebuilt.xml").toFile());
		String source=svg.getElementsByTagName("path").item(0).getAttributes().getNamedItem("d").getNodeValue();
		String runtime=vector.getElementsByTagName("path").item(0).getAttributes().getNamedItem("android:pathData").getNodeValue();
		assertEquals(source, runtime);
		assertEquals(6, source.chars().filter(c->c=='Z').count());
		assertTrue(source.length()>1000);
	}

	@Test
	public void runtimeIconDrawsBookFacesSeparateSpineTwoLinesAndFourPointStar(){
		Drawable drawable=RuntimeEnvironment.getApplication().getDrawable(R.drawable.ic_novel_rebuilt);
		assertNotNull(drawable);
		Bitmap bitmap=Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
		drawable.setBounds(0, 0, 512, 512);
		drawable.draw(new Canvas(bitmap));
		assertOpaque(bitmap, 110, 145);
		assertOpaque(bitmap, 290, 150);
		assertOpaque(bitmap, 45, 240);
		assertOpaque(bitmap, 460, 240);
		assertClear(bitmap, 256, 230);
		assertClear(bitmap, 165, 186);
		assertClear(bitmap, 165, 238);
		assertClear(bitmap, 350, 225);
		assertOpaque(bitmap, 300, 285);
		assertClear(bitmap, 50, 50);
	}

	private static void assertOpaque(Bitmap bitmap, int x, int y){
		assertTrue("Missing icon stroke at "+x+","+y, Color.alpha(bitmap.getPixel(x,y))>200);
	}

	private static void assertClear(Bitmap bitmap, int x, int y){
		assertTrue("Filled icon cutout at "+x+","+y, Color.alpha(bitmap.getPixel(x,y))<50);
	}
}
