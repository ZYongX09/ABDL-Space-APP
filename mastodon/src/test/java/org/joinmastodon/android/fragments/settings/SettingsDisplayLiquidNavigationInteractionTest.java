package org.joinmastodon.android.fragments.settings;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.E;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.StatusDisplaySettingsChangedEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.viewmodel.CheckableListItem;
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility;
import org.joinmastodon.android.ui.viewholders.SwitchListItemViewHolder;
import org.joinmastodon.android.ui.views.M3Switch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.fragments.BaseRecyclerFragment;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowLooper;

/** Real settings callbacks, attached list holders, switches, dialogs, and lifecycle transitions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={33, 35}, application=CompatibilityTestApplication.class, qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class SettingsDisplayLiquidNavigationInteractionTest{
	private static final String CHOICE="useIosLiquidNavigation";

	private ActivityController<FragmentStackActivity> controller;
	private FragmentStackActivity activity;
	private SettingsDisplayFragment fragment;
	private RecyclerView list;
	private CheckableListItem<?> navigationItem;
	private int navigationPosition=-1;
	private SharedPreferences prefs;
	private AccountSession session;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private AccountSession replacedSession;
	private Instance replacedInstance;
	private final DisplaySettingsEvents events=new DisplaySettingsEvents();

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception{
		Context context=RuntimeEnvironment.getApplication();
		MastodonApp.context=context;
		V.setApplicationContext(context);
		setPerformance(false, 256); // Robolectric's default memory class can trigger the warning.
		prefs=GlobalUserPreferences.getPrefs();
		assertTrue(prefs.edit().clear().putBoolean("perAccountMigrationDone", true).commit());
		GlobalUserPreferences.load();

		// Do not call addAccount(), launch MainActivity, or create authenticated API requests.
		// Both the session and its instance are local fixtures, so local preferences never
		// enter the missing-instance path that could fetch instance information.
		var constructor=AccountSession.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		session=constructor.newInstance();
		session.domain="liquid-navigation.offline.example.test";
		session.self=new Account();
		session.self.id="42";
		session.self.username=session.self.acct="offline";
		session.infoLastUpdated=System.currentTimeMillis();
		AccountSessionManager manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		instances=(Map<String, Instance>)field(AccountSessionManager.class, "instances").get(manager);
		replacedSession=sessions.put(session.getID(), session);
		InstanceV1 instance=new InstanceV1();
		instance.uri=instance.normalizedUri=session.domain;
		instance.version="4.3.0";
		replacedInstance=instances.put(session.domain, instance);
		assertTrue(session.getRawLocalPreferences().edit().clear().commit());
		E.register(events);
	}

	@After
	public void tearDown() throws Exception{
		E.unregister(events);
		try{
			if(controller!=null)
				controller.close();
		}finally{
			if(sessions!=null){
				if(replacedSession==null)
					sessions.remove(session.getID());
				else
					sessions.put(session.getID(), replacedSession);
			}
			if(instances!=null){
				if(replacedInstance==null)
					instances.remove(session.domain);
				else
					instances.put(session.domain, replacedInstance);
			}
			setPerformance(false, 256);
			prefs.edit().clear().putBoolean("perAccountMigrationDone", true).commit();
			GlobalUserPreferences.load();
			GlobalUserPreferences.useIosLiquidNavigation=false;
			MastodonApp.context=null;
		}
	}

	@Test
	@Config(sdk={26, 32})
	public void oldSystemsHideTheItemAndPreserveARestoredDiskChoice() throws Exception{
		launch(true, true, 64);

		assertFalse(LiquidGlassCompatibility.isSystemSupported());
		assertFalse(GlobalUserPreferences.isIosLiquidNavigationSupported());
		assertFalse(GlobalUserPreferences.useIosLiquidNavigation);
		assertFalse(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertNull(navigationItem);
		assertEquals(-1, navigationPosition);
		assertPersistedChoice(true);
		assertNoDialogEver();
		assertEvents();

		// A real pause invokes onHidden() and saves the other display settings.
		controller.pause();
		idleMain();
		assertPersistedChoice(true);
		assertFalse(GlobalUserPreferences.useIosLiquidNavigation);
		assertNull(field(SettingsDisplayFragment.class, "iosLiquidNavigationItem").get(fragment));
		assertNoDialogEver();
	}

	@Test
	public void normalPerformanceWithoutASavedChoiceStartsChecked() throws Exception{
		launch(null, false, 256);

		assertTrue(LiquidGlassCompatibility.isSystemSupported());
		assertFalse(LiquidGlassCompatibility.shouldWarnAboutPerformance());
		assertRequestedChoice(true);
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertFalse(prefs.contains(CHOICE));
		assertSubtitle(R.string.settings_ios_liquid_navigation_summary);
		assertNoDialogEver();
		assertEvents();
	}

	@Test
	public void lowRamWithoutASavedChoiceStartsUncheckedButStillOffersTheItem() throws Exception{
		launch(null, true, 256);

		assertTrue(GlobalUserPreferences.isIosLiquidNavigationSupported());
		assertTrue(LiquidGlassCompatibility.isSupported());
		assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance());
		assertRequestedChoice(false);
		assertFalse(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertFalse(prefs.contains(CHOICE));
		assertSubtitle(R.string.settings_ios_liquid_navigation_summary);
		assertNoDialogEver();
		assertEvents();
	}

	@Test
	public void memoryClassAt128WithoutASavedChoiceStartsUnchecked() throws Exception{
		launch(null, false, 128);

		assertTrue(GlobalUserPreferences.isIosLiquidNavigationSupported());
		assertTrue(LiquidGlassCompatibility.isSupported());
		assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance());
		assertRequestedChoice(false);
		assertFalse(prefs.contains(CHOICE));
		assertNoDialogEver();
		assertEvents();
	}

	@Test
	public void explicitTrueOnLowRamStartsCheckedWithoutAnOpeningWarning() throws Exception{
		launch(true, true, 256);

		assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance());
		assertRequestedChoice(true);
		assertPersistedChoice(true);
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertNoDialogEver();
		assertEvents();
	}

	@Test
	public void explicitTrueOnLowRamAllowsWarningFreeOptOut() throws Exception{
		launch(true, true, 256);

		assertTrue(GlobalUserPreferences.isIosLiquidNavigationSupported());
		assertTrue(LiquidGlassCompatibility.isSupported());
		assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance());
		assertRequestedChoice(true);
		assertPersistedChoice(true);
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertSubtitle(R.string.settings_ios_liquid_navigation_summary);
		assertNoDialogEver();
		assertEvents();

		click(ClickTarget.SWITCH);
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		assertFalse(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertSubtitle(R.string.settings_ios_liquid_navigation_summary);
		assertNoDialogEver();
		assertEvents(false);
	}

	@Test
	public void lowRamRowClickRequiresConfirmationAndCancelDoesNotSave() throws Exception{
		assertPerformanceConfirmationFlow(true, 256, ClickTarget.ROW);
	}

	@Test
	public void lowRamRealSwitchClickRequiresConfirmationAndCancelDoesNotSave() throws Exception{
		assertPerformanceConfirmationFlow(true, 256, ClickTarget.SWITCH);
	}

	@Test
	public void memoryClassAt128RowClickRequiresConfirmation() throws Exception{
		assertPerformanceConfirmationFlow(false, 128, ClickTarget.ROW);
	}

	@Test
	public void memoryClassBelow128RealSwitchClickRequiresConfirmation() throws Exception{
		assertPerformanceConfirmationFlow(false, 64, ClickTarget.SWITCH);
	}

	@Test
	public void cancellingTheDefaultLowRamChoiceDoesNotCreateAPreference() throws Exception{
		launch(null, true, 256);
		Map<String, ?> before=new HashMap<>(prefs.getAll());

		click(ClickTarget.SWITCH);
		AlertDialog dialog=assertPerformanceDialog();
		assertRequestedChoice(false);
		assertEquals(before, prefs.getAll());
		assertEvents();
		dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		idleMain();

		assertFalse(dialog.isShowing());
		assertNoDialog();
		assertRequestedChoice(false);
		assertFalse(prefs.contains(CHOICE));
		assertEquals(before, prefs.getAll());
		assertEvents();
	}

	@Test
	public void repeatedRowAndSwitchRequestsKeepOnlyOneUncommittedWarning() throws Exception{
		launch(false, true, 256);
		Map<String, ?> before=new HashMap<>(prefs.getAll());
		click(ClickTarget.SWITCH);
		AlertDialog dialog=assertPerformanceDialog();

		click(ClickTarget.ROW);
		assertSame(dialog, currentDialog());
		click(ClickTarget.SWITCH);
		assertSame(dialog, assertPerformanceDialog());
		assertRequestedChoice(false);
		assertEquals(before, prefs.getAll());
		assertEvents();

		// System/back-style cancellation also resets the tracked dialog, not just Cancel.
		dialog.cancel();
		idleMain();
		assertNoDialog();
		assertRequestedChoice(false);
		assertEquals(before, prefs.getAll());
		assertEvents();
	}

	@Test
	public void normalPerformanceRowClickSavesAndPublishesWithoutAWarning() throws Exception{
		assertImmediateToggleFlow(256, ClickTarget.ROW);
	}

	@Test
	public void memoryClassAt129RealSwitchClickSavesWithoutAWarning() throws Exception{
		assertImmediateToggleFlow(129, ClickTarget.SWITCH);
	}

	@Test
	public void realSwitchOptInSurvivesReloadWithoutAnObsoletePopup() throws Exception{
		launch(false, false, 256);
		assertRequestedChoice(false);
		assertSubtitle(R.string.settings_ios_liquid_navigation_summary);
		assertFalse(LiquidGlassCompatibility.shouldWarnAboutPerformance());

		click(ClickTarget.SWITCH);
		assertRequestedChoice(true);
		assertPersistedChoice(true);
		assertEvents(true);
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertNoDialogEver();

		GlobalUserPreferences.load();
		assertRequestedChoice(true);
		assertPersistedChoice(true);
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertSubtitle(R.string.settings_ios_liquid_navigation_summary);
		assertNoDialogEver();
		assertEvents(true);

		click(ClickTarget.ROW);
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		assertFalse(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertNoDialogEver();
		assertEvents(true, false);
	}

	@Test
	public void lowRamConfirmationSavesWithoutAnObsoletePopupAndRejectsRepeatedConfirmation() throws Exception{
		launch(false, true, 256);
		click(ClickTarget.ROW);
		AlertDialog warning=assertPerformanceDialog();
		Button confirm=warning.getButton(AlertDialog.BUTTON_POSITIVE);
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		assertEvents();

		confirm.performClick();
		idleMain();
		assertFalse(warning.isShowing());
		assertRequestedChoice(true);
		assertPersistedChoice(true);
		assertEvents(true);
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertSubtitle(R.string.settings_ios_liquid_navigation_summary);
		assertNoDialog();
		assertSame(warning, ShadowAlertDialog.getLatestAlertDialog());

		confirm.performClick(); // A dismissed warning must not commit or open another dialog.
		idleMain();
		assertNoDialog();
		assertSame(warning, ShadowAlertDialog.getLatestAlertDialog());
		assertRequestedChoice(true);
		assertPersistedChoice(true);
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertEvents(true);
	}

	@Test
	public void pausingActivityDismissesWarningAndRejectsOldConfirmationAfterResume() throws Exception{
		launch(false, true, 256);
		click(ClickTarget.SWITCH);
		AlertDialog oldDialog=assertPerformanceDialog();
		Button oldConfirm=oldDialog.getButton(AlertDialog.BUTTON_POSITIVE);

		controller.pause();
		idleMain();
		assertFalse(oldDialog.isShowing());
		assertNoDialog();
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		// AppKit's onPause also invokes onHidden, which publishes the ordinary display save.
		assertEvents(false);
		oldConfirm.performClick();
		idleMain();
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		assertEvents(false);

		controller.resume();
		idleMain();
		assertStaleConfirmationCannotCommitANewWarning(oldDialog, oldConfirm, ClickTarget.ROW);
	}

	@Test
	public void hidingFragmentDismissesWarningAndRejectsOldConfirmationAfterShowing() throws Exception{
		launch(false, false, 128);
		click(ClickTarget.ROW);
		AlertDialog oldDialog=assertPerformanceDialog();
		Button oldConfirm=oldDialog.getButton(AlertDialog.BUTTON_POSITIVE);

		activity.getFragmentManager().beginTransaction().hide(fragment).commit();
		activity.getFragmentManager().executePendingTransactions();
		idleMain();
		assertTrue(fragment.isHidden());
		assertFalse(oldDialog.isShowing());
		assertNoDialog();
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		assertEvents(false);
		oldConfirm.performClick();
		idleMain();
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		assertEvents(false);

		activity.getFragmentManager().beginTransaction().show(fragment).commit();
		activity.getFragmentManager().executePendingTransactions();
		idleMain();
		assertFalse(fragment.isHidden());
		assertStaleConfirmationCannotCommitANewWarning(oldDialog, oldConfirm, ClickTarget.SWITCH);
	}

	private void assertPerformanceConfirmationFlow(boolean lowRam, int memoryClass, ClickTarget target) throws Exception{
		launch(false, lowRam, memoryClass);
		assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance());
		assertRequestedChoice(false);
		assertNoDialogEver();
		Map<String, ?> before=new HashMap<>(prefs.getAll());

		click(target);
		AlertDialog cancelled=assertPerformanceDialog();
		assertRequestedChoice(false); // In particular, the real M3Switch is rolled back by rebind.
		assertEquals(before, prefs.getAll());
		assertEvents();
		cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		idleMain();
		assertFalse(cancelled.isShowing());
		assertNoDialog();
		assertRequestedChoice(false);
		assertEquals(before, prefs.getAll());
		assertEvents();

		click(target);
		AlertDialog accepted=assertPerformanceDialog();
		assertNotSame(cancelled, accepted);
		assertRequestedChoice(false);
		assertEquals(before, prefs.getAll());
		assertEvents();
		accepted.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idleMain();
		assertFalse(accepted.isShowing());
		assertNoDialog();
		assertRequestedChoice(true);
		assertPersistedChoice(true);
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertEvents(true);

		click(target); // Turning off never asks for performance confirmation.
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		assertFalse(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertNoDialog();
		assertSame(accepted, ShadowAlertDialog.getLatestAlertDialog());
		assertEvents(true, false);
	}

	private void assertImmediateToggleFlow(int memoryClass, ClickTarget target) throws Exception{
		launch(false, false, memoryClass);
		assertFalse(LiquidGlassCompatibility.shouldWarnAboutPerformance());
		assertRequestedChoice(false);
		assertEvents();

		click(target);
		assertRequestedChoice(true);
		assertPersistedChoice(true);
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled());
		assertSubtitle(R.string.settings_ios_liquid_navigation_summary);
		assertNoDialogEver();
		assertEvents(true);

		click(target);
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		assertNoDialogEver();
		assertEvents(true, false);
	}

	private void assertStaleConfirmationCannotCommitANewWarning(AlertDialog oldDialog, Button oldConfirm,
			ClickTarget target) throws Exception{
		assertTrue(fragment.isAdded());
		assertFalse(fragment.isHidden());
		click(target);
		AlertDialog current=assertPerformanceDialog();
		assertNotSame(oldDialog, current);

		oldConfirm.performClick();
		idleMain();
		assertSame(current, currentDialog());
		assertTrue(current.isShowing());
		assertRequestedChoice(false);
		assertPersistedChoice(false);
		assertEvents(false);

		current.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idleMain();
		assertFalse(current.isShowing());
		assertNoDialog();
		assertRequestedChoice(true);
		assertPersistedChoice(true);
		assertEvents(false, true);
	}

	private void launch(Boolean savedChoice, boolean lowRam, int memoryClass) throws Exception{
		setPerformance(lowRam, memoryClass);
		SharedPreferences.Editor editor=prefs.edit();
		if(savedChoice==null)
			editor.remove(CHOICE);
		else
			editor.putBoolean(CHOICE, savedChoice);
		assertTrue(editor.commit());
		GlobalUserPreferences.load();

		controller=Robolectric.buildActivity(FragmentStackActivity.class);
		activity=controller.get();
		activity.setTheme(R.style.Theme_Mastodon_Light);
		controller.create();
		fragment=new SettingsDisplayFragment();
		Bundle args=new Bundle();
		args.putString("account", session.getID());
		fragment.setArguments(args);
		activity.showFragment(fragment);
		controller.start().resume().visible();
		idleMain();

		assertTrue(fragment.isAdded());
		assertFalse(fragment.isHidden());
		assertNotNull(fragment.itemsAdapter);
		list=(RecyclerView)field(BaseRecyclerFragment.class, "list").get(fragment);
		assertNotNull(list);
		list.measure(View.MeasureSpec.makeMeasureSpec(V.dp(480), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(V.dp(960), View.MeasureSpec.EXACTLY));
		list.layout(0, 0, V.dp(480), V.dp(960));
		idleMain();
		for(int i=0; i<fragment.itemsAdapter.getItemCount(); i++){
			if(fragment.itemsAdapter.getItem(i).titleRes==R.string.settings_ios_liquid_navigation){
				assertEquals("Only one liquid-navigation item should be offered", -1, navigationPosition);
				navigationPosition=i;
				navigationItem=(CheckableListItem<?>)fragment.itemsAdapter.getItem(i);
			}
		}
		assertSame(navigationItem, field(SettingsDisplayFragment.class, "iosLiquidNavigationItem").get(fragment));
		if(navigationItem!=null){
			assertEquals(CheckableListItem.Style.SWITCH, navigationItem.style);
			assertTrue(navigationItem.isEnabled);
			assertNotNull(navigationItem.checkedChangeListener);
			assertSame(navigationItem, holder().getItem());
			assertTrue(switchView().isEnabled());
		}
	}

	private void click(ClickTarget target){
		if(target==ClickTarget.ROW){
			// UsableRecyclerView dispatches row taps to this production holder callback.
			holder().onClick();
		}else{
			// Do not call checkedChangeListener directly: exercise CompoundButton's real toggle.
			switchView().performClick();
		}
		idleMain();
	}

	private SwitchListItemViewHolder holder(){
		assertNotNull(navigationItem);
		RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(navigationPosition);
		assertTrue("The actual settings RecyclerView must bind the liquid-navigation switch", holder instanceof SwitchListItemViewHolder);
		assertSame(navigationItem, ((SwitchListItemViewHolder)holder).getItem());
		return (SwitchListItemViewHolder)holder;
	}

	private M3Switch switchView(){
		M3Switch sw=findSwitch(holder().itemView);
		assertNotNull("The production holder must contain a real M3Switch", sw);
		return sw;
	}

	private void assertRequestedChoice(boolean enabled){
		assertNotNull(navigationItem);
		assertEquals(enabled, navigationItem.checked);
		assertEquals(enabled, GlobalUserPreferences.useIosLiquidNavigation);
		assertEquals(enabled, switchView().isChecked());
	}

	private void assertPersistedChoice(boolean enabled){
		assertTrue("An explicit choice must be persisted", prefs.contains(CHOICE));
		assertEquals(enabled, prefs.getBoolean(CHOICE, !enabled));
	}

	private void assertSubtitle(int resource){
		assertEquals(resource, navigationItem.subtitleRes);
		TextView subtitle=holder().itemView.findViewById(R.id.subtitle);
		assertEquals(View.VISIBLE, subtitle.getVisibility());
		assertEquals(activity.getString(resource), subtitle.getText().toString());
	}

	private AlertDialog assertPerformanceDialog() throws Exception{
		AlertDialog dialog=assertDialog(R.string.settings_liquid_glass_performance_title,
				R.string.settings_liquid_glass_performance_message);
		assertEquals(activity.getString(R.string.settings_liquid_glass_enable_anyway),
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
		assertEquals(activity.getString(R.string.cancel),
				dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
		return dialog;
	}

	private AlertDialog assertDialog(int titleResource, int messageResource) throws Exception{
		AlertDialog dialog=currentDialog();
		assertNotNull(dialog);
		assertTrue(dialog.isShowing());
		assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog());
		TextView title=dialog.findViewById(R.id.title);
		TextView message=dialog.findViewById(android.R.id.message);
		assertNotNull(title);
		assertNotNull(message);
		assertEquals(activity.getString(titleResource), title.getText().toString());
		assertEquals(activity.getString(messageResource), message.getText().toString());
		return dialog;
	}

	private AlertDialog currentDialog() throws Exception{
		return (AlertDialog)field(SettingsDisplayFragment.class, "liquidNavigationDialog").get(fragment);
	}

	private void assertNoDialog() throws Exception{
		assertNull(currentDialog());
		AlertDialog latest=ShadowAlertDialog.getLatestAlertDialog();
		assertTrue(latest==null || !latest.isShowing());
	}

	private void assertNoDialogEver() throws Exception{
		assertNoDialog();
		assertNull(ShadowAlertDialog.getLatestAlertDialog());
	}

	private void assertEvents(boolean... choices){
		assertEquals("Only committed display changes should be published", choices.length, events.received.size());
		for(int i=0; i<choices.length; i++){
			assertEquals(session.getID(), events.received.get(i).accountID);
			assertEquals("The in-memory choice must be committed before E.post", choices[i], events.requestedAtDelivery.get(i).booleanValue());
			assertEquals("The disk choice must be committed before E.post", choices[i], events.persistedAtDelivery.get(i).booleanValue());
		}
	}

	private static void setPerformance(boolean lowRam, int memoryClass){
		ActivityManager manager=MastodonApp.context.getSystemService(ActivityManager.class);
		assertNotNull(manager);
		ShadowActivityManager shadow=Shadow.extract(manager);
		shadow.setIsLowRamDevice(lowRam);
		shadow.setMemoryClass(memoryClass);
	}

	private static void idleMain(){
		ShadowLooper.idleMainLooper();
	}

	private static Field field(Class<?> owner, String name) throws Exception{
		Field field=owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static M3Switch findSwitch(View view){
		if(view instanceof M3Switch)
			return (M3Switch)view;
		if(view instanceof ViewGroup){
			ViewGroup group=(ViewGroup)view;
			for(int i=0; i<group.getChildCount(); i++){
				M3Switch sw=findSwitch(group.getChildAt(i));
				if(sw!=null)
					return sw;
			}
		}
		return null;
	}

	private enum ClickTarget{
		ROW, SWITCH
	}

	public static final class DisplaySettingsEvents{
		final List<StatusDisplaySettingsChangedEvent> received=new ArrayList<>();
		final List<Boolean> requestedAtDelivery=new ArrayList<>();
		final List<Boolean> persistedAtDelivery=new ArrayList<>();

		@Subscribe
		public void onDisplaySettingsChanged(StatusDisplaySettingsChangedEvent event){
			received.add(event);
			requestedAtDelivery.add(GlobalUserPreferences.useIosLiquidNavigation);
			persistedAtDelivery.add(GlobalUserPreferences.getPrefs().getBoolean(CHOICE, false));
		}
	}
}
