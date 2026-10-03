package org.joinmastodon.android.novel.editor;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.noveleditor.EditorLimits;
import org.joinmastodon.noveleditor.EditorModels;
import org.joinmastodon.noveleditor.EditorText;
import org.joinmastodon.noveleditor.NovelEditorStorage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

/** Immersive plain-text editor with journal and checkpoint barriers. */
public class NovelChapterEditorFragment extends MastodonToolbarFragment{
	private static final long JOURNAL_DELAY=250,CHECKPOINT_DELAY=750,MAX_DELAY=5000;
	private final Handler main=new Handler(Looper.getMainLooper());
	private final ExecutorService io=Executors.newSingleThreadExecutor();
	private String accountID,workID,chapterID;
	private NovelEditorStorage storage;
	private EditText editor;
	private TextView title,status,count;
	private Button previous,next;
	private EditorModels.Chapter chapter;
	private List<EditorModels.Chapter> chapters=List.of();
	private long loadedGeneration,editGeneration,lastCheckpoint;
	private String checkpointText="";
	private boolean loading,dirty,saving,destroyed,applying,saveAgain;
	private int generation;
	private final Runnable journal=this::writeJournal;
	private final Runnable checkpoint=()->save(false);
	private final Runnable forced=()->save(false);

	@Override public void onCreate(Bundle state){super.onCreate(state);accountID=getArguments().getString("account");workID=getArguments().getString("work");chapterID=getArguments().getString("chapter");setTitle(R.string.novel_v2_creation);}
	@Override public View onCreateContentView(LayoutInflater inflater,ViewGroup container,Bundle state){
		View root=inflater.inflate(R.layout.fragment_novel_chapter_editor_v2,container,false);editor=root.findViewById(R.id.content);title=root.findViewById(R.id.chapter_title);status=root.findViewById(R.id.save_status);count=root.findViewById(R.id.character_count);previous=root.findViewById(R.id.previous_btn);next=root.findViewById(R.id.next_btn);
		root.findViewById(R.id.back_btn).setOnClickListener(v->saveThen(()->Nav.finish(this)));root.findViewById(R.id.structure_btn).setOnClickListener(v->saveThen(()->Nav.finish(this)));previous.setOnClickListener(v->move(-1));next.setOnClickListener(v->move(1));
		editor.setHorizontallyScrolling(false);editor.setSaveEnabled(false);editor.addTextChangedListener(new TextWatcher(){
			private String before; private int start,removed;
			@Override public void beforeTextChanged(CharSequence s,int st,int count,int after){before=s.toString();start=st;removed=count;}
			@Override public void onTextChanged(CharSequence s,int st,int before,int count){}
			@Override public void afterTextChanged(Editable editable){if(applying||loading)return;if(editable.length()>EditorLimits.CHAPTER_CONTENT){applying=true;editable.replace(0,editable.length(),before);editor.setSelection(Math.min(start,editor.length()));applying=false;android.widget.Toast.makeText(getActivity(),getString(R.string.novel_v2_editor_limit,EditorLimits.CHAPTER_CONTENT),android.widget.Toast.LENGTH_LONG).show();return;}String now=editable.toString();int prefix=start,suffix=Math.max(0,now.length()-(before.length()-start-removed));String inserted=now.substring(prefix,now.length()-suffix);editGeneration++;dirty=true;updateState();main.removeCallbacks(journal);main.postDelayed(journal,JOURNAL_DELAY);main.removeCallbacks(checkpoint);main.postDelayed(checkpoint,CHECKPOINT_DELAY);if(lastCheckpoint==0||android.os.SystemClock.elapsedRealtime()-lastCheckpoint>=MAX_DELAY){main.removeCallbacks(forced);main.post(forced);}updateCount();}
		});
		load();return root;
	}
	@Override public void onApplyWindowInsets(WindowInsets insets){View root=getView();if(root!=null){View footer=root.findViewById(R.id.editor_footer);footer.setPadding(V.dp(16)+insets.getSystemWindowInsetLeft(),V.dp(8),V.dp(16)+insets.getSystemWindowInsetRight(),V.dp(8)+insets.getSystemWindowInsetBottom());}super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0,insets.getSystemWindowInsetTop(),0,0));}
	private boolean valid(){return !destroyed&&AccountSessionManager.getInstance().tryGetAccount(accountID)!=null;}
	private void load(){loading=true;status.setText(R.string.loading);int token=++generation;io.execute(()->{try{if(storage==null)storage=new NovelEditorStorage(getActivity(),accountID);var structure=storage.structure(workID);var draft=storage.loadDraft(chapterID);var ch=storage.getChapter(chapterID);getActivity().runOnUiThread(()->{if(token!=generation||!valid()||editor==null)return;chapters=structure.chapters();chapter=ch;loadedGeneration=ch.currentGeneration();checkpointText=draft.content();applying=true;editor.setText(draft.content());editor.setSelection(Math.min(draft.selectionStart(),editor.length()));editor.scrollTo(0,draft.scrollY());applying=false;loading=false;dirty=draft.dirty();title.setText(ch.title());setTitle(structure.work().title());updateNavigation();updateCount();updateState();});}catch(RuntimeException error){getActivity().runOnUiThread(()->Nav.finish(this));}});}
	private void writeJournal(){if(!valid()||!dirty||saving)return;String now=editor.getText().toString();String base=checkpointText;int prefix=0;while(prefix<base.length()&&prefix<now.length()&&base.charAt(prefix)==now.charAt(prefix))prefix++;int suffix=0;while(suffix<base.length()-prefix&&suffix<now.length()-prefix&&base.charAt(base.length()-1-suffix)==now.charAt(now.length()-1-suffix))suffix++;String inserted=now.substring(prefix,now.length()-suffix);int deleted=base.length()-prefix-suffix;long expected=loadedGeneration;final int editStart=prefix;io.execute(()->{try{storage.appendJournal(chapterID,expected,editStart,deleted,inserted);}catch(RuntimeException ignored){}});}
	private void save(boolean immediate){
		if(editor==null||loading||!dirty)return;
		if(saving){saveAgain=true;if(immediate)waitBarrier();return;}
		main.removeCallbacks(journal);main.removeCallbacks(checkpoint);main.removeCallbacks(forced);
		String text=editor.getText().toString(),targetChapter=chapterID;int selection=editor.getSelectionStart(),scroll=editor.getScrollY();long expected=loadedGeneration,version=editGeneration;saving=true;saveAgain=false;status.setText(R.string.novel_v2_saving);
		io.execute(()->{try{var saved=storage.checkpoint(targetChapter,expected,text,selection,selection,scroll,true);getActivity().runOnUiThread(()->{if(!valid()||editor==null)return;saving=false;if(targetChapter.equals(chapterID)){loadedGeneration=saved.generation();checkpointText=text;lastCheckpoint=android.os.SystemClock.elapsedRealtime();if(editGeneration==version)dirty=false;updateState();if((saveAgain||editGeneration!=version)&&dirty)save(false);}});}catch(RuntimeException error){getActivity().runOnUiThread(()->{if(editor!=null){saving=false;saveAgain=false;status.setText(R.string.novel_v2_save_failed);}});}});
		if(immediate){waitBarrier();if(saveAgain&&editor!=null)save(true);}
	}
	private void waitBarrier(){try{io.submit(()->{}).get(4,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ignored){}}
	private void saveThen(Runnable navigation){
		if(editor==null||loading){navigation.run();return;}
		main.removeCallbacks(journal);main.removeCallbacks(checkpoint);main.removeCallbacks(forced);
		String text=editor.getText().toString(),target=chapterID;int selection=editor.getSelectionStart(),scroll=editor.getScrollY();long expected=loadedGeneration;
		status.setText(R.string.novel_v2_saving);editor.setEnabled(false);
		io.execute(()->{boolean ok=true;try{if(dirty)storage.checkpoint(target,expected,text,selection,selection,scroll,true);}catch(RuntimeException error){ok=false;}final boolean saved=ok;if(getActivity()!=null)getActivity().runOnUiThread(()->{if(editor==null)return;editor.setEnabled(true);if(saved)navigation.run();else status.setText(R.string.novel_v2_save_failed);});});
	}
	private void move(int delta){if(chapter==null)return;int index=chapters.indexOf(chapter);int target=index+delta;if(target<0||target>=chapters.size())return;String nextChapter=chapters.get(target).id();saveThen(()->{chapterID=nextChapter;getArguments().putString("chapter",chapterID);load();});}
	private void updateNavigation(){int index=chapter==null?-1:chapters.indexOf(chapter);previous.setEnabled(index>0);next.setEnabled(index>=0&&index<chapters.size()-1);}
	private void updateCount(){if(count!=null&&editor!=null)count.setText(getString(R.string.novel_v2_count,editor.length(),EditorLimits.CHAPTER_CONTENT));}
	private void updateState(){if(status==null)return;status.setText(saving?R.string.novel_v2_saving:dirty?R.string.novel_v2_unsaved:R.string.novel_v2_saved_local);}
	@Override public void onPause(){save(false);waitBarrier();super.onPause();}
	@Override public void onDestroyView(){generation++;main.removeCallbacksAndMessages(null);save(false);waitBarrier();editor=null;title=null;status=null;count=null;previous=null;next=null;super.onDestroyView();}
	@Override public void onDestroy(){destroyed=true;io.shutdown();try{io.awaitTermination(4,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(storage!=null)storage.close();super.onDestroy();}
}
