package org.joinmastodon.android.novel.editor;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
import org.joinmastodon.android.api.novels.NovelV2Api;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.grishka.appkit.utils.V;

/** New public square over immutable releases. Reading opens through the frozen reader host later. */
public class NovelSquareFragment extends MastodonToolbarFragment{
	private final ExecutorService io=Executors.newSingleThreadExecutor();
	private LinearLayout content;
	private EditText search;
	private String cursor, query="";
	private boolean loading, hasMore=true, destroyed;
	private int generation;
	private final List<NovelV2Api.SquareWorkResponse> works=new ArrayList<>();

	public static NovelSquareFragment newInstance(String accountID){
		Bundle args=new Bundle(); args.putString("account", accountID);
		NovelSquareFragment fragment=new NovelSquareFragment(); fragment.setArguments(args); return fragment;
	}
	@Override public void onCreate(Bundle state){ super.onCreate(state); setTitle(R.string.novel_v2_tab_square); }
	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		LinearLayout root=new LinearLayout(getActivity()); root.setOrientation(LinearLayout.VERTICAL);
		LinearLayout searchWrap=NovelUi.column(getActivity()); searchWrap.setPadding(V.dp(20), V.dp(12), V.dp(20), 0);
		search=new EditText(getActivity(), null, 0, R.style.Widget_Mastodon_M3_EditText); search.setHint(R.string.novel_v2_search_hint); search.setSingleLine(true);
		search.setOnEditorActionListener((v, action, event)->{ query=search.getText().toString().trim(); refresh(); return true; });
		searchWrap.addView(search, new LinearLayout.LayoutParams(-1, -2));
		LinearLayout switcher=new LinearLayout(getActivity()); switcher.setOrientation(LinearLayout.HORIZONTAL); switcher.setPadding(V.dp(20), V.dp(8), V.dp(20), 0);
		Button squareTab=new Button(new android.view.ContextThemeWrapper(getActivity(), R.style.Widget_Mastodon_M3_Button_Filled), null, 0); squareTab.setText(R.string.novel_v2_tab_square); squareTab.setAllCaps(false); squareTab.setEnabled(false); switcher.addView(squareTab, new LinearLayout.LayoutParams(0, -2, 1));
		Button creationTab=new Button(new android.view.ContextThemeWrapper(getActivity(), R.style.Widget_Mastodon_M3_Button_Outlined), null, 0); creationTab.setText(R.string.novel_v2_tab_creation); creationTab.setAllCaps(false); creationTab.setOnClickListener(v->((me.grishka.appkit.FragmentStackActivity)getActivity()).showFragmentClearingBackStack(NovelWorkspaceFragment.newInstance(getArguments().getString("account")))); switcher.addView(creationTab, new LinearLayout.LayoutParams(0, -2, 1));
		ScrollView scroll=new ScrollView(getActivity()); scroll.setFillViewport(true); content=NovelUi.column(getActivity()); scroll.addView(content);
		root.addView(searchWrap, new LinearLayout.LayoutParams(-1, -2)); root.addView(switcher, new LinearLayout.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
		scroll.setOnScrollChangeListener((v, oldX, oldY, newX, newY)->{ View child=scroll.getChildAt(0); if(child!=null && hasMore && !loading && scroll.getHeight()+scroll.getScrollY()>=child.getHeight()-V.dp(240)) loadMore(); });
		render(); refresh(); return root;
	}
	@Override public void onApplyWindowInsets(WindowInsets insets){ if(content!=null) content.setPadding(V.dp(20)+insets.getSystemWindowInsetLeft(), V.dp(12), V.dp(20)+insets.getSystemWindowInsetRight(), V.dp(28)+insets.getSystemWindowInsetBottom()); super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, 0)); }
	private boolean valid(){ return !destroyed && AccountSessionManager.getInstance().tryGetAccount(getArguments().getString("account"))!=null; }
	private void refresh(){ if(loading||!valid()) return; int token=++generation; loading=true; hasMore=true; cursor=null; io.execute(()->{ try{ NovelV2Api api=new NovelV2Api(AccountSessionManager.get(getArguments().getString("account"))); var page=api.squareWorks(null, query, 20); if(getActivity()==null)return; getActivity().runOnUiThread(()->{ if(token!=generation||content==null)return; loading=false; works.clear(); works.addAll(page.items); cursor=page.next_cursor; hasMore=page.next_cursor!=null; render(); }); }catch(Exception error){ if(getActivity()==null)return; getActivity().runOnUiThread(()->{ if(token!=generation||content==null)return; loading=false; hasMore=false; Toast.makeText(getActivity(), error.getMessage()!=null ? error.getMessage() : getString(R.string.error), Toast.LENGTH_SHORT).show(); render(); }); } }); }
	private void loadMore(){ if(loading||!hasMore||!valid()||cursor==null) return; int token=generation; loading=true; io.execute(()->{ try{ NovelV2Api api=new NovelV2Api(AccountSessionManager.get(getArguments().getString("account"))); var page=api.squareWorks(cursor, query, 20); if(getActivity()==null)return; getActivity().runOnUiThread(()->{ if(token!=generation||content==null)return; loading=false; works.addAll(page.items); cursor=page.next_cursor; hasMore=page.next_cursor!=null; render(); }); }catch(Exception error){ if(getActivity()==null)return; getActivity().runOnUiThread(()->loading=false); } }); }
	private void openWork(NovelV2Api.SquareWorkResponse work){
		io.execute(()->{try{
			NovelV2Api api=new NovelV2Api(AccountSessionManager.get(getArguments().getString("account")));
			NovelV2Api.SquareWorkResponse detail=api.squareWork(work.id);
			NovelV2Api.SquareChapter first=detail.volumes.stream().sorted(java.util.Comparator.comparingLong(v->v.sort_order)).flatMap(v->v.chapters.stream().sorted(java.util.Comparator.comparingLong(c->c.sort_order))).findFirst().orElse(null);
			if(first==null)throw new IllegalStateException("No chapter");
			if(getActivity()!=null)getActivity().runOnUiThread(()->{
				android.content.Intent intent=new android.content.Intent(getActivity(),org.joinmastodon.android.novel.NovelActivity.class);
				intent.putExtra(org.joinmastodon.android.novel.NovelActivity.EXTRA_ACCOUNT_ID,getArguments().getString("account"));
				intent.putExtra(org.joinmastodon.android.novel.NovelActivity.EXTRA_SQUARE_WORK,detail.id);
				intent.putExtra(org.joinmastodon.android.novel.NovelActivity.EXTRA_SQUARE_RELEASE,detail.release_id);
				intent.putExtra(org.joinmastodon.android.novel.NovelActivity.EXTRA_SQUARE_CHAPTER,first.id);
				startActivity(intent);
			});
		}catch(Exception error){if(getActivity()!=null)getActivity().runOnUiThread(()->Toast.makeText(getActivity(),R.string.error,Toast.LENGTH_SHORT).show());}});
	}
	private void render(){
		content.removeAllViews();
		if(works.isEmpty()){ NovelUi.body(NovelUi.card(content), getString(R.string.novel_v2_square_empty)); return; }
		for(var work : works){
			LinearLayout card=NovelUi.card(content); GradientDrawable bg=(GradientDrawable)card.getBackground(); bg.setStroke(V.dp(1), UiUtils.getThemeColor(content.getContext(), R.attr.colorM3OutlineVariant));
			NovelUi.heading(card, work.title); NovelUi.label(card, "@"+work.author.username+" · "+work.published_chapter_count+" 章");
			if(work.description!=null && !work.description.isBlank()) NovelUi.body(card, work.description.length()>140 ? work.description.substring(0, 140)+"…" : work.description);
			NovelUi.primary(card,getString(R.string.novel_v2_open_reader),()->openWork(work));
		}
		if(hasMore) NovelUi.label(content, loading ? getString(R.string.loading) : "");
	}
	@Override public void onDestroy(){ destroyed=true; generation++; io.shutdownNow(); super.onDestroy(); }
}
