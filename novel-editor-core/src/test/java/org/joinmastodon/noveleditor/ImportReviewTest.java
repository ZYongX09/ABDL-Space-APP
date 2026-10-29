package org.joinmastodon.noveleditor;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ImportReviewTest{
	private static ImportModels.Chapter chapter(int index,String body){return new ImportModels.Chapter(index,"章"+index,body,"paragraph:"+index);}
	@Test public void dragInsertsAcrossManyRowsInsteadOfSwappingEndpoints(){
		var review=new ImportReview(List.of(chapter(0,"a"),chapter(1,"b"),chapter(2,"c"),chapter(3,"d")));
		review.move(0,3);assertEquals(List.of("b","c","d","a"),review.chapters().stream().map(ImportModels.Chapter::content).toList());
		review.move(3,1);assertEquals(List.of("b","a","c","d"),review.chapters().stream().map(ImportModels.Chapter::content).toList());
		review.reset();assertEquals("a",review.chapters().get(0).content());
	}
	@Test public void mergeRejectsNonAdjacentAndSplitPreservesEveryCharacter(){
		String original="第一段。\n\n第二段 🦊。\n\n第三段。\n";
		var review=new ImportReview(List.of(chapter(0,original),chapter(1,"b"),chapter(2,"c")));
		assertThrows(IllegalArgumentException.class,()->review.merge(List.of(0,2)));
		assertEquals(3,review.chapters().size());
		int offset=ImportReview.paragraphBoundaries(original).get(1);
		review.split(0,offset,"前","后");assertEquals(original,review.chapters().get(0).content()+review.chapters().get(1).content());
		review.rename(1,"新题");assertEquals("新题",review.chapters().get(1).title());
		assertThrows(IllegalArgumentException.class,()->review.split(0,1,"前","后"));
		review.merge(List.of(1,2));assertEquals(3,review.chapters().size());
	}
	@Test public void longParagraphBoundariesIgnoreBlankLeadingAndTrailingText(){
		String body="\n \n正文\n\n下一段\n \n";
		for(int offset:ImportReview.paragraphBoundaries(body)){assertFalse(body.substring(0,offset).isBlank());assertFalse(body.substring(offset).isBlank());}
		assertEquals(1,ImportReview.paragraphBoundaries(body).size());
		assertEquals(9999,ImportReview.paragraphBoundaries("文字\n".repeat(10000)).size());
	}
}
