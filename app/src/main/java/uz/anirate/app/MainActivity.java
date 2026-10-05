package uz.anirate.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
import android.widget.ProgressBar;
import android.widget.SeekBar;
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
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String SITE_URL = "https://anirate.wwwz.uz";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private LinearLayout layoutError;
    private Button btnRetry;
    private Button btnOpenDownloads;
    private LinearLayout btnQuickDownloadsFab;
    private FrameLayout fullscreenContainer;

    // In-App Downloads Management
    private LinearLayout layoutDownloads;
    private ListView listDownloads;
    private TextView tvEmptyDownloads;
    private Button btnCloseDownloads;
    private DownloadAdapter downloadAdapter;
    private final List<File> downloadedFiles = new ArrayList<>();

    // 1:1 In-App Video Player (AniRate / GoldAnime Glassmorphism Engine)
    private FrameLayout layoutInternalPlayer;
    private VideoView internalVideoView;
    private View playerTouchSurface;
    private FrameLayout playerCenterPlayBtn;
    private TextView tvCenterPlayIcon;
    private LinearLayout rippleLeft;
    private LinearLayout rippleRight;
    private FrameLayout playerControlsOverlay;
    private TextView btnExitPlayer;
    private TextView tvPlayerTitle;
    private TextView btnPlayerSpeed;
    private TextView btnPlayerPlay;
    private TextView btnPlayerRewind;
    private TextView btnPlayerForward;
    private TextView tvPlayerCurrentTime;
    private SeekBar playerSeekBar;
    private TextView tvPlayerDuration;
    private TextView btnPlayerFullscreen;

    private MediaPlayer mediaPlayer;
    private boolean isUserSeeking = false;
    private float currentSpeed = 1.0f;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Handler hideControlsHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = this::hideControls;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (layoutInternalPlayer.getVisibility() == View.VISIBLE && internalVideoView != null) {
                if (!isUserSeeking && internalVideoView.isPlaying()) {
                    int cur = internalVideoView.getCurrentPosition();
                    int dur = internalVideoView.getDuration();
                    updateTimeline(cur, dur);
                }
                progressHandler.postDelayed(this, 500);
            }
        }
    };

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
        btnQuickDownloadsFab = findViewById(R.id.btn_quick_downloads_fab);
        fullscreenContainer = findViewById(R.id.fullscreen_container);

        layoutDownloads = findViewById(R.id.layout_downloads);
        listDownloads = findViewById(R.id.list_downloads);
        tvEmptyDownloads = findViewById(R.id.tv_empty_downloads);
        btnCloseDownloads = findViewById(R.id.btn_close_downloads);

        // 1:1 Video Player views
        layoutInternalPlayer = findViewById(R.id.layout_internal_player);
        internalVideoView = findViewById(R.id.internal_video_view);
        playerTouchSurface = findViewById(R.id.player_touch_surface);
        playerCenterPlayBtn = findViewById(R.id.player_center_play_btn);
        tvCenterPlayIcon = findViewById(R.id.tv_center_play_icon);
        rippleLeft = findViewById(R.id.ripple_left);
        rippleRight = findViewById(R.id.ripple_right);
        playerControlsOverlay = findViewById(R.id.player_controls_overlay);
        btnExitPlayer = findViewById(R.id.btn_exit_player);
        tvPlayerTitle = findViewById(R.id.tv_player_title);
        btnPlayerSpeed = findViewById(R.id.btn_player_speed);
        btnPlayerPlay = findViewById(R.id.btn_player_play);
        btnPlayerRewind = findViewById(R.id.btn_player_rewind);
        btnPlayerForward = findViewById(R.id.btn_player_forward);
        tvPlayerCurrentTime = findViewById(R.id.tv_player_current_time);
        playerSeekBar = findViewById(R.id.player_seekbar);
        tvPlayerDuration = findViewById(R.id.tv_player_duration);
        btnPlayerFullscreen = findViewById(R.id.btn_player_fullscreen);

        btnRetry.setOnClickListener(v -> {
            if (isNetworkAvailable()) {
                layoutError.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                btnQuickDownloadsFab.setVisibility(View.VISIBLE);
                webView.reload();
            } else {
                Toast.makeText(this, "Internet aloqasi yo'q. Qaytadan urinib ko'ring.", Toast.LENGTH_SHORT).show();
            }
        });

        btnOpenDownloads.setOnClickListener(v -> showDownloadsList());
        btnQuickDownloadsFab.setOnClickListener(v -> showDownloadsList());

        btnCloseDownloads.setOnClickListener(v -> {
            layoutDownloads.setVisibility(View.GONE);
            if (!isNetworkAvailable()) {
                layoutError.setVisibility(View.VISIBLE);
                btnQuickDownloadsFab.setVisibility(View.GONE);
            } else {
                webView.setVisibility(View.VISIBLE);
                btnQuickDownloadsFab.setVisibility(View.VISIBLE);
            }
        });
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
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
        settings.setUserAgentString(defaultUA + " AniRateApp/1.3");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // JavaScript Interfaces
        WebAppInterface webAppInterface = new WebAppInterface();
        webView.addJavascriptInterface(webAppInterface, "AniRateNative");
        webView.addJavascriptInterface(webAppInterface, "AndroidApp");

        // Download Listener: Standard fallback download
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            startDirectDownload(url, fileName);
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
                btnQuickDownloadsFab.setVisibility(View.VISIBLE);

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
                btnQuickDownloadsFab.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                swipeRefresh.setVisibility(View.VISIBLE);
                btnQuickDownloadsFab.setVisibility(View.VISIBLE);

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }
                customView = null;
                customViewCallback = null;
            }
        });
    }

    // Direct Video Download to Movies/AniRate
    private void startDirectDownload(String url, String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = "AniRate_Episode_" + System.currentTimeMillis() + ".mp4";
            }
            if (!fileName.toLowerCase().endsWith(".mp4") && !fileName.toLowerCase().endsWith(".mkv") && !fileName.toLowerCase().endsWith(".webm")) {
                fileName += ".mp4";
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType("video/mp4");
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("cookie", cookies);
            request.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            request.setDescription("AniRate orqali oflayn ko'rish uchun yuklanmoqda...");
            request.setTitle(fileName);
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            // Ensure directory exists
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AniRate");
            if (!dir.exists()) dir.mkdirs();

            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "AniRate/" + fileName);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(MainActivity.this, "📥 Anime yuklab olish boshlandi: " + fileName, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "Yuklab olishda xatolik: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
        public void downloadVideo(String url, String filename) {
            runOnUiThread(() -> startDirectDownload(url, filename));
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
        btnQuickDownloadsFab.setVisibility(View.GONE);
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

    // =========================================================================
    // 1:1 NATIVE VIDEO PLAYER ENGINE (AniRate / GoldAnime Glassmorphism Engine)
    // =========================================================================
    @SuppressLint("ClickableViewAccessibility")
    private void setupInternalPlayer() {
        // Gesture Detector for Single Tap (toggle controls) and Double Tap (+/- 10s seek)
        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControlsVisibility();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int width = playerTouchSurface.getWidth();
                if (width <= 0) width = 1;
                float x = e.getX();

                if (x < width * 0.38f) {
                    seekRelative(-10);
                } else if (x > width * 0.62f) {
                    seekRelative(10);
                } else {
                    togglePlayPause();
                }
                return true;
            }
        });

        playerTouchSurface.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });

        // Center Big Play/Pause Crystal Button
        playerCenterPlayBtn.setOnClickListener(v -> togglePlayPause());

        // Mini Play/Pause button in bottom island
        btnPlayerPlay.setOnClickListener(v -> togglePlayPause());

        // Relative skip buttons (-10s / +10s)
        btnPlayerRewind.setOnClickListener(v -> seekRelative(-10));
        btnPlayerForward.setOnClickListener(v -> seekRelative(10));

        // Speed Selector Button (0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x)
        btnPlayerSpeed.setOnClickListener(v -> showSpeedDialog());

        // Fullscreen toggle
        btnPlayerFullscreen.setOnClickListener(v -> {
            Toast.makeText(this, "To'liq ekran rejimi yoqilgan", Toast.LENGTH_SHORT).show();
            resetAutoHideTimer();
        });

        // Exit Player
        btnExitPlayer.setOnClickListener(v -> closeInternalPlayer());

        // Zero-Lag SeekBar scrubbing
        playerSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int duration = internalVideoView.getDuration();
                    int targetTime = (int) ((progress / 1000.0) * duration);
                    tvPlayerCurrentTime.setText(formatTime(targetTime));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
                hideControlsHandler.removeCallbacks(hideControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int duration = internalVideoView.getDuration();
                int targetTime = (int) ((seekBar.getProgress() / 1000.0) * duration);
                internalVideoView.seekTo(targetTime);
                isUserSeeking = false;
                resetAutoHideTimer();
            }
        });

        // Video Event Listeners
        internalVideoView.setOnPreparedListener(mp -> {
            mediaPlayer = mp;
            progressBar.setVisibility(View.GONE);
            applyPlaybackSpeed();

            int duration = internalVideoView.getDuration();
            tvPlayerDuration.setText(formatTime(duration));
            updateTimeline(0, duration);

            internalVideoView.start();
            tvCenterPlayIcon.setText("⏸");
            btnPlayerPlay.setText("⏸");

            progressHandler.post(progressRunnable);
            resetAutoHideTimer();
        });

        internalVideoView.setOnCompletionListener(mp -> {
            tvCenterPlayIcon.setText("▶");
            btnPlayerPlay.setText("▶");
            showControlsPermanently();
            Toast.makeText(this, "Anime qismi yakunlandi", Toast.LENGTH_SHORT).show();
        });

        internalVideoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Videoni ochishda xatolik yuz berdi", Toast.LENGTH_SHORT).show();
            closeInternalPlayer();
            return true;
        });
    }

    private void playVideoInApp(File videoFile) {
        layoutDownloads.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        btnQuickDownloadsFab.setVisibility(View.GONE);
        layoutInternalPlayer.setVisibility(View.VISIBLE);

        tvPlayerTitle.setText(videoFile.getName());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        showControlsPermanently();
        internalVideoView.setVideoPath(videoFile.getAbsolutePath());
        internalVideoView.requestFocus();
    }

    private void closeInternalPlayer() {
        progressHandler.removeCallbacks(progressRunnable);
        hideControlsHandler.removeCallbacks(hideControlsRunnable);

        if (internalVideoView.isPlaying()) {
            internalVideoView.stopPlayback();
        }
        mediaPlayer = null;
        currentSpeed = 1.0f;
        btnPlayerSpeed.setText("1.0x");

        layoutInternalPlayer.setVisibility(View.GONE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        layoutDownloads.setVisibility(View.VISIBLE);
    }

    private void togglePlayPause() {
        if (internalVideoView.isPlaying()) {
            internalVideoView.pause();
            tvCenterPlayIcon.setText("▶");
            btnPlayerPlay.setText("▶");
            showControlsPermanently();
        } else {
            internalVideoView.start();
            tvCenterPlayIcon.setText("⏸");
            btnPlayerPlay.setText("⏸");
            resetAutoHideTimer();
        }
    }

    private void seekRelative(int seconds) {
        int cur = internalVideoView.getCurrentPosition();
        int dur = internalVideoView.getDuration();
        int target = Math.max(0, Math.min(dur, cur + seconds * 1000));
        internalVideoView.seekTo(target);
        updateTimeline(target, dur);

        if (seconds < 0) {
            showSeekRipple(rippleLeft);
        } else {
            showSeekRipple(rippleRight);
        }
        resetAutoHideTimer();
    }

    private void showSeekRipple(View rippleView) {
        rippleView.animate().cancel();
        rippleView.setVisibility(View.VISIBLE);
        rippleView.setAlpha(1f);
        rippleView.animate()
                .alpha(0f)
                .setDuration(600)
                .withEndAction(() -> rippleView.setVisibility(View.GONE))
                .start();
    }

    private void updateTimeline(int currentMs, int durationMs) {
        tvPlayerCurrentTime.setText(formatTime(currentMs));
        if (durationMs > 0) {
            tvPlayerDuration.setText(formatTime(durationMs));
            int progress = (int) (((double) currentMs / durationMs) * 1000);
            playerSeekBar.setProgress(progress);
        }
    }

    private void showSpeedDialog() {
        String[] speeds = {"0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x"};
        float[] speedValues = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

        new AlertDialog.Builder(this)
                .setTitle("Ijro tezligi")
                .setItems(speeds, (dialog, which) -> {
                    currentSpeed = speedValues[which];
                    btnPlayerSpeed.setText(speeds[which].replace(" (Normal)", ""));
                    applyPlaybackSpeed();
                    resetAutoHideTimer();
                })
                .show();
    }

    private void applyPlaybackSpeed() {
        if (mediaPlayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PlaybackParams params = mediaPlayer.getPlaybackParams();
                params.setSpeed(currentSpeed);
                mediaPlayer.setPlaybackParams(params);
            } catch (Exception ignored) {}
        }
    }

    private void resetAutoHideTimer() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable);
        if (internalVideoView.isPlaying()) {
            hideControlsHandler.postDelayed(hideControlsRunnable, 3500);
        }
    }

    private void hideControls() {
        if (internalVideoView.isPlaying()) {
            playerControlsOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> playerControlsOverlay.setVisibility(View.GONE))
                    .start();

            playerCenterPlayBtn.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> playerCenterPlayBtn.setVisibility(View.GONE))
                    .start();
        }
    }

    private void showControls() {
        playerControlsOverlay.setVisibility(View.VISIBLE);
        playerControlsOverlay.animate().alpha(1f).setDuration(250).start();

        playerCenterPlayBtn.setVisibility(View.VISIBLE);
        playerCenterPlayBtn.animate().alpha(1f).setDuration(250).start();

        resetAutoHideTimer();
    }

    private void showControlsPermanently() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable);
        playerControlsOverlay.setVisibility(View.VISIBLE);
        playerControlsOverlay.setAlpha(1f);
        playerCenterPlayBtn.setVisibility(View.VISIBLE);
        playerCenterPlayBtn.setAlpha(1f);
    }

    private void toggleControlsVisibility() {
        if (playerControlsOverlay.getVisibility() == View.VISIBLE) {
            hideControls();
        } else {
            showControls();
        }
    }

    private String formatTime(int millis) {
        int seconds = (millis / 1000) % 60;
        int minutes = (millis / (1000 * 60)) % 60;
        int hours = (millis / (1000 * 60 * 60));
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        }
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
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface);

        swipeRefresh.setOnRefreshListener(() -> {
            if (isNetworkAvailable()) {
                layoutError.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                btnQuickDownloadsFab.setVisibility(View.VISIBLE);
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
                        btnQuickDownloadsFab.setVisibility(View.VISIBLE);
                    } else {
                        layoutError.setVisibility(View.VISIBLE);
                        btnQuickDownloadsFab.setVisibility(View.GONE);
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
                            btnQuickDownloadsFab.setVisibility(View.VISIBLE);
                            webView.reload();
                            Toast.makeText(MainActivity.this, "🟢 Internet qayta ulandi!", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            };

            try {
                cm.registerNetworkCallback(request, networkCallback);
            } catch (Exception ignored) {}
        }
    }

    private void showOfflineScreen() {
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
        webView.setVisibility(View.GONE);
        btnQuickDownloadsFab.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            );
        } else {
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            return networkInfo != null && networkInfo.isConnected();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        progressHandler.removeCallbacks(progressRunnable);
        hideControlsHandler.removeCallbacks(hideControlsRunnable);
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                } catch (Exception ignored) {}
            }
        }
    }
}
