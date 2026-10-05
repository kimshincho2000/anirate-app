package uz.anirate.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String SITE_URL = "https://anirate.wwwz.uz";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private LinearLayout layoutError;
    private Button btnRetry;
    private Button btnOpenDownloads;
    private FrameLayout fullscreenContainer;

    // In-App Downloads Management
    private LinearLayout layoutDownloads;
    private ListView listDownloads;
    private TextView tvEmptyDownloads;
    private Button btnCloseDownloads;
    private DownloadAdapter downloadAdapter;
    private final List<File> downloadedFiles = new ArrayList<>();

    // In-App Video Player
    private FrameLayout layoutInternalPlayer;
    private VideoView internalVideoView;
    private TextView tvPlayerTitle;
    private Button btnExitPlayer;
    private MediaController mediaController;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ConnectivityManager.NetworkCallback networkCallback;

    private boolean isAdminUser = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Content Protection: Block screenshots and screen recording by default
        // (Only unlocked if the logged-in user is verified as Admin)
        applyContentProtection(true);

        setContentView(R.layout.activity_main);

        initViews();
        setupWebView();
        setupSwipeRefresh();
        setupDownloadsUI();
        setupInternalPlayer();
        setupBackHandler();
        registerNetworkAutoReconnect();

        if (savedInstanceState == null) {
            if (isNetworkAvailable()) {
                webView.loadUrl(SITE_URL);
            } else {
                showOfflineScreen();
            }
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void applyContentProtection(boolean enable) {
        if (enable) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void initViews() {
        webView = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        progressBar = findViewById(R.id.progress_bar);
        layoutError = findViewById(R.id.layout_error);
        btnRetry = findViewById(R.id.btn_retry);
        btnOpenDownloads = findViewById(R.id.btn_open_downloads);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

        layoutDownloads = findViewById(R.id.layout_downloads);
        listDownloads = findViewById(R.id.list_downloads);
        tvEmptyDownloads = findViewById(R.id.tv_empty_downloads);
        btnCloseDownloads = findViewById(R.id.btn_close_downloads);

        layoutInternalPlayer = findViewById(R.id.layout_internal_player);
        internalVideoView = findViewById(R.id.internal_video_view);
        tvPlayerTitle = findViewById(R.id.tv_player_title);
        btnExitPlayer = findViewById(R.id.btn_exit_player);

        btnRetry.setOnClickListener(v -> {
            if (isNetworkAvailable()) {
                layoutError.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                webView.reload();
            } else {
                Toast.makeText(this, "Internet aloqasi yo'q. Qaytadan urinib ko'ring.", Toast.LENGTH_SHORT).show();
            }
        });

        btnOpenDownloads.setOnClickListener(v -> showDownloadsList());
        btnCloseDownloads.setOnClickListener(v -> {
            layoutDownloads.setVisibility(View.GONE);
            if (!isNetworkAvailable()) {
                layoutError.setVisibility(View.VISIBLE);
            } else {
                webView.setVisibility(View.VISIBLE);
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // Standard cache only - Do NOT over-cache to preserve user storage
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // User Agent
        String defaultUA = settings.getUserAgentString();
        settings.setUserAgentString(defaultUA + " AniRateApp/1.2");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // JavaScript Interface for App & Admin features
        webView.addJavascriptInterface(new WebAppInterface(), "AniRateNative");

        // Download Listener: Download anime episodes for in-app offline watching
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "AniRate_Episode_" + System.currentTimeMillis() + ".mp4";
                }

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("AniRate orqali oflayn ko'rish uchun yuklanmoqda...");
                request.setTitle(fileName);
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                // Save into public Movies/AniRate folder
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "AniRate/" + fileName);

                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(MainActivity.this, "📥 Anime yuklab olish boshlandi: " + fileName, Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                if (url.startsWith("tg:") || url.startsWith("https://t.me/") || url.startsWith("intent:")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {
                        return true;
                    }
                }

                if (url.contains("anirate.wwwz.uz")) {
                    return false;
                }

                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                layoutError.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);

                // Automatically check if logged-in user is an Admin
                checkAdminStatus();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showOfflineScreen();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    onHideCustomView();
                    return;
                }
                customView = view;
                customViewCallback = callback;

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                fullscreenContainer.setVisibility(View.VISIBLE);
                swipeRefresh.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                swipeRefresh.setVisibility(View.VISIBLE);

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }
                customView = null;
                customViewCallback = null;
            }
        });
    }

    // Checks if the website user is Admin: Unlocks screenshot protection for admin, blocks for guests/users
    private void checkAdminStatus() {
        String js = "(function() { " +
                "  try { " +
                "    var isAdmin = (document.cookie.indexOf('admin_ok') !== -1 || " +
                "                   document.body.innerHTML.indexOf('Boshqarish') !== -1 || " +
                "                   window.isSiteAdmin === true);" +
                "    if (window.AniRateNative) { window.AniRateNative.setAdmin(isAdmin); } " +
                "  } catch(e) {} " +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void setAdmin(boolean admin) {
            runOnUiThread(() -> {
                isAdminUser = admin;
                if (isAdminUser) {
                    applyContentProtection(false); // Admin: Screenshots & Recording allowed
                } else {
                    applyContentProtection(true);  // Normal users: Screenshots & Recording blocked
                }
            });
        }

        @JavascriptInterface
        public void openOfflineDownloads() {
            runOnUiThread(MainActivity.this::showDownloadsList);
        }
    }

    // In-App Downloads List
    private void setupDownloadsUI() {
        downloadAdapter = new DownloadAdapter();
        listDownloads.setAdapter(downloadAdapter);
    }

    private void showDownloadsList() {
        loadDownloadedAnimeFiles();
        layoutError.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        layoutDownloads.setVisibility(View.VISIBLE);
    }

    private void loadDownloadedAnimeFiles() {
        downloadedFiles.clear();

        // 1. Check public Movies/AniRate
        File publicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AniRate");
        if (publicDir.exists() && publicDir.isDirectory()) {
            File[] files = publicDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (isVideoFile(f)) downloadedFiles.add(f);
                }
            }
        }

        // 2. Check app external files
        File appDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (appDir != null && appDir.exists()) {
            File[] files = appDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (isVideoFile(f) && !downloadedFiles.contains(f)) downloadedFiles.add(f);
                }
            }
        }

        Collections.sort(downloadedFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        if (downloadedFiles.isEmpty()) {
            tvEmptyDownloads.setVisibility(View.VISIBLE);
            listDownloads.setVisibility(View.GONE);
        } else {
            tvEmptyDownloads.setVisibility(View.GONE);
            listDownloads.setVisibility(View.VISIBLE);
        }
        downloadAdapter.notifyDataSetChanged();
    }

    private boolean isVideoFile(File f) {
        String name = f.getName().toLowerCase();
        return f.isFile() && (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") || name.endsWith(".mov"));
    }

    // In-App Video Player (Plays downloaded animes directly inside the APK!)
    private void setupInternalPlayer() {
        mediaController = new MediaController(this);
        mediaController.setAnchorView(internalVideoView);
        internalVideoView.setMediaController(mediaController);

        internalVideoView.setOnPreparedListener(mp -> {
            progressBar.setVisibility(View.GONE);
            mp.start();
        });

        internalVideoView.setOnCompletionListener(mp -> {
            Toast.makeText(this, "Anime qismi yakunlandi", Toast.LENGTH_SHORT).show();
        });

        internalVideoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Videoni ochishda xatolik yuz berdi", Toast.LENGTH_SHORT).show();
            closeInternalPlayer();
            return true;
        });

        btnExitPlayer.setOnClickListener(v -> closeInternalPlayer());
    }

    private void playVideoInApp(File videoFile) {
        layoutDownloads.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        layoutInternalPlayer.setVisibility(View.VISIBLE);

        tvPlayerTitle.setText(videoFile.getName());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        internalVideoView.setVideoPath(videoFile.getAbsolutePath());
        internalVideoView.requestFocus();
        internalVideoView.start();
    }

    private void closeInternalPlayer() {
        if (internalVideoView.isPlaying()) {
            internalVideoView.stopPlayback();
        }
        layoutInternalPlayer.setVisibility(View.GONE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        layoutDownloads.setVisibility(View.VISIBLE);
    }

    private class DownloadAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return downloadedFiles.size();
        }

        @Override
        public File getItem(int position) {
            return downloadedFiles.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_download, parent, false);
            }

            File file = getItem(position);
            TextView title = convertView.findViewById(R.id.item_title);
            TextView size = convertView.findViewById(R.id.item_size);
            Button btnPlay = convertView.findViewById(R.id.btn_play);
            Button btnDelete = convertView.findViewById(R.id.btn_delete);

            title.setText(file.getName());
            long mb = file.length() / (1024 * 1024);
            size.setText(mb + " MB");

            // Play inside APK
            btnPlay.setOnClickListener(v -> playVideoInApp(file));

            // Delete to free memory
            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("O'chirish")
                        .setMessage("Haqiqatan ham ushbu animeni o'chirmoqchimisiz?")
                        .setPositiveButton("Ha", (dialog, which) -> {
                            file.delete();
                            loadDownloadedAnimeFiles();
                            Toast.makeText(MainActivity.this, "O'chirildi", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Yo'q", null)
                        .show();
            });

            return convertView;
        }
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(() -> {
            if (isNetworkAvailable()) {
                layoutError.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                webView.reload();
            } else {
                swipeRefresh.setRefreshing(false);
                showOfflineScreen();
            }
        });

        webView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            swipeRefresh.setEnabled(webView.getScrollY() == 0);
        });
    }

    private void setupBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (layoutInternalPlayer.getVisibility() == View.VISIBLE) {
                    closeInternalPlayer();
                } else if (layoutDownloads.getVisibility() == View.VISIBLE) {
                    layoutDownloads.setVisibility(View.GONE);
                    if (isNetworkAvailable()) {
                        webView.setVisibility(View.VISIBLE);
                    } else {
                        layoutError.setVisibility(View.VISIBLE);
                    }
                } else if (customView != null) {
                    if (webView.getWebChromeClient() != null) {
                        ((WebChromeClient) webView.getWebChromeClient()).onHideCustomView();
                    }
                } else if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void registerNetworkAutoReconnect() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        runOnUiThread(() -> {
                            if (layoutError.getVisibility() == View.VISIBLE && layoutDownloads.getVisibility() != View.VISIBLE && layoutInternalPlayer.getVisibility() != View.VISIBLE) {
                                layoutError.setVisibility(View.GONE);
                                webView.setVisibility(View.VISIBLE);
                                webView.reload();
                                Toast.makeText(MainActivity.this, "🟢 Internet qayta ulandi!", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                };
                cm.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
    }

    private void showOfflineScreen() {
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
        webView.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            return netInfo != null && netInfo.isConnected();
        }
        return false;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
