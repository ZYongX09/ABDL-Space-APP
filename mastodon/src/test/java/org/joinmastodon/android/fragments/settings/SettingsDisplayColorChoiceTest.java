package org.joinmastodon.android.fragments.settings;

import android.content.Context;
import android.content.SharedPreferences;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.session.AccountLocalPreferences;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Map;

import me.grishka.appkit.utils.V;

import static org.joinmastodon.android.api.session.AccountLocalPreferences.ColorPreference.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
public class SettingsDisplayColorChoiceTest{
	private SharedPreferences global, local;
	private AccountSession session;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private AccountSession previousSession;
	private Instance previousInstance;
	private AccountLocalPreferences preferences;

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception{
		Context context=RuntimeEnvironment.getApplication();
		MastodonApp.context=context;
		V.setApplicationContext(context);
		global=GlobalUserPreferences.getPrefs();
		global.edit().clear().putBoolean("perAccountMigrationDone", true).commit();
		GlobalUserPreferences.load();
		var constructor=AccountSession.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		session=constructor.newInstance();
		session.domain="novel-palette.offline.example.test";
		session.self=new Account();
		session.self.id="42";
		session.self.username=session.self.acct="palette";
		session.infoLastUpdated=System.currentTimeMillis();
		AccountSessionManager manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field("sessions").get(manager);
		instances=(Map<String, Instance>)field("instances").get(manager);
		previousSession=sessions.put(session.getID(), session);
		InstanceV1 instance=new InstanceV1();
		instance.uri=instance.normalizedUri=session.domain;
		instance.version="4.3.0";
		previousInstance=instances.put(session.domain, instance);
		local=session.getRawLocalPreferences();
		local.edit().clear().commit();
		preferences=new AccountLocalPreferences(local, session);
	}

	@After
	public void tearDown(){
		local.edit().clear().commit();
		if(previousSession==null) sessions.remove(session.getID());
		else sessions.put(session.getID(), previousSession);
		if(previousInstance==null) instances.remove(session.domain);
		else instances.put(session.domain, previousInstance);
		global.edit().clear().putBoolean("perAccountMigrationDone", true).commit();
		GlobalUserPreferences.load();
		MastodonApp.context=null;
	}

	@Test
	public void unrelatedSettingsSaveDoesNotTurnDefaultNordIntoAnExplicitChoice(){
		assertEquals(NORD, GlobalUserPreferences.color);
		assertFalse(GlobalUserPreferences.colorExplicitlyChosen);
		GlobalUserPreferences.confirmDeletePost=false;
		GlobalUserPreferences.save();
		GlobalUserPreferences.load();
		assertEquals("NORD", global.getString("color", null));
		assertFalse(global.getBoolean("colorExplicitlyChosen", true));
		assertFalse(GlobalUserPreferences.colorExplicitlyChosen);
	}

	@Test
	public void historicalSavedNordRemainsUnknownWithoutChangingTheGlobalPalette(){
		global.edit().putString("color", "NORD").remove("colorExplicitlyChosen").commit();
		GlobalUserPreferences.load();
		assertFalse(GlobalUserPreferences.colorExplicitlyChosen);
		assertEquals(NORD, preferences.getCurrentColor());
		assertEquals(NORD.ordinal(), SettingsDisplayFragment.colorSelectionIndex(preferences, false));
	}

	@Test
	public void singleAccountChooserSelectsTheInheritedColorNotMaterialDynamic(){
		GlobalUserPreferences.color=BLUE;
		assertNull(preferences.color);
		assertEquals(BLUE.ordinal(), SettingsDisplayFragment.colorSelectionIndex(preferences, false));
		assertNotEquals(MATERIAL3.ordinal(), SettingsDisplayFragment.colorSelectionIndex(preferences, false));
	}

	@Test
	public void confirmingTheSameNordRecordsARealExplicitChoice(){
		SettingsDisplayFragment.applyColorSelection(preferences, false, NORD.ordinal(), false);
		assertEquals(NORD, preferences.color);
		assertTrue(GlobalUserPreferences.colorExplicitlyChosen);
		GlobalUserPreferences.load();
		assertTrue(GlobalUserPreferences.colorExplicitlyChosen);
		assertEquals(NORD, GlobalUserPreferences.color);
		assertEquals(NORD, new AccountLocalPreferences(local, session).color);
	}

	@Test
	public void perAccountChoiceDoesNotChangeTheInheritedGlobalChoice(){
		SettingsDisplayFragment.applyColorSelection(preferences, true, GREEN.ordinal()+1, false);
		assertEquals(GREEN, preferences.color);
		assertEquals(NORD, GlobalUserPreferences.color);
		assertFalse(GlobalUserPreferences.colorExplicitlyChosen);
		assertEquals(GREEN.ordinal()+1, SettingsDisplayFragment.colorSelectionIndex(preferences, true));
	}

	@Test
	public void promotingAnAlreadySelectedAccountColorStillUpdatesTheDefault(){
		preferences.color=BLUE;
		preferences.save();
		SettingsDisplayFragment.applyColorSelection(preferences, true, BLUE.ordinal()+1, true);
		assertNull(preferences.color);
		assertFalse(local.contains("color"));
		assertEquals(BLUE, GlobalUserPreferences.color);
		assertTrue(GlobalUserPreferences.colorExplicitlyChosen);
		assertEquals(0, SettingsDisplayFragment.colorSelectionIndex(preferences, true));
	}

	@Test
	public void selectingInheritanceDoesNotClaimAnExplicitGlobalChoice(){
		preferences.color=RED;
		preferences.save();
		SettingsDisplayFragment.applyColorSelection(preferences, true, 0, false);
		assertNull(preferences.color);
		assertFalse(local.contains("color"));
		assertEquals(NORD, preferences.getCurrentColor());
		assertFalse(GlobalUserPreferences.colorExplicitlyChosen);
	}

	private static Field field(String name) throws Exception{
		Field value=AccountSessionManager.class.getDeclaredField(name);
		value.setAccessible(true);
		return value;
	}
}
