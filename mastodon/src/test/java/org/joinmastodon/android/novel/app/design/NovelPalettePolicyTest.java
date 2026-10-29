package org.joinmastodon.android.novel.app.design;

import static org.junit.Assert.*;
import org.joinmastodon.android.api.session.AccountLocalPreferences.ColorPreference;
import org.junit.Test;

/** Executable resolver matrix, not source-code pattern assertions. */
public class NovelPalettePolicyTest{
	@Test public void sourceUnknownGlobalNordChangesOnlyNovelLight(){
		assertEquals(ColorPreference.BLUE,NovelPalettePolicy.resolve(null,ColorPreference.NORD,false,false));
		assertEquals(ColorPreference.NORD,NovelPalettePolicy.resolve(null,ColorPreference.NORD,false,true));
		assertEquals(ColorPreference.BLUE,NovelPalettePolicy.resolve(null,null,false,false));
	}
	@Test public void explicitGlobalIncludingSameNordIsPreserved(){
		for(ColorPreference color:ColorPreference.values()){
			assertEquals(color,NovelPalettePolicy.resolve(null,color,true,false));
			assertEquals(color,NovelPalettePolicy.resolve(null,color,true,true));
			if(color!=ColorPreference.NORD)assertEquals(color,NovelPalettePolicy.resolve(null,color,false,false));
		}
	}
	@Test public void accountOverrideAlwaysWinsIncludingNordInLightAndDark(){
		for(ColorPreference account:ColorPreference.values())for(ColorPreference global:ColorPreference.values())for(boolean explicit:new boolean[]{false,true})for(boolean dark:new boolean[]{false,true})
			assertEquals(account,NovelPalettePolicy.resolve(account,global,explicit,dark));
	}
}
