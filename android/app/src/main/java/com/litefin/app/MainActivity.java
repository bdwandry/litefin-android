package com.litefin.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

/**
 * Litefin Android shell — hosts the Litefin web bundle in a WebView.
 *
 * The web bundle is served from https://appassets.androidplatform.net/assets/
 * via WebViewAssetLoader so that fetch(), XHR, localStorage, IndexedDB and
 * service workers all work exactly as they do on a real HTTP origin (this
 * sidesteps the file:// restrictions that other Litefin shells work around).
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    /** Virtual origin serving the bundled web app (copied to assets/webapp/ by the build). */
    private static final String START_URL = "https://appassets.androidplatform.net/assets/webapp/index.html";

    private WebView webView;
    private View nativeSplash;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen, edge-to-edge, solid dark background (the web app is dark-themed).
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);

        /*
         * True fullscreen: hide the status bar (clock, notifications, battery)
         * so the app owns the entire screen. The gesture navigation zones stay
         * live — the user can still pull down from the top edge to reveal the
         * notification shade, and the system re-hides it afterwards. The bar
         * auto-re-hides whenever focus returns (dialogs, splitscreen, back
         * from the shade, app resume) via setOnSystemUiVisibilityChangeListener
         * below and the onResume hook.
         */
        hideSystemBars();

        webView = new WebView(this);
        setContentView(webView);

        // Native splash: a plain black view shown over the WebView until the
        // web app signals readiness via AndroidBridge.notifyAppReady().
        nativeSplash = new View(this);
        nativeSplash.setBackgroundColor(Color.BLACK);
        addContentView(nativeSplash, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100); // Ignore OS font scaling — TV-style fixed layout

        /*
         * Mixed content: Jellyfin servers are frequently plain http on the LAN
         * (http://192.168.x.x:8096) while our virtual origin is https. WebView's
         * default MIXED_CONTENT_NEVER_ALLOW silently kills every fetch/XHR to
         * the server with a net::ERR_BLOCKED_BY_CLIENT-style failure, which
         * surfaces in the app as a generic "server unreachable" error.
         *
         * MIXED_CONTENT_ALWAYS_ALLOW lets the page load http content freely.
         * COMPATIBILITY_MODE is not sufficient here: it only auto-upgrades
         * https-upgradable content and still blocks passive/active http content
         * in many cases (and media playback from http servers fails outright).
         * A Jellyfin client's whole purpose is talking to user-chosen servers,
         * so an always-allow policy is the correct trade-off; cleartext isn't
         * blocked at the manifest level either (usesCleartextTraffic=true).
         */
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Optional remote debugging on chrome://inspect
        WebView.setWebContentsDebuggingEnabled(true);

        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Bridge — becomes window.AndroidBridge in JS
        webView.addJavascriptInterface(new LitefinBridge(this), "AndroidBridge");

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keep all navigation inside the shell; external links are not
                // opened in a browser for this simple port.
                return false;
            }

            /*
             * Accept server TLS certificates without bailing out. This covers
             * two real Jellyfin deployments the CORS-free fetch path must be
             * able to reach: plain-IP LAN servers (http works via mixed-content
             * allowance; https-on-IP almost always presents a cert that is not
             * valid for the IP), and self-signed/lets-encrypt-style setups on
             * custom hostnames. Tizen and webOS clients do not hard-fail here
             * either — a media client that refuses user-chosen servers over
             * certificate warnings is unusable for homelab use.
             */
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    /**
     * Enters sticky immersive fullscreen: hides the status AND navigation
     * bars. Sticky mode means a swipe from any edge temporarily reveals the
     * bars (notifications included) without unlinking fullscreen mode — the
     * system hides them again on its own.
     */
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Re-assert fullscreen whenever the system clears it (shade pulled,
        // dialog focus, split screen, etc.).
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                visibility -> {
                    if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        hideSystemBars();
                    }
                });
    }

    /**
     * Called by LitefinBridge when the web app signals boot completion.
     * Fades out the native splash.
     */
    public void onAppReady() {
        if (nativeSplash != null && nativeSplash.getVisibility() == View.VISIBLE) {
            nativeSplash.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                @Override
                public void run() {
                    nativeSplash.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    /**
     * Hardware Back button / gesture. The web app owns navigation history, so
     * the event is forwarded into JS (window.__litefinAndroidBack) where the
     * standard key:back pipeline handles modals, router.back(), and exit flow
     * (the web layer calls AndroidBridge.exitApp() when it wants to close).
     * If the web app has not booted yet (hook missing, e.g. during splash),
     * we fall back to moving the task to the background (never killing state).
     */
    @Override
    public void onBackPressed() {
        if (webView != null) {
            // Comma operator: invoke the hook, then report that it existed.
            webView.evaluateJavascript(
                    "(window.__litefinAndroidBack ? (window.__litefinAndroidBack(), 'handled') : '')",
                    value -> {
                        if (value == null || !value.contains("handled")) {
                            moveTaskToBack(true);
                        }
                    });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        // Re-enter fullscreen after returning from the notification shade,
        // recents, or another app.
        hideSystemBars();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
