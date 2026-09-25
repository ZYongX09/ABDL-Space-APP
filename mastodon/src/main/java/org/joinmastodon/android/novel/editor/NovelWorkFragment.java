package org.joinmastodon.android.novel.editor;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.noveleditor.EditorModels;
import org.joinmastodon.noveleditor.NovelEditorStorage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

/** Local work structure and publishing entry point. */
public class NovelWorkFragment extends MastodonToolbarFragment{
	private final ExecutorService io=Executors.newSingleThreadExecutor();
	private final ExecutorService publishExecutor=Executors.newSingleThreadExecutor();
	private String accountID,workID;
	private NovelEditorStorage storage;
	private LinearLayout content,footer;
	private TextView publishStatus;
	private Button publishButton;
	private EditorModels.WorkStructure structure;
	private boolean publishing;
	private int generation;
	@Override public void onCreate(Bundle state){super.onCreate(state);accountID=getArguments().getString("account");workID=getArguments().getString("work");setTitle(R.string.novel_v2_structure);}
	@Override public View onCreateContentView(LayoutInflater inflater,ViewGroup container,Bundle state){LinearLayout root=new LinearLayout(getActivity());root.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(getActivity());content=NovelUi.column(getActivity());scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));footer=NovelUi.column(getActivity());footer.setPadding(V.dp(20),V.dp(4),V.dp(20),V.dp(12));publishStatus=NovelUi.label(footer,"");publishStatus.setVisibility(View.GONE);publishButton=NovelUi.primary(footer,getString(R.string.novel_v2_publish),this::publish);root.addView(footer,new LinearLayout.LayoutParams(-1,-2));load();return root;}
	@Override public void onApplyWindowInsets(WindowInsets insets){if(content!=null)content.setPadding(V.dp(20)+insets.getSystemWindowInsetLeft(),V.dp(12),V.dp(20)+insets.getSystemWindowInsetRight(),V.dp(24));if(footer!=null)footer.setPadding(V.dp(20)+insets.getSystemWindowInsetLeft(),V.dp(4),V.dp(20)+insets.getSystemWindowInsetRight(),V.dp(12)+insets.getSystemWindowInsetBottom());super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0,insets.getSystemWindowInsetTop(),0,0));}
	@Override protected void onShown(){super.onShown();if(content!=null)load();}
	private boolean valid(){return AccountSessionManager.getInstance().tryGetAccount(accountID)!=null;}
	private void load(){if(!valid()){Nav.finish(this);return;}int token=++generation;io.execute(()->{try{if(storage==null)storage=new NovelEditorStorage(getActivity(),accountID);var result=storage.structure(workID);getActivity().runOnUiThread(()->{if(token==generation&&content!=null)render(result);});}catch(RuntimeException e){getActivity().runOnUiThread(()->Nav.finish(this));}});}
	private void render(EditorModels.WorkStructure value){structure=value;setTitle(value.work().title());content.removeAllViews();NovelUi.title(content,value.work().title());if(!value.work().description().isBlank())NovelUi.body(content,value.work().description());NovelUi.label(content,getString(R.string.novel_v2_chapters,value.work().chapterCount(),value.work().characterCount()));NovelUi.heading(content,getString(R.string.novel_v2_structure));for(var volume:value.volumes()){LinearLayout section=NovelUi.card(content);NovelUi.heading(section,volume.title());for(var chapter:value.chapters())if(chapter.volumeId().equals(volume.id())){LinearLayout row=NovelUi.card(section);NovelUi.heading(row,chapter.title());NovelUi.label(row,chapter.contentLength()+" 字 · "+getString(R.string.novel_v2_saved_local));NovelUi.primary(row,getString(R.string.novel_v2_continue),()->openChapter(chapter.id()));}NovelUi.textButton(section,getString(R.string.novel_v2_add_chapter),()->newChapter(volume.id()));}NovelUi.body(content,getString(R.string.novel_v2_publish_hint));}
	private void newChapter(String volumeID){EditText input=new EditText(getActivity(),null,0,R.style.Widget_Mastodon_M3_EditText);input.setHint(R.string.novel_v2_chapter_title);new M3AlertDialogBuilder(getActivity()).setTitle(R.string.novel_v2_add_chapter).setView(input).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.add,(d,w)->{String title=input.getText().toString();io.execute(()->{try{var chapter=storage.addChapter(workID,volumeID,title);getActivity().runOnUiThread(()->openChapter(chapter.id()));}catch(RuntimeException e){getActivity().runOnUiThread(()->Toast.makeText(getActivity(),R.string.error,Toast.LENGTH_SHORT).show());}});}).show();}
	private void openChapter(String chapterID){Bundle args=new Bundle();args.putString("account",accountID);args.putString("work",workID);args.putString("chapter",chapterID);Nav.go(getActivity(),NovelChapterEditorFragment.class,args);}
	private void publish(){
		if(structure==null||publishing||!valid())return;
		if(structure.chapters().isEmpty()){Toast.makeText(getActivity(),R.string.novel_v2_empty,Toast.LENGTH_SHORT).show();return;}
		publishing=true;updatePublishing();String workIDFinal=workID;int token=generation;
		publishExecutor.execute(()->{try{
			NovelEditorStorage storage=new NovelEditorStorage(getActivity(),accountID);
			var result=new NovelPublishClient(getActivity(),accountID).publish(storage,workIDFinal,progress->{Activity activity=getActivity();if(activity!=null)activity.runOnUiThread(()->{if(token==generation&&publishStatus!=null&&structure!=null&&workIDFinal.equals(this.workID))publishStatus.setText(switch(progress.phase()){case "parts"->getString(R.string.novel_v2_publishing_parts,progress.itemsDone(),progress.itemsTotal());case "release"->getString(R.string.novel_v2_publishing_release);default->getString(R.string.novel_v2_publishing_start);});});});
			storage.close();Activity activity=getActivity();if(activity!=null)activity.runOnUiThread(()->{if(token!=generation||content==null)return;publishing=false;updatePublishing();Toast.makeText(activity,getString(R.string.novel_v2_publish_done,result.releaseVersion()),Toast.LENGTH_LONG).show();load();});
		}catch(Exception error){Activity activity=getActivity();if(activity!=null)activity.runOnUiThread(()->{if(token!=generation||content==null)return;publishing=false;updatePublishing();Toast.makeText(activity,publishError(error),Toast.LENGTH_LONG).show();});}
		});
	}
	private String publishError(Exception error){ return error instanceof org.joinmastodon.android.api.novels.NovelV2Api.ApiException api&&api.message2!=null ? api.message2 : getString(R.string.novel_v2_publish_failed); }
	private void updatePublishing(){if(publishButton!=null){publishButton.setEnabled(!publishing&&valid()&&structure!=null&&!structure.chapters().isEmpty());publishButton.setText(publishing?R.string.novel_v2_publishing_start:R.string.novel_v2_publish);}if(publishStatus!=null)publishStatus.setVisibility(publishing?View.VISIBLE:View.GONE);}
	@Override public void onDestroyView(){generation++;content=null;footer=null;publishStatus=null;publishButton=null;structure=null;super.onDestroyView();}
	@Override public void onDestroy(){generation++;io.shutdownNow();publishExecutor.shutdownNow();if(storage!=null)storage.close();storage=null;super.onDestroy();}
}
