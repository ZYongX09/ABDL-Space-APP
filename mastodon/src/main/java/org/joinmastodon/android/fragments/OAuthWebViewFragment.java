package org.joinmastodon.android.fragments;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;

import me.grishka.appkit.Nav;
import me.grishka.appkit.fragments.LoaderFragment;

public class OAuthWebViewFragment extends LoaderFragment {
    private static final String ARG_URL = "url";
    private WebView webView;
    private String url;

    public static OAuthWebViewFragment newInstance(String url) {
        OAuthWebViewFragment fragment = new OAuthWebViewFragment();
        Bundle args = new Bundle();
        args.putString(ARG_URL, url);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        url = getArguments().getString(ARG_URL);
        setTitle("授权登录");
    }

    @Override
    protected void doLoadData() {
        // No-op, WebView handles loading
    }

    @Override
    public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        webView = new WebView(getActivity());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String requestUrl = request.getUrl().toString();
                // Check if this is the OAuth callback
                if (requestUrl.startsWith("abdl-space://callback")) {
                    // Extract the authorization code from the URL
                    String code = request.getUrl().getQueryParameter("code");
                    if (code != null) {
                        // TODO: Exchange code for token
                        // For now, just close the WebView
                        Nav.finish(OAuthWebViewFragment.this);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                dataLoaded();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                // Handle error
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                setTitle(title);
            }
        });

        webView.loadUrl(url);
        return webView;
    }

    @Override
    public void onRefresh() {
        webView.reload();
    }

    @Override
    public void onToolbarNavigationClick() {
        Nav.finish(this);
    }
}
