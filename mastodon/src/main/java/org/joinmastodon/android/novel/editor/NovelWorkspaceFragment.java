package org.joinmastodon.android.novel.editor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.noveleditor.EditorModels;
import org.joinmastodon.noveleditor.ImportException;
import org.joinmastodon.noveleditor.ImportLimits;
import org.joinmastodon.noveleditor.NovelEditorStorage;
import org.joinmastodon.noveleditor.NovelImportParser;
import org.joinmastodon.noveleditor.EditorText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

/** New local-first creation centre. No reader code or old layout is used here. */
public class NovelWorkspaceFragment extends MastodonToolbarFragment{
	private static final int PICK_DOCUMENT=601;
	public static NovelWorkspaceFragment newInstance(String accountID){ return newInstance(accountID,null,0); }
	public static NovelWorkspaceFragment newInstance(String accountID,Uri externalDocument,int grantFlags){
		Bundle args=new Bundle(); args.putString("account",accountID); if(externalDocument!=null){args.putParcelable("externalDocument",externalDocument);args.putInt("externalGrantFlags",grantFlags);}
		NovelWorkspaceFragment fragment=new NovelWorkspaceFragment(); fragment.setArguments(args); return fragment;
	}
	private final ExecutorService io=Executors.newSingleThreadExecutor();
	private String accountID;
	private NovelEditorStorage storage;
	private LinearLayout content;
	private int generation;

	@Override public void onCreate(Bundle state){ super.onCreate(state); accountID=getArguments().getString("account"); setTitle(R.string.novel_v2_creation); }
	@Override public View onCreateContentView(LayoutInflater inflater,ViewGroup container,Bundle state){ ScrollView scroll=new ScrollView(getActivity()); scroll.setFillViewport(true); content=NovelUi.column(getActivity()); scroll.addView(content); renderLoading(); Uri external=getArguments().getParcelable("externalDocument"); if(external!=null){getArguments().remove("externalDocument");importDocument(external,getArguments().getInt("externalGrantFlags"));}else load(); return scroll; }
	@Override public void onApplyWindowInsets(WindowInsets insets){ if(content!=null) content.setPadding(V.dp(20)+insets.getSystemWindowInsetLeft(),V.dp(16),V.dp(20)+insets.getSystemWindowInsetRight(),V.dp(28)+insets.getSystemWindowInsetBottom()); super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0,insets.getSystemWindowInsetTop(),0,0)); }
	@Override protected void onShown(){ super.onShown(); if(content!=null) load(); }
	private boolean sessionValid(){ return AccountSessionManager.getInstance().tryGetAccount(accountID)!=null; }
	private void renderLoading(){ content.removeAllViews(); NovelUi.body(content,getString(R.string.loading)); }
	private void load(){
		if(!sessionValid()){ Nav.finish(this); return; }
		int token=++generation;
		io.execute(()->{
			try{ if(storage==null) storage=new NovelEditorStorage(getActivity(),accountID); var works=storage.listWorks(false); getActivity().runOnUiThread(()->{ if(token==generation&&content!=null)render(works); }); }
			catch(RuntimeException error){ getActivity().runOnUiThread(()->{ if(token==generation&&content!=null)showError(); }); }
		});
	}
	private void render(java.util.List<EditorModels.Work> works){
		content.removeAllViews(); NovelUi.title(content,getString(R.string.novel_v2_creation)); NovelUi.label(content,getString(R.string.novel_v2_creation_subtitle));
		LinearLayout switcher=new LinearLayout(getActivity()); switcher.setOrientation(LinearLayout.HORIZONTAL); LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2); sp.bottomMargin=V.dp(12); content.addView(switcher,sp);
		Button squareTab=new Button(new android.view.ContextThemeWrapper(getActivity(),R.style.Widget_Mastodon_M3_Button_Outlined),null,0); squareTab.setText(R.string.novel_v2_tab_square); squareTab.setAllCaps(false); squareTab.setOnClickListener(v->((me.grishka.appkit.FragmentStackActivity)getActivity()).showFragmentClearingBackStack(NovelSquareFragment.newInstance(accountID))); switcher.addView(squareTab,new LinearLayout.LayoutParams(0,-2,1));
		Button creationTab=new Button(new android.view.ContextThemeWrapper(getActivity(),R.style.Widget_Mastodon_M3_Button_Filled),null,0); creationTab.setText(R.string.novel_v2_tab_creation); creationTab.setAllCaps(false); creationTab.setEnabled(false); switcher.addView(creationTab,new LinearLayout.LayoutParams(0,-2,1));
		Button libraryTab=new Button(new android.view.ContextThemeWrapper(getActivity(),R.style.Widget_Mastodon_M3_Button_Outlined),null,0);libraryTab.setText(R.string.novel_bookshelf);libraryTab.setAllCaps(false);libraryTab.setOnClickListener(v->((me.grishka.appkit.FragmentStackActivity)getActivity()).showFragmentClearingBackStack(NovelLibraryFragment.newInstance(accountID)));switcher.addView(libraryTab,new LinearLayout.LayoutParams(0,-2,1));
		LinearLayout actions=NovelUi.card(content); NovelUi.primary(actions,getString(R.string.novel_v2_new_work),this::newWork); NovelUi.secondary(actions,getString(R.string.novel_v2_import),this::pickDocument);
		NovelUi.heading(content,getString(R.string.novel_v2_recent));
		if(works.isEmpty()) NovelUi.body(NovelUi.card(content),getString(R.string.novel_v2_empty));
		for(EditorModels.Work work:works){ LinearLayout card=NovelUi.card(content); NovelUi.heading(card,work.title()); NovelUi.label(card,getString(R.string.novel_v2_chapters,work.chapterCount(),work.characterCount())); NovelUi.label(card,getString(R.string.novel_v2_saved_local)); NovelUi.primary(card,getString(R.string.novel_v2_continue),()->openWork(work.id())); NovelUi.textButton(card,getString(R.string.novel_v2_delete),()->trash(work)); }
		NovelUi.textButton(content,getString(R.string.novel_v2_trash),this::showTrash);
	}
	private void newWork(){
		LinearLayout form=NovelUi.column(getActivity()); EditText title=new EditText(getActivity(),null,0,R.style.Widget_Mastodon_M3_EditText); title.setHint(R.string.novel_v2_work_title); form.addView(title,new LinearLayout.LayoutParams(-1,-2)); EditText description=new EditText(getActivity(),null,0,R.style.Widget_Mastodon_M3_EditText); description.setHint(R.string.novel_v2_description); description.setMinLines(3); form.addView(description,new LinearLayout.LayoutParams(-1,-2));
		new M3AlertDialogBuilder(getActivity()).setTitle(R.string.novel_v2_new_work).setView(form).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.novel_v2_create,(d,w)->{
			String t=title.getText().toString(); if(t.trim().isEmpty()){ Toast.makeText(getActivity(),R.string.novel_v2_work_title,Toast.LENGTH_SHORT).show(); return; }
			io.execute(()->{ try{ var created=storage.createWork(t,description.getText().toString(),"fiction"); getActivity().runOnUiThread(()->openChapter(created.workId(),created.chapterId())); }catch(RuntimeException e){ getActivity().runOnUiThread(this::showError); } });
		}).show();
	}
	private void pickDocument(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","application/epub+zip","application/vnd.openxmlformats-officedocument.wordprocessingml.document"}).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,PICK_DOCUMENT); }
	@Override public void onActivityResult(int requestCode,int resultCode,Intent data){ super.onActivityResult(requestCode,resultCode,data); if(requestCode==PICK_DOCUMENT&&resultCode==Activity.RESULT_OK&&data!=null&&data.getData()!=null) importDocument(data.getData(),data.getFlags()); }
	private void importDocument(Uri uri,int flags){
		renderLoading(); int token=++generation;
		io.execute(()->{ File staged=null; try{
			try{ getActivity().getContentResolver().takePersistableUriPermission(uri,flags&Intent.FLAG_GRANT_READ_URI_PERMISSION); }catch(SecurityException ignored){}
			String displayName="document",mime=getActivity().getContentResolver().getType(uri); long known=-1;
			try(var c=getActivity().getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){ if(c!=null&&c.moveToFirst()){ displayName=c.getString(0); if(!c.isNull(1))known=c.getLong(1); } }
			if(known>ImportLimits.SOURCE_BYTES)throw new ImportException("SOURCE_TOO_LARGE","File too large");
			File dir=new File(getActivity().getNoBackupFilesDir(),"novel-editor/imports/"+java.util.UUID.randomUUID()); if(!dir.mkdirs())throw new ImportException("STORAGE_FULL","Unable to create staging directory"); File part=new File(dir,"source.part"); MessageDigest digest=MessageDigest.getInstance("SHA-256"); long count=0;
			try(InputStream in=getActivity().getContentResolver().openInputStream(uri); FileOutputStream out=new FileOutputStream(part)){ if(in==null)throw new ImportException("URI_PERMISSION_DENIED","Unable to read file"); byte[] buffer=new byte[64*1024]; for(int n;(n=in.read(buffer))>=0;){ count+=n;if(count>ImportLimits.SOURCE_BYTES)throw new ImportException("SOURCE_TOO_LARGE","File too large");digest.update(buffer,0,n);out.write(buffer,0,n);} out.getFD().sync(); }
			staged=new File(dir,"source"); if(!part.renameTo(staged))throw new ImportException("STORAGE_FULL","Unable to commit staged file"); String hash=hex(digest.digest()); var job=storage.createImportJob(displayName,mime,staged.getAbsolutePath(),count,hash); var parsed=new NovelImportParser().parse(staged,displayName,()->token!=generation); storage.storeImportResult(job.id(),parsed); final String name=displayName; getActivity().runOnUiThread(()->{ if(token==generation&&content!=null)showImportReview(job.id(),parsed,name); });
		}catch(Exception error){ if(staged!=null)staged.delete(); getActivity().runOnUiThread(()->{ if(token==generation&&content!=null){ Toast.makeText(getActivity(),error instanceof ImportException?error.getMessage():getString(R.string.novel_v2_invalid_file),Toast.LENGTH_LONG).show(); load(); } }); }
		});
	}
	private void showImportReview(String jobId,org.joinmastodon.noveleditor.ImportModels.Result parsed,String displayName){
		content.removeAllViews(); NovelUi.title(content,getString(R.string.novel_v2_import_review)); NovelUi.heading(content,parsed.metadata().title()); NovelUi.label(content,parsed.chapters().size()+" 章 · "+parsed.metadata().format().toUpperCase(java.util.Locale.ROOT)); if(!parsed.warnings().isEmpty()){ NovelUi.heading(content,getString(R.string.novel_v2_import_warnings)); for(var warning:parsed.warnings())NovelUi.body(NovelUi.card(content),warning.message()); }
		NovelUi.primary(content,getString(R.string.novel_v2_import_review),()->io.execute(()->{ try{ var created=storage.commitImport(jobId,parsed.metadata().title(),"","fiction"); getActivity().runOnUiThread(()->{ Toast.makeText(getActivity(),R.string.novel_v2_import_done,Toast.LENGTH_SHORT).show(); openWork(created.workId()); }); }catch(RuntimeException e){ getActivity().runOnUiThread(this::showError); } })); NovelUi.textButton(content,getString(R.string.cancel),this::load);
	}
	private void openWork(String id){ Bundle args=new Bundle();args.putString("account",accountID);args.putString("work",id);Nav.go(getActivity(),NovelWorkFragment.class,args); }
	private void openChapter(String work,String chapter){ Bundle args=new Bundle();args.putString("account",accountID);args.putString("work",work);args.putString("chapter",chapter);Nav.go(getActivity(),NovelChapterEditorFragment.class,args); }
	private void trash(EditorModels.Work work){ io.execute(()->{storage.softDeleteWork(work.id());getActivity().runOnUiThread(this::load);}); }
	private void showTrash(){ io.execute(()->{var trash=storage.listWorks(true);getActivity().runOnUiThread(()->{content.removeAllViews();NovelUi.title(content,getString(R.string.novel_v2_trash));for(var work:trash){var card=NovelUi.card(content);NovelUi.heading(card,work.title());NovelUi.primary(card,getString(R.string.novel_v2_restore),()->io.execute(()->{storage.restoreWork(work.id());getActivity().runOnUiThread(this::load);}));NovelUi.secondary(card,getString(R.string.novel_v2_permanent_delete),()->io.execute(()->{storage.permanentlyDeleteWork(work.id());getActivity().runOnUiThread(this::showTrash);}));}NovelUi.textButton(content,getString(R.string.back),this::load);});}); }
	private void showError(){ Toast.makeText(getActivity(),R.string.error,Toast.LENGTH_LONG).show(); }
	private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
	@Override public void onDestroy(){ generation++; io.shutdownNow(); if(storage!=null)storage.close(); super.onDestroy(); }
}
