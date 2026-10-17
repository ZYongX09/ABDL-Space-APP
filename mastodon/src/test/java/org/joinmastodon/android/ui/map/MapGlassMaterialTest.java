package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.view.View;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,32,35}, application=CompatibilityTestApplication.class)
public class MapGlassMaterialTest{
	private Context context;
	@Before public void setup(){
		context=RuntimeEnvironment.getApplication(); context.setTheme(R.style.Theme_Mastodon_Dark);
		V.setApplicationContext(context);
	}

	// Regression for the 2026-10-11 crash: tab wiring calls setSampleSource before any snapshot
	// update has created the liquid surface, so the call must not dereference it.
	@Test public void sampleSourceBeforeFirstSnapshotDoesNotCrash(){
		MapGlassMaterial material=new MapGlassMaterial(context,24,false);
		try{
			material.setCornerRadius(24f);
			material.setCornerRadius(18.5f);
			material.setCornerRadius(-1f);
			material.setSampleSource(new View(context));
			material.setSampleSource(null);
			material.update(null,0,0);
			material.setSampleSource(new View(context));
		}finally{ material.dispose(); }
	}

	@Test public void disposeToleratesRepeatedCallsAndLateSource(){
		MapGlassMaterial material=new MapGlassMaterial(context,24,false);
		material.dispose();
		material.setSampleSource(new View(context));
		material.update(null,0,0);
		material.dispose();
		material.dispose();
	}
}
