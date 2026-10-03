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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.grishka.appkit.utils.V;

/** New private bookshelf UI. Private TXT/EPUB sync is reused; DOCX belongs to creation imports. */
public class NovelLibraryFragment extends MastodonToolbarFragment{
	private static final int PICK_PRIVATE=602;
	private final ExecutorService io=Executors.newSingleThreadExecutor();
	private String accountID;
	private NovelLibraryBridge bridge;
	private LinearLayout content;
	private int generation;
	public static NovelLibraryFragment newInstance(String accountID){Bundle args=new Bundle();args.putString("account",accountID);NovelLibraryFragment f=new NovelLibraryFragment();f.setArguments(args);return f;}
	@Override public void onCreate(Bundle state){super.onCreate(state);accountID=getArguments().getString("account");setTitle(R.string.novel_bookshelf);}
	@Override public View onCreateContentView(LayoutInflater inflater,ViewGroup container,Bundle state){ScrollView scroll=new ScrollView(getActivity());content=NovelUi.column(getActivity());scroll.addView(content);load();return scroll;}
	@Override public void onApplyWindowInsets(WindowInsets insets){if(content!=null)content.setPadding(V.dp(20)+insets.getSystemWindowInsetLeft(),V.dp(16),V.dp(20)+insets.getSystemWindowInsetRight(),V.dp(28)+insets.getSystemWindowInsetBottom());super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0,insets.getSystemWindowInsetTop(),0,0));}
	private boolean valid(){return AccountSessionManager.getInstance().tryGetAccount(accountID)!=null;}
	private void load(){if(!valid())return;int token=++generation;content.removeAllViews();NovelUi.body(content,getString(R.string.loading));io.execute(()->{try{if(bridge==null)bridge=new NovelLibraryBridge(getActivity().getApplication(),accountID);var books=bridge.books();getActivity().runOnUiThread(()->{if(token!=generation||content==null)return;render(books);});}catch(Exception e){getActivity().runOnUiThread(()->Toast.makeText(getActivity(),R.string.error,Toast.LENGTH_SHORT).show());}});}
	private void render(java.util.List<NovelLibraryBridge.Book> books){content.removeAllViews();NovelUi.title(content,getString(R.string.novel_bookshelf));NovelUi.label(content,"私人书架仅保存用于阅读的 TXT / EPUB；DOCX 请从创作中心导入。");LinearLayout switcher=new LinearLayout(getActivity());switcher.setOrientation(LinearLayout.HORIZONTAL);content.addView(switcher,new LinearLayout.LayoutParams(-1,-2));Button square=new Button(new android.view.ContextThemeWrapper(getActivity(),R.style.Widget_Mastodon_M3_Button_Outlined),null,0);square.setText(R.string.novel_v2_tab_square);square.setOnClickListener(v->((me.grishka.appkit.FragmentStackActivity)getActivity()).showFragmentClearingBackStack(NovelSquareFragment.newInstance(accountID)));switcher.addView(square,new LinearLayout.LayoutParams(0,-2,1));Button creation=new Button(new android.view.ContextThemeWrapper(getActivity(),R.style.Widget_Mastodon_M3_Button_Outlined),null,0);creation.setText(R.string.novel_v2_tab_creation);creation.setOnClickListener(v->((me.grishka.appkit.FragmentStackActivity)getActivity()).showFragmentClearingBackStack(NovelWorkspaceFragment.newInstance(accountID)));switcher.addView(creation,new LinearLayout.LayoutParams(0,-2,1));Button shelf=new Button(new android.view.ContextThemeWrapper(getActivity(),R.style.Widget_Mastodon_M3_Button_Filled),null,0);shelf.setText(R.string.novel_bookshelf);shelf.setEnabled(false);switcher.addView(shelf,new LinearLayout.LayoutParams(0,-2,1));NovelUi.primary(content,"上传 TXT / EPUB 到私人书架",this::pickPrivate);NovelUi.textButton(content,getString(R.string.sponsor_ui_refresh),()->{bridge.refresh();load();});if(books.isEmpty()){NovelUi.body(NovelUi.card(content),getString(R.string.novel_bookshelf_empty));return;}for(var book:books){LinearLayout card=NovelUi.card(content);NovelUi.heading(card,book.getTitle());if(book.getAuthor()!=null)NovelUi.label(card,book.getAuthor());NovelUi.label(card,book.getChapterCount()+" 章 · "+book.getDownloadState());if(book.getChapterCount()>0)NovelUi.primary(card,getString(R.string.novel_v2_open_reader),()->open(book));else NovelUi.primary(card,"下载到本机",()->{bridge.download(book);Toast.makeText(getActivity(),"已加入下载队列",Toast.LENGTH_SHORT).show();});}}
	private void pickPrivate(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","application/epub+zip"}).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,PICK_PRIVATE);}
	@Override public void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=PICK_PRIVATE||resultCode!=Activity.RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();int flags=data.getFlags();io.execute(()->{try{String name="小说",mime=getActivity().getContentResolver().getType(uri);try(var c=getActivity().getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);}String lower=name.toLowerCase(java.util.Locale.ROOT);String format=lower.endsWith(".epub")?"epub":"txt";if(!format.equals("epub")&&!(mime==null||mime.startsWith("text/")))throw new IllegalArgumentException("format");bridge.upload(uri,name.replaceFirst("(?i)\\.(txt|epub)$",""),"",format,format.equals("epub")?"application/epub+zip":"text/plain",flags);if(getActivity()!=null)getActivity().runOnUiThread(()->{Toast.makeText(getActivity(),"已加入上传队列",Toast.LENGTH_SHORT).show();load();});}catch(Exception e){if(getActivity()!=null)getActivity().runOnUiThread(()->Toast.makeText(getActivity(),R.string.error,Toast.LENGTH_SHORT).show());}});}
	private void open(NovelLibraryBridge.Book book){Intent intent=new Intent(getActivity(),org.joinmastodon.android.novel.NovelActivity.class);intent.putExtra(org.joinmastodon.android.novel.NovelActivity.EXTRA_ACCOUNT_ID,accountID);intent.putExtra(org.joinmastodon.android.novel.NovelActivity.EXTRA_PRIVATE_BOOK,book.getId());startActivity(intent);}
	@Override public void onDestroy(){generation++;io.shutdownNow();if(bridge!=null)bridge.close();super.onDestroy();}
}
