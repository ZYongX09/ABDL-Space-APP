package org.joinmastodon.android.fragments;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.widget.EditText;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.AppLanguageManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Uses the existing real-editor/offline-transport fixture; never submits a post or invokes AI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en",
		shadows=ComposeReplyPublishingBehaviorTest.LocalRequests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ComposeAppLanguageDraftTest{
	private ComposeReplyPublishingBehaviorTest fixture;

	@Before public void setup() throws Exception{
		fixture=new ComposeReplyPublishingBehaviorTest(); fixture.setUp();
		RuntimeEnvironment.getApplication().getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().clear().commit();
		AppLanguageManager.install(RuntimeEnvironment.getApplication());
	}
	@After public void cleanup() throws Exception{
		try{ if(fixture!=null) fixture.tearDown(); }
		finally{ RuntimeEnvironment.getApplication().getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().clear().commit(); }
	}

	@Test public void realComposerSnapshotsTypedBodyAndCwBeforeUiLanguageChanges() throws Exception{
		ComposeFragment composer=fixture.launchClassificationEditor();
		EditText body=composer.getView().findViewById(R.id.toot_text);
		body.setText("A draft with user content 中文 left unchanged"); body.setSelection(7);
		composer.getView().findViewById(R.id.btn_spoiler).performClick();
		EditText cw=composer.getView().findViewById(R.id.content_warning); cw.setText("User-authored CW");
		assertTrue(composer.prepareForAppLanguageChange());
		Bundle state=new Bundle(); composer.onSaveInstanceState(state);
		assertEquals("A draft with user content 中文 left unchanged", state.getString("appLanguageDraftText"));
		assertEquals("User-authored CW", state.getString("appLanguageDraftSpoiler"));
		assertEquals(7, state.getInt("appLanguageDraftSelection"));
		assertTrue(state.containsKey("postLang")); // Posting language is retained, not replaced with UI locale.
		assertEquals(0, ComposeReplyPublishingBehaviorTest.LocalRequests.sent.stream()
				.filter(r->r instanceof org.joinmastodon.android.api.requests.statuses.CreateStatus).count());
	}

	@Test public void busyRealComposerVetoesManagerBeforeLocaleWrite() throws Exception{
		ComposeFragment composer=fixture.launchClassificationEditor();
		EditText body=composer.getView().findViewById(R.id.toot_text); body.setText("Do not lose this draft");
		java.lang.reflect.Field sending=ComposeFragment.class.getDeclaredField("localPublishInFlight"); sending.setAccessible(true);
		sending.setBoolean(composer, true); // Busy-state fixture only; no actual posting request is made.
		try{
			assertFalse(composer.prepareForAppLanguageChange());
			assertFalse(AppLanguageManager.setLanguage(fixture.classificationActivity(), "zh-CN"));
			assertEquals("", AppLanguageManager.getSelectedLanguage(fixture.classificationActivity()));
			assertEquals("Do not lose this draft", body.getText().toString());
			assertFalse(fixture.classificationActivity().isFinishing());
		}finally{ sending.setBoolean(composer, false); }
	}
}
