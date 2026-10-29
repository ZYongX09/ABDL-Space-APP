package org.joinmastodon.noveleditor;

import org.junit.Test;
import static org.junit.Assert.*;

public class EditorSessionTest{
	private static EditorSession.Frame frame(String title,String body,int start,int end,int scroll){return new EditorSession.Frame(title,body,start,end,scroll);}

	@Test public void continuousTypingNeverPostponesFixedTenSecondDeadline(){
		EditorSession session=new EditorSession("chapter",1,frame("标题","",0,0,0),1000);
		for(long now=1250;now<11000;now+=250){session.edit(frame("标题","正文"+now,2,2,12));assertFalse(session.tick(now));}
		assertTrue(session.tick(11000));
		var checkpoint=session.capture();session.saved(checkpoint,2);assertFalse(session.dirty());
		assertFalse(session.tick(11001));
		session.edit(frame("标题","仍在输入",4,4,30));assertFalse(session.tick(20999));assertTrue(session.tick(21000));
		assertTrue(session.tick(51000));assertFalse(session.tick(51001));
	}

	@Test public void saveAcknowledgesOnlyCapturedRevisionAndFailurePreservesUndoCursorAndScroll(){
		var first=frame("原题","原文",1,2,20);var second=frame("改题","输入中正文",2,4,48);
		EditorSession session=new EditorSession("chapter",7,first,0);session.edit(second);
		var captured=session.capture();var newest=frame("最新题","输入中正文继续",5,7,64);session.edit(newest);
		session.saved(captured,8);assertTrue(session.dirty());assertEquals(newest,session.frame());
		var failed=session.capture();session.failed(failed);assertTrue(session.dirty());assertTrue(session.canUndo());
		session.undo();assertEquals(second,session.frame());session.redo();assertEquals(newest,session.frame());
		session.saved(session.capture(),9);assertFalse(session.dirty());assertTrue(session.canUndo());
	}

	@Test public void cursorAndScrollAreDirtyButDoNotPolluteTextUndoHistory(){
		EditorSession session=new EditorSession("chapter",1,frame("题","abcdef",0,0,0),0);
		session.edit(frame("题","abcdef",2,4,100));assertTrue(session.dirty());assertFalse(session.canUndo());
		session.edit(frame("新题","abcdef",2,4,100));assertTrue(session.canUndo());session.undo();
		assertEquals(frame("题","abcdef",2,4,100),session.frame());
	}
}
