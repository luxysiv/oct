package com.termux.app.opencode;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The launcher client: a thin window onto the OpenCode server.
 *
 * <p>This activity owns no state and runs no engine. It starts/binds
 * {@link OpenCodeDaemonService}, waits for the daemon's connection details, then
 * renders the server's own web UI in a WebView — OpenCode is the host, the app
 * is a window.</p>
 *
 * <p>Authentication reuses the browser flow. The daemon's password
 * ({@code opencode:<pw>}, HTTP Basic) authorises {@code POST /api/pair}; the
 * one-time code is redeemed at {@code /auth/connect/<code>}, which answers with
 * a {@code Set-Cookie: opencode_session_<port>} redirect that authenticates the
 * WebView. When the daemon restarts it prints a new password, so the window
 * re-pairs automatically on the next state change.</p>
 */
public final class OpenCodeClientActivity extends AppCompatActivity
    implements OpenCodeDaemonService.Listener {

    private static final int REQUEST_POST_NOTIFICATIONS = 7002;

    private WebView mWebView;
    private TextView mStatus;

    @Nullable
    private OpenCodeDaemonService mService;
    private String mPairedKey;
    private boolean mBound;

    /** Intent that opens (or brings forward) the client window. */
    public static Intent newInstance(Context context) {
        Intent intent = new Intent(context, OpenCodeClientActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            OpenCodeDaemonService.LocalBinder binder = (OpenCodeDaemonService.LocalBinder) service;
            mService = binder.getService();
            mService.addListener(OpenCodeClientActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);

        mWebView = new WebView(this);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (mStatus != null) mStatus.setVisibility(TextView.GONE);
            }
        });
        root.addView(mWebView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mStatus = new TextView(this);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setText("Đang khởi động OpenCode…");
        root.addView(mStatus, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(mWebView, true);

        requestNotificationPermissionIfNeeded();
        startAndBindDaemon();
    }

    private void startAndBindDaemon() {
        Intent intent = new Intent(this, OpenCodeDaemonService.class)
            .setAction(OpenCodeDaemonService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        mBound = bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    // ------------------------------------------------------------------
    // OpenCodeDaemonService.Listener
    // ------------------------------------------------------------------

    @Override
    public void onDaemonStateChanged(@Nullable final OpenCodeDaemon.Info info) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                handleDaemonInfo(info);
            }
        });
    }

    private void handleDaemonInfo(@Nullable OpenCodeDaemon.Info info) {
        if (info == null || !info.isValid()) {
            setStatus("Đang khởi động OpenCode…");
            return;
        }
        String key = info.url + "#" + info.password;
        if (key.equals(mPairedKey)) return;
        mPairedKey = key;
        pairAndLoad(info);
    }

    private void setStatus(String text) {
        if (mStatus == null) return;
        mStatus.setText(text);
        mStatus.setVisibility(TextView.VISIBLE);
    }

    private void pairAndLoad(final OpenCodeDaemon.Info info) {
        setStatus("Đang kết nối " + info.url + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String code = requestPairCode(info);
                final String target = code != null
                    ? info.url + "/auth/connect/" + code
                    : info.url + "/";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mWebView != null) mWebView.loadUrl(target);
                    }
                });
            }
        }, "opencode-pair").start();
    }

    /** {@code POST /api/pair} with Basic auth → one-time code, or {@code null}. */
    private String requestPairCode(OpenCodeDaemon.Info info) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(info.url + "/api/pair").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", basicAuth(info.password));
            connection.setRequestProperty("Accept", "application/json");
            OutputStream out = connection.getOutputStream();
            out.close();

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return null;

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return extractJsonString(body.toString(), "code");
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String basicAuth(String password) {
        String userInfo = "opencode:" + (password == null ? "" : password);
        return "Basic " + Base64.encodeToString(
            userInfo.getBytes(java.nio.charset.Charset.forName("UTF-8")), Base64.NO_WRAP);
    }

    /** Minimal reader for a flat {@code {"key":"value"}} field. */
    private static String extractJsonString(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) return null;
        int colon = json.indexOf(':', keyIndex + needle.length());
        if (colon < 0) return null;
        int open = json.indexOf('"', colon + 1);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        return json.substring(open + 1, close);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (mService != null) mService.removeListener(this);
        if (mBound) {
            try {
                unbindService(mConnection);
            } catch (Exception ignored) {
            }
            mBound = false;
        }
        mService = null;
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }
}
