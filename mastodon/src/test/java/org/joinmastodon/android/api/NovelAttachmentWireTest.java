package org.joinmastodon.android.api;

import static org.junit.Assert.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.JsonAdapter;
import org.joinmastodon.android.model.NovelAttachment;
import org.junit.Test;
import java.util.List;

/** Pure-JVM real Gson coverage, also runnable directly with javac/JUnit without an Android build. */
public class NovelAttachmentWireTest{
	public static final String NOVEL="""
		{"schema_version":1,"mode":"chapter_update","available":true,"work_id":"work-1","release_id":"release-2",
		 "title":"柔软日常","author":{"id":42,"username":"owner","display_name":"棉云","avatar":"","url":""},
		 "cover_url":"https://offline.invalid/cover","completed":false,"work_type":"original","chapter_count":13,"word_count":32000,
		 "description":"A description","chapters":[{"id":"chapter-12","title":"第12章","word_count":1000},{"id":"chapter-13","title":"第13章","word_count":2460}],
		 "excerpt":"章节摘录","first_chapter_id":"chapter-12"}
		""";
	private static final Gson GSON=new Gson();
	public static final class Envelope{
		public String id,content;
		@JsonAdapter(NovelAttachment.Deserializer.class) public NovelAttachment novel;
		public Envelope reblog;
	}
	private Envelope read(String novel){return GSON.fromJson("{\"id\":\"post-1\",\"content\":\"unchanged\",\"novel\":"+novel+"}",Envelope.class);}
	@Test public void exactWireFieldsAndAllModesDecode(){
		for(String mode:List.of("chapter_update","new_book","link")){
			NovelAttachment n=read(NOVEL.replace("chapter_update",mode)).novel;
			assertNotNull(n);assertTrue(n.isDisplayable());assertEquals(mode,n.mode);assertEquals(1,n.schemaVersion);
			assertEquals("work-1",n.workId);assertEquals("release-2",n.releaseId);assertEquals(42,n.author.id);
			assertEquals("棉云",n.author.displayName);assertEquals(32000,n.wordCount);assertEquals(2,n.chapters.size());assertEquals("chapter-12",n.firstChapterId);
		}
	}
	@Test public void malformedIndependentExtensionNeverDropsPostOrNestedReblog(){
		for(String bad:List.of("null","[]","true","2","\"bad\"","{}",
				NOVEL.replace("\"schema_version\":1","\"schema_version\":2"),
				NOVEL.replace("\"schema_version\":1","\"schema_version\":\"1\""),
				NOVEL.replace("\"schema_version\":1","\"schema_version\":1.5"),
				NOVEL.replace("\"available\":true","\"available\":\"true\""),
				NOVEL.replace("\"chapter_update\"","\"future_mode\""),
				NOVEL.replace("\"title\":\"柔软日常\"","\"title\":{}"),
				NOVEL.replace("\"id\":42","\"id\":\"42\""),
				NOVEL.replace("\"id\":42","\"id\":42.5"),
				NOVEL.replace("\"word_count\":32000","\"word_count\":-1"),
				NOVEL.replace("\"chapter_count\":13","\"chapter_count\":1.2"),
				NOVEL.replace("\"word_count\":32000","\"word_count\":9999999999999999999999999"),
				NOVEL.replace("\"first_chapter_id\":\"chapter-12\"","\"first_chapter_id\":[]"),
				NOVEL.replace("\"title\":\"第12章\"","\"title\":{}"))){
			Envelope post=GSON.fromJson("{\"id\":\"outer\",\"content\":\"keep body\",\"novel\":"+bad+",\"reblog\":{\"id\":\"inner\",\"content\":\"keep nested\",\"novel\":"+bad+"}}",Envelope.class);
			assertEquals("keep body",post.content);assertNull(bad,post.novel);assertEquals("inner",post.reblog.id);assertEquals("keep nested",post.reblog.content);assertNull(post.reblog.novel);
		}
	}
	@Test public void unavailableDropsEvenMalformedStaleMetadata(){
		JsonObject o=JsonParser.parseString(NOVEL).getAsJsonObject();o.addProperty("available",false);o.addProperty("release_id","old-private");
		o.add("title",new JsonObject());o.add("chapters",new JsonObject());
		NovelAttachment n=read(o.toString()).novel;
		assertNotNull(n);assertTrue(n.isSupported());assertFalse(n.isDisplayable());assertFalse(n.available);
		assertNull(n.title);assertNull(n.releaseId);assertNull(n.author);assertNull(n.coverUrl);assertNull(n.description);assertNull(n.excerpt);assertNull(n.chapters);
	}
	@Test public void ordinaryStatusHasNoSyntheticNovel(){assertNull(read("null").novel);assertNull(GSON.fromJson("{\"id\":\"p\"}",Envelope.class).novel);}
}
