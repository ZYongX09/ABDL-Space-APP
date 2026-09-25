package org.joinmastodon.android.sponsors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.joinmastodon.android.model.sponsors.SponsorModels;
import org.junit.Test;

/** Regression coverage for literal placeholder parsing on Android and the desktop JVM. */
public class SponsorModelsValidTemplateTest{
	@Test public void acceptsValidPlaceholders(){
		assertTrue(SponsorModels.validTemplate("普通 {x} 张，赞助者 {y} 张，剩余 {a}，{reset} 重置"));
	}
	@Test public void acceptsPlainTextWithoutPlaceholders(){
		assertTrue(SponsorModels.validTemplate("这是一段没有任何占位符的说明文字。"));
	}
	@Test public void rejectsUnknownPlaceholder(){
		assertFalse(SponsorModels.validTemplate("非法占位 {zz}"));
	}
	@Test public void rejectsStrayBraces(){
		assertFalse(SponsorModels.validTemplate("残留花括号 }"));
		assertFalse(SponsorModels.validTemplate("残留花括号 {"));
	}
	@Test public void rejectsNestedBraces(){
		assertFalse(SponsorModels.validTemplate("嵌套 {{x}}"));
	}
	@Test public void acceptsAdjacentRepeatedAndMultilinePlaceholders(){
		assertTrue(SponsorModels.validTemplate("{x}{y}{a}{reset}\n{x} 😀"));
	}
	@Test public void rejectsEmptyBrokenAndMultilinePlaceholders(){
		assertFalse(SponsorModels.validTemplate("{}"));
		assertFalse(SponsorModels.validTemplate("{reset"));
		assertFalse(SponsorModels.validTemplate("{x\n}"));
		assertFalse(SponsorModels.validTemplate("{x}{unknown}"));
	}
	@Test public void rejectsBlankAndNull(){
		assertFalse(SponsorModels.validTemplate(""));
		assertFalse(SponsorModels.validTemplate("   "));
		assertFalse(SponsorModels.validTemplate(null));
	}
}
