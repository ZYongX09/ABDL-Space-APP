package org.joinmastodon.android.chat.ui;

import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.test.core.app.ApplicationProvider;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26, 28, 30, 35}, application=CompatibilityTestApplication.class,
        qualifiers="w393dp-h852dp-mdpi", shadows=ChatFragmentLayoutTest.NoActiveAccount.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ChatFragmentLayoutTest {
    @Implements(AccountSessionManager.class)
    public static class NoActiveAccount {
        @Implementation public String getLastActiveAccountID() { return null; }
    }

    @Before
    public void setUp() {
        MastodonApp.context=ApplicationProvider.getApplicationContext();
        V.setApplicationContext(MastodonApp.context);
    }

    @Test
    public void keyboardMovesInputAndSendAboveItsTopWithoutAddingNavigationTwice() {
        try (Page page=new Page()) {
            page.apply(24, 24, 0);
            Rect inputBefore=page.bounds(page.input), sendBefore=page.bounds(page.send);
            int listBefore=page.recycler.getHeight();
            page.apply(24, 280, 280);
            Rect inputAfter=page.bounds(page.input), sendAfter=page.bounds(page.send);
            assertEquals(256, inputBefore.bottom-inputAfter.bottom);
            assertEquals(256, sendBefore.bottom-sendAfter.bottom);
            assertEquals(256, listBefore-page.recycler.getHeight());
            page.assertAbove(280);
            assertEquals(page.baseBottom+280, page.inputBar.getPaddingBottom());
        }
    }

    @Test
    public void repeatedInsetsAndKeyboardHeightChangesDoNotAccumulateAndHideRestoresLayout() {
        try (Page page=new Page()) {
            page.apply(24, 24, 0);
            Rect original=page.bounds(page.input);
            int listHeight=page.recycler.getHeight();
            for (int keyboard:new int[]{280, 280, 320, 240, 24, 24}) {
                page.apply(24, keyboard, keyboard>24 ? keyboard : 0);
                assertEquals(page.baseBottom+keyboard, page.inputBar.getPaddingBottom());
                page.assertAbove(keyboard);
            }
            assertEquals(original, page.bounds(page.input));
            assertEquals(listHeight, page.recycler.getHeight());
            assertEquals(page.baseToolbarHeight+24, page.toolbar.getHeight());
        }
    }

    @Test
    @Config(sdk={30, 35})
    public void typedImeWorksWhenLegacySystemBottomStillReportsOnlyNavigation() {
        try (Page page=new Page()) {
            page.apply(24, 24, 0);
            Rect before=page.bounds(page.input);
            page.apply(24, 24, 300);
            assertEquals(page.baseBottom+300, page.inputBar.getPaddingBottom());
            assertEquals(276, before.bottom-page.bounds(page.input).bottom);
            page.assertAbove(300);
            page.apply(24, 24, 0);
            assertEquals(before, page.bounds(page.input));
        }
    }

    @Test
    public void hiddenKeyboardKeepsNavigationReservationAndToolbarSizeStable() {
        try (Page page=new Page()) {
            for (int navigation:new int[]{0, 24, 48, 48, 0}) {
                page.apply(navigation, 0, 0);
                assertEquals(page.baseBottom+navigation, page.inputBar.getPaddingBottom());
                assertEquals(24, page.toolbar.getPaddingTop());
                assertEquals(page.baseToolbarHeight+24, page.toolbar.getHeight());
                assertEquals(0, page.root.getPaddingBottom());
                page.assertAbove(navigation);
            }
        }
    }

    @Test
    public void multilineInputAndLandscapeRemainInsideVisibleChatViewport() {
        try (Page page=new Page()) {
            page.width=852;
            page.height=500;
            page.input.setLines(3);
            page.input.requestFocus();
            page.apply(24, 200, 200);
            page.assertAbove(200);
            assertTrue(page.bounds(page.input).top>=page.toolbar.getBottom());
            assertTrue(page.recycler.getHeight()>0);
            assertTrue(page.input.hasFocus());
            page.apply(24, 24, 0);
            page.assertAbove(24);
            assertEquals(page.baseBottom+24, page.inputBar.getPaddingBottom());
        }
    }

    public static class TestActivity extends FragmentStackActivity {}

    private static class Page implements AutoCloseable {
        final ActivityController<TestActivity> controller;
        final TestActivity activity;
        final ChatFragment fragment;
        final ViewGroup host;
        final ViewGroup root;
        final View toolbar, inputBar, recycler;
        final EditText input;
        final ImageButton send;
        final int baseBottom, baseToolbarHeight;
        int width=393, height=852;

        Page() {
            controller=Robolectric.buildActivity(TestActivity.class);
            activity=controller.get();
            activity.setTheme(R.style.Theme_Mastodon_Light);
            controller.setup();
            fragment=new ChatFragment();
            activity.showFragment(fragment);
            root=(ViewGroup)fragment.getView();
            assertNotNull(root);
            host=activity.findViewById(me.grishka.appkit.R.id.fragment_wrap);
            assertNotNull(host);
            toolbar=root.findViewById(R.id.toolbar);
            inputBar=root.findViewById(R.id.input_bar);
            recycler=root.findViewById(R.id.recycler);
            input=root.findViewById(R.id.input);
            send=root.findViewById(R.id.send_btn);
            baseBottom=inputBar.getPaddingBottom();
            baseToolbarHeight=toolbar.getLayoutParams().height;
            layout();
        }

        void apply(int navigation, int systemBottom, int keyboard) {
            WindowInsets insets;
            if (Build.VERSION.SDK_INT>=30) {
                insets=new WindowInsets.Builder()
                        .setSystemWindowInsets(android.graphics.Insets.of(0, 24, 0, systemBottom))
                        .setStableInsets(android.graphics.Insets.of(0, 24, 0, navigation))
                        .setInsets(WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, keyboard))
                        .setVisible(WindowInsets.Type.ime(), keyboard>0)
                        .build();
            } else {
                try {
                    insets=WindowInsets.class.getDeclaredConstructor(Rect.class)
                            .newInstance(new Rect(0, 24, 0, systemBottom));
                    var stable=WindowInsets.class.getDeclaredField("mStableInsets");
                    stable.setAccessible(true);
                    stable.set(insets, new Rect(0, 24, 0, navigation));
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("Cannot construct real legacy insets", error);
                }
            }
            assertEquals(systemBottom, insets.getSystemWindowInsetBottom());
            assertEquals(navigation, insets.getStableInsetBottom());
            // AppKit's real container forwards the original insets to the real chat callback.
            host.dispatchApplyWindowInsets(insets);
            layout();
        }

        Rect bounds(View view) {
            Rect bounds=new Rect(0, 0, view.getWidth(), view.getHeight());
            root.offsetDescendantRectToMyCoords(view, bounds);
            return bounds;
        }

        void assertAbove(int inset) {
            assertEquals(height, root.getHeight());
            assertTrue(bounds(input).bottom<=height-inset);
            assertTrue(bounds(send).bottom<=height-inset);
            assertTrue(bounds(input).height()>0);
            assertTrue(bounds(send).height()>0);
            assertEquals(height-inset-baseBottom, bounds(input).bottom);
        }

        void layout() {
            host.forceLayout();
            root.forceLayout();
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            host.layout(0, 0, width, height);
        }

        @Override public void close() {
            controller.close();
        }
    }
}
