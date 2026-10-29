package org.joinmastodon.noveleditor;

import java.util.ArrayDeque;
import java.util.Objects;

/** UI-independent edit state. Save completion acknowledges exactly the captured revision. */
public final class EditorSession{
	public static final long SAVE_PERIOD_MS=10_000;
	public record Frame(String title,String body,int start,int end,int scroll){}
	public record Checkpoint(String chapterId,long generation,long revision,Frame frame){}
	private final String chapterId;
	private final ArrayDeque<Frame> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
	private Frame frame;
	private long generation,revision,savedRevision,nextTick;
	public EditorSession(String chapterId,long generation,Frame initial,long now){this.chapterId=chapterId;this.generation=generation;frame=initial;nextTick=now+SAVE_PERIOD_MS;}
	public synchronized Frame frame(){return frame;}
	public synchronized long generation(){return generation;}
	public synchronized boolean dirty(){return revision!=savedRevision;}
	public synchronized void edit(Frame value){
		Objects.requireNonNull(value);if(value.body().length()>EditorLimits.CHAPTER_CONTENT||value.title().length()>EditorLimits.CHAPTER_TITLE)throw new IllegalArgumentException("Chapter too long");
		if(frame.title().equals(value.title())&&frame.body().equals(value.body())){if(!frame.equals(value)){frame=value;revision++;}return;}
		undo.addLast(frame);int budget=0;for(Frame f:undo)budget+=f.body().length();while(undo.size()>40||budget>2_000_000){Frame old=undo.removeFirst();budget-=old.body().length();}
		redo.clear();frame=value;revision++;
	}
	public synchronized boolean canUndo(){return !undo.isEmpty();}
	public synchronized boolean canRedo(){return !redo.isEmpty();}
	public synchronized void undo(){if(!undo.isEmpty()){redo.addLast(frame);frame=undo.removeLast();revision++;}}
	public synchronized void redo(){if(!redo.isEmpty()){undo.addLast(frame);frame=redo.removeLast();revision++;}}
	/** Edits never move this deadline: continuous typing cannot postpone a checkpoint. */
	public synchronized boolean tick(long now){if(now<nextTick)return false;nextTick+=((now-nextTick)/SAVE_PERIOD_MS+1)*SAVE_PERIOD_MS;return dirty();}
	public synchronized Checkpoint capture(){return new Checkpoint(chapterId,generation,revision,frame);}
	public synchronized void saved(Checkpoint checkpoint,long newGeneration){if(!checkpoint.chapterId().equals(chapterId)||checkpoint.generation()!=generation)throw new IllegalStateException("Stale save");generation=newGeneration;savedRevision=checkpoint.revision();}
	/** Failure deliberately does nothing: dirty text, cursor and undo history remain reachable. */
	public synchronized void failed(Checkpoint checkpoint){}
}
