package uz.anirate.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import android.text.InputType;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RemoteViews;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import android.util.Log;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final String SITE_URL = "https://anirate.wwwz.uz";
    private static final String NOTIFICATION_CHANNEL_ID = "anirate_downloads_channel_v4";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private LinearLayout layoutError;
    private View btnRetry;
    private View btnOpenDownloads;
    private View btnQuickDownloadsFab;
    private FrameLayout fullscreenContainer;

    // In-App Downloads Management
    private LinearLayout layoutDownloads;
    private ListView listDownloads;
    private TextView tvEmptyDownloads;
    private View btnCloseDownloads;
    private View btnAdminToggle;
    private TextView tvAdminLabel;
    private DownloadAdapter downloadAdapter;
    private final List<File> downloadedFiles = new ArrayList<>();

    // 1:1 In-App Video Player (AniRate / GoldAnime iOS Glass Engine)
    private FrameLayout layoutInternalPlayer;
    private VideoView internalVideoView;
    private View playerTouchSurface;
    private FrameLayout playerCenterPlayBtn;
    private ImageView ivCenterPlayIcon;
    private LinearLayout rippleLeft;
    private LinearLayout rippleRight;
    private FrameLayout playerControlsOverlay;
    private View btnExitPlayer;
    private TextView tvPlayerTitle;
    private View btnPlayerSpeed;
    private TextView tvPlayerSpeedLabel;
    private FrameLayout btnPlayerPlay;
    private ImageView ivBottomPlayIcon;
    private FrameLayout btnPlayerRewind;
    private FrameLayout btnPlayerForward;
    private TextView tvPlayerCurrentTime;
    private SeekBar playerSeekBar;
    private TextView tvPlayerDuration;
    private FrameLayout btnPlayerFullscreen;

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
    private BroadcastReceiver downloadCompleteReceiver;

    // iOS In-App Dynamic Notification Banner
    private LinearLayout iosInAppBanner;
    private ImageView inAppBannerIcon;
    private TextView inAppBannerTitle;
    private TextView inAppBannerSubtitle;
    private TextView inAppBannerBadge;
    private ProgressBar inAppBannerProgressBar;
    private final Handler bannerHandler = new Handler(Looper.getMainLooper());
    private Runnable hideBannerRunnable;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Content Protection: Check persisted Admin mode from SharedPreferences
        SharedPreferences prefs = getSharedPreferences("anirate_prefs", Context.MODE_PRIVATE);
        isAdminUser = prefs.getBoolean("is_admin_mode", false);
        applyContentProtection(!isAdminUser);

        setContentView(R.layout.activity_main);

        createNotificationChannel();
        checkAndRequestAllPermissions();

        initViews();
        setupWebView();
        setupSwipeRefresh();
        setupDownloadsUI();
        setupInternalPlayer();
        setupBackHandler();
        registerNetworkAutoReconnect();
        registerDownloadReceiver();

        handleIncomingIntent(getIntent());

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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("open_downloads", false)) {
            showDownloadsList();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "AniRate Yuklab Olishlar",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Anime qismlarini yuklab olish holati va foizi");
            channel.enableVibration(true);
            channel.setShowBadge(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void checkAndRequestAllPermissions() {
        List<String> neededPermissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    neededPermissions.toArray(new String[0]), 101);
        }
    }

    private void applyContentProtection(boolean enable) {
        if (enable) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void updateAdminButtonState() {
        if (tvAdminLabel != null && btnAdminToggle != null) {
            if (isAdminUser) {
                tvAdminLabel.setText("Admin (Faol)");
                btnAdminToggle.setBackgroundResource(R.drawable.bg_ios_primary_btn);
            } else {
                tvAdminLabel.setText("Admin");
                btnAdminToggle.setBackgroundResource(R.drawable.bg_ios_glass_pill);
            }
        }
    }

    private void setAdminStatus(boolean admin) {
        isAdminUser = admin;
        getSharedPreferences("anirate_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_admin_mode", admin)
                .apply();
        applyContentProtection(!admin);
        updateAdminButtonState();
    }

    private void showAdminUnlockDialog() {
        if (isAdminUser) {
            new AlertDialog.Builder(this)
                    .setTitle("👑 Admin Rejimi Faol")
                    .setMessage("Siz hozirda tasdiqlangan Admin maqomidasiz. Barcha skrinshot va video olish cheklovlari olib tashlangan.\n\nAdmin rejimidan chiqmoqchimisiz?")
                    .setPositiveButton("Chiqish (Logout)", (dialog, which) -> {
                        setAdminStatus(false);
                        Toast.makeText(this, "Admin rejimidan chiqildi. Kontent himoyasi yoqildi.", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Yopish", null)
                    .show();
            return;
        }

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Admin paroli (1234negr)");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = (int) (20 * getResources().getDisplayMetrics().density);
        params.leftMargin = margin;
        params.rightMargin = margin;
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("👑 Admin sifatida kirish")
                .setMessage("Admin parolini kiriting (standart: 1234negr yoki rofi):")
                .setView(container)
                .setPositiveButton("Tasdiqlash", (dialog, which) -> {
                    String pass = input.getText().toString().trim();
                    if ("1234negr".equals(pass) || "rofi".equals(pass)) {
                        setAdminStatus(true);
                        Toast.makeText(this, "👑 Tabriklaymiz! Siz Admin sifatida tasdiqlandingiz. Cheklovlar olib tashlandi!", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "❌ Noto'g'ri parol!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Bekor qilish", null)
                .show();
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
        btnAdminToggle = findViewById(R.id.btn_admin_toggle);
        tvAdminLabel = findViewById(R.id.tv_admin_label);
        updateAdminButtonState();

        if (btnAdminToggle != null) {
            btnAdminToggle.setOnClickListener(v -> showAdminUnlockDialog());
        }

        // iOS In-App Dynamic Notification Banner
        iosInAppBanner = findViewById(R.id.ios_inapp_banner);
        inAppBannerIcon = findViewById(R.id.inapp_banner_icon);
        inAppBannerTitle = findViewById(R.id.inapp_banner_title);
        inAppBannerSubtitle = findViewById(R.id.inapp_banner_subtitle);
        inAppBannerBadge = findViewById(R.id.inapp_banner_badge);
        inAppBannerProgressBar = findViewById(R.id.inapp_banner_progressbar);

        if (iosInAppBanner != null) {
            iosInAppBanner.setOnClickListener(v -> {
                showDownloadsList();
                iosInAppBanner.setVisibility(View.GONE);
            });
        }

        // 1:1 Video Player views
        layoutInternalPlayer = findViewById(R.id.layout_internal_player);
        internalVideoView = findViewById(R.id.internal_video_view);
        playerTouchSurface = findViewById(R.id.player_touch_surface);
        playerCenterPlayBtn = findViewById(R.id.player_center_play_btn);
        ivCenterPlayIcon = findViewById(R.id.iv_center_play_icon);
        rippleLeft = findViewById(R.id.ripple_left);
        rippleRight = findViewById(R.id.ripple_right);
        playerControlsOverlay = findViewById(R.id.player_controls_overlay);
        btnExitPlayer = findViewById(R.id.btn_exit_player);
        tvPlayerTitle = findViewById(R.id.tv_player_title);
        btnPlayerSpeed = findViewById(R.id.btn_player_speed);
        tvPlayerSpeedLabel = findViewById(R.id.tv_player_speed_label);
        btnPlayerPlay = findViewById(R.id.btn_player_play);
        ivBottomPlayIcon = findViewById(R.id.iv_bottom_play_icon);
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
        btnQuickDownloadsFab.setOnLongClickListener(v -> {
            showAdminUnlockDialog();
            return true;
        });

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

                if (url.contains("admin_token=rofi") || (url.contains("admin.php") && url.contains("step=panel"))) {
                    setAdminStatus(true);
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

                if (url.contains("admin_token=rofi") || (url.contains("admin.php") && url.contains("step=panel"))) {
                    setAdminStatus(true);
                }

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

    // Send diagnostic logs to server (app-log.php) to monitor download steps and errors
    private void sendRemoteLog(String tag, String message) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://anirate.wwwz.uz/app-log.php");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                JSONObject obj = new JSONObject();
                obj.put("tag", tag);
                obj.put("message", message);
                byte[] data = obj.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                    os.flush();
                }
                conn.getResponseCode();
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // Direct Video Download to App Storage via Reliable Native Background Stream
    private void startDirectDownload(String url, String fileName) {
        try {
            sendRemoteLog("DOWNLOAD_CLICK", "Raw URL: " + url + " | Raw FileName: " + fileName);

            if (url == null || url.trim().isEmpty()) {
                Toast.makeText(MainActivity.this, "Video manzili topilmadi", Toast.LENGTH_SHORT).show();
                sendRemoteLog("DOWNLOAD_CLICK_ERR", "URL is empty");
                return;
            }
            url = url.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                if (url.startsWith("/")) {
                    url = "https://anirate.wwwz.uz" + url;
                } else {
                    url = "https://anirate.wwwz.uz/" + url;
                }
            }

            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = "AniRate_Episode_" + System.currentTimeMillis() + ".mp4";
            }
            // Sanitize illegal filesystem characters: \ / : * ? " < > |
            fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (fileName.isEmpty()) {
                fileName = "AniRate_Episode_" + System.currentTimeMillis() + ".mp4";
            }
            if (!fileName.toLowerCase().endsWith(".mp4") && !fileName.toLowerCase().endsWith(".mkv") && !fileName.toLowerCase().endsWith(".webm")) {
                fileName += ".mp4";
            }

            if (url.contains("episode-proxy.php")) {
                if (!url.contains("download=")) {
                    url += (url.contains("?") ? "&" : "?") + "download=1";
                }
                if (!url.contains("name=")) {
                    url += "&name=" + Uri.encode(fileName);
                }
            }

            // Must capture userAgent and cookies safely on UI thread
            String userAgent = "Mozilla/5.0 (Linux; Android 10; Mobile) AniRateApp";
            try {
                if (webView != null && webView.getSettings() != null) {
                    userAgent = webView.getSettings().getUserAgentString();
                }
            } catch (Exception ignored) {}

            String cookies = "";
            try {
                cookies = CookieManager.getInstance().getCookie(url);
            } catch (Exception ignored) {}

            Toast.makeText(MainActivity.this, "Yuklab olish boshlandi: " + fileName, Toast.LENGTH_SHORT).show();
            startNativeDownload(url, fileName, userAgent, cookies);
        } catch (Exception e) {
            sendRemoteLog("DOWNLOAD_DIRECT_ERR", "Error in startDirectDownload: " + Log.getStackTraceString(e));
            Toast.makeText(MainActivity.this, "Yuklab olishda xatolik: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private RemoteViews createIosNotificationView(String title, String subtitle, String badgeText, int progress, boolean isDone, boolean isFailed) {
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification_ios_banner);
        views.setImageViewResource(R.id.ios_notif_icon, R.drawable.ic_download);
        views.setTextViewText(R.id.ios_notif_title, title);
        views.setTextViewText(R.id.ios_notif_subtitle, subtitle);
        views.setTextViewText(R.id.ios_notif_percentage, badgeText);

        if (isDone) {
            views.setTextColor(R.id.ios_notif_percentage, Color.parseColor("#34C759"));
            views.setViewVisibility(R.id.ios_notif_progressbar, View.GONE);
        } else if (isFailed) {
            views.setTextColor(R.id.ios_notif_percentage, Color.parseColor("#FF3B30"));
            views.setViewVisibility(R.id.ios_notif_progressbar, View.GONE);
        } else {
            views.setTextColor(R.id.ios_notif_percentage, Color.parseColor("#0A84FF"));
            views.setViewVisibility(R.id.ios_notif_progressbar, View.VISIBLE);
            views.setProgressBar(R.id.ios_notif_progressbar, 100, progress, false);
        }
        return views;
    }

    private void updateInAppNotification(String title, String subtitle, int progress, boolean isDone, boolean isFailed) {
        runOnUiThread(() -> {
            if (iosInAppBanner == null) return;

            if (isDone) {
                inAppBannerIcon.setImageResource(R.drawable.ic_check);
                inAppBannerTitle.setText("AniRate • Yuklab olindi! ✨");
                inAppBannerSubtitle.setText(subtitle + " — ko'rish uchun bosing");
                inAppBannerBadge.setText("Tayyor");
                inAppBannerBadge.setTextColor(Color.parseColor("#34C759"));
                inAppBannerProgressBar.setVisibility(View.GONE);

                if (hideBannerRunnable != null) bannerHandler.removeCallbacks(hideBannerRunnable);
                hideBannerRunnable = () -> {
                    iosInAppBanner.animate()
                            .alpha(0f)
                            .translationY(-60f)
                            .setDuration(350)
                            .withEndAction(() -> iosInAppBanner.setVisibility(View.GONE))
                            .start();
                };
                bannerHandler.postDelayed(hideBannerRunnable, 4500);
            } else if (isFailed) {
                inAppBannerIcon.setImageResource(R.drawable.ic_trash);
                inAppBannerTitle.setText("Yuklash to'xtatildi");
                inAppBannerSubtitle.setText(subtitle);
                inAppBannerBadge.setText("Xatolik");
                inAppBannerBadge.setTextColor(Color.parseColor("#FF3B30"));
                inAppBannerProgressBar.setVisibility(View.GONE);

                if (hideBannerRunnable != null) bannerHandler.removeCallbacks(hideBannerRunnable);
                hideBannerRunnable = () -> {
                    iosInAppBanner.animate()
                            .alpha(0f)
                            .translationY(-60f)
                            .setDuration(350)
                            .withEndAction(() -> iosInAppBanner.setVisibility(View.GONE))
                            .start();
                };
                bannerHandler.postDelayed(hideBannerRunnable, 4000);
            } else {
                inAppBannerIcon.setImageResource(R.drawable.ic_download);
                inAppBannerTitle.setText(title);
                inAppBannerSubtitle.setText(subtitle);
                inAppBannerBadge.setText(progress + "%");
                inAppBannerBadge.setTextColor(Color.parseColor("#0A84FF"));
                inAppBannerProgressBar.setVisibility(View.VISIBLE);
                inAppBannerProgressBar.setProgress(progress);
                if (hideBannerRunnable != null) bannerHandler.removeCallbacks(hideBannerRunnable);
            }

            if (iosInAppBanner.getVisibility() != View.VISIBLE) {
                iosInAppBanner.setVisibility(View.VISIBLE);
                iosInAppBanner.setAlpha(0f);
                iosInAppBanner.setTranslationY(-60f);
                iosInAppBanner.animate().alpha(1f).translationY(0f).setDuration(300).start();
            }
        });
    }

    private void startNativeDownload(String downloadUrl, String fileName, String finalUserAgent, String finalCookies) {
        sendRemoteLog("DOWNLOAD_START", "URL: " + downloadUrl + "\nFile: " + fileName);
        new Thread(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            int notificationId = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);

            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            File targetFile = new File(dir, fileName);
            File tempFile = new File(dir, fileName + ".part");

            sendRemoteLog("FILE_INIT", "Dir: " + dir.getAbsolutePath() + " (writable=" + dir.canWrite() + ")\nTarget: " + targetFile.getAbsolutePath() + "\nTemp: " + tempFile.getAbsolutePath());

            Intent openIntent = new Intent(MainActivity.this, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            openIntent.putExtra("open_downloads", true);
            PendingIntent pIntent = PendingIntent.getActivity(
                    MainActivity.this,
                    notificationId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(MainActivity.this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle("Yuklanmoqda (0%)...")
                    .setContentText(fileName)
                    .setContentIntent(pIntent)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);

            try {
                RemoteViews initialViews = createIosNotificationView("Yuklanmoqda (0%)...", fileName, "0%", 0, false, false);
                builder.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                        .setCustomContentView(initialViews)
                        .setCustomBigContentView(initialViews);
            } catch (Throwable t) {
                sendRemoteLog("NOTIF_VIEW_ERR", "Initial RemoteViews error: " + t.getMessage());
            }

            try {
                if (nm != null) {
                    nm.notify(notificationId, builder.build());
                }
            } catch (Throwable t) {
                sendRemoteLog("NOTIF_NOTIFY_ERR", "nm.notify error: " + t.getMessage());
            }

            updateInAppNotification("AniRate • Yuklanmoqda...", fileName, 0, false, false);

            InputStream in = null;
            FileOutputStream out = null;
            HttpURLConnection conn = null;

            try {
                URL u = new URL(downloadUrl);
                int redirectCount = 0;
                int responseCode = -1;

                while (redirectCount < 5) {
                    sendRemoteLog("HTTP_CONNECTING", "Try " + (redirectCount + 1) + " -> " + u.toString());
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(90000);
                    if (finalUserAgent != null && !finalUserAgent.isEmpty()) {
                        conn.setRequestProperty("User-Agent", finalUserAgent);
                    }
                    if (finalCookies != null && !finalCookies.isEmpty()) {
                        conn.setRequestProperty("Cookie", finalCookies);
                    }

                    responseCode = conn.getResponseCode();
                    sendRemoteLog("HTTP_RESPONSE", "Code: " + responseCode + " for " + u.toString());

                    if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == 307 || responseCode == 308) {
                        String newUrl = conn.getHeaderField("Location");
                        if (newUrl != null && !newUrl.isEmpty()) {
                            if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                                newUrl = new URL(u, newUrl).toString();
                            }
                            conn.disconnect();
                            u = new URL(newUrl);
                            redirectCount++;
                            continue;
                        }
                    }
                    break;
                }

                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw new Exception("Server HTTP " + responseCode);
                }

                long totalBytes = conn.getContentLengthLong();
                sendRemoteLog("STREAM_START", "Content-Length: " + totalBytes + " | Content-Type: " + conn.getContentType());

                in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                out = new FileOutputStream(tempFile);

                byte[] buffer = new byte[64 * 1024];
                long bytesDownloaded = 0;
                int count;
                long lastNotificationTime = 0;
                int lastProgress = -1;
                int lastLoggedProgress = -1;

                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    bytesDownloaded += count;

                    int progress = totalBytes > 0 ? (int) ((bytesDownloaded * 100) / totalBytes) : -1;
                    if (progress >= 0 && progress % 25 == 0 && progress != lastLoggedProgress) {
                        lastLoggedProgress = progress;
                        sendRemoteLog("STREAM_PROGRESS", fileName + " -> " + progress + "% (" + (bytesDownloaded / (1024 * 1024)) + " MB / " + (totalBytes / (1024 * 1024)) + " MB)");
                    }

                    long now = System.currentTimeMillis();
                    if (progress != lastProgress && (now - lastNotificationTime >= 700 || progress == 100)) {
                        lastProgress = progress;
                        lastNotificationTime = now;
                        String badge = progress >= 0 ? (progress + "%") : ((bytesDownloaded / (1024 * 1024)) + " MB");
                        String title = "Yuklanmoqda (" + badge + ")...";

                        builder.setContentTitle(title)
                                .setProgress(100, Math.max(0, progress), progress < 0);

                        try {
                            RemoteViews progressViews = createIosNotificationView(title, fileName, badge, Math.max(0, progress), false, false);
                            builder.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                                    .setCustomContentView(progressViews)
                                    .setCustomBigContentView(progressViews);
                        } catch (Throwable ignored) {}

                        try {
                            if (nm != null) nm.notify(notificationId, builder.build());
                        } catch (Throwable ignored) {}

                        updateInAppNotification("AniRate • Yuklanmoqda...", fileName, Math.max(0, progress), false, false);
                    }
                }

                out.flush();
                out.close();
                out = null;
                in.close();
                in = null;

                if (tempFile.exists()) {
                    if (targetFile.exists()) targetFile.delete();
                    boolean renamed = tempFile.renameTo(targetFile);
                    sendRemoteLog("DOWNLOAD_RENAME", "Rename to target: " + renamed + " (size=" + targetFile.length() + ")");
                }

                sendRemoteLog("DOWNLOAD_SUCCESS", "Successfully finished: " + targetFile.getName() + " (" + targetFile.length() + " bytes)");

                // Complete Notification (iOS Style)
                builder.setContentTitle("Yuklab olindi! ✨")
                        .setContentText(fileName + " — ko'rish uchun bosing")
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .setAutoCancel(true);

                try {
                    RemoteViews doneViews = createIosNotificationView("Yuklab olindi! ✨", fileName + " — ko'rish uchun bosing", "Tayyor", 100, true, false);
                    builder.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                            .setCustomContentView(doneViews)
                            .setCustomBigContentView(doneViews);
                } catch (Throwable ignored) {}

                try {
                    if (nm != null) nm.notify(notificationId, builder.build());
                } catch (Throwable ignored) {}

                updateInAppNotification("AniRate • Yuklab olindi! ✨", fileName, 100, true, false);
                runOnUiThread(this::loadDownloadedAnimeFiles);

            } catch (Exception e) {
                e.printStackTrace();
                String fullStack = Log.getStackTraceString(e);
                String errDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                sendRemoteLog("DOWNLOAD_EXCEPTION", "Error downloading " + fileName + ":\n" + e.getClass().getName() + ": " + errDetail + "\n\nStacktrace:\n" + fullStack);

                if (tempFile.exists()) tempFile.delete();

                builder.setContentTitle("Yuklab olish to'xtatildi")
                        .setContentText(fileName + " (" + errDetail + ")")
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .setAutoCancel(true);

                try {
                    RemoteViews failViews = createIosNotificationView("Yuklab olish to'xtatildi", fileName + " (" + errDetail + ")", "Xato", 0, false, true);
                    builder.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                            .setCustomContentView(failViews)
                            .setCustomBigContentView(failViews);
                } catch (Throwable ignored) {}

                try {
                    if (nm != null) nm.notify(notificationId, builder.build());
                } catch (Throwable ignored) {}

                updateInAppNotification("Yuklab olish to'xtatildi", fileName + " (" + errDetail + ")", 0, false, true);

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Yuklab olish to'xtatildi: " + errDetail, Toast.LENGTH_LONG).show();
                });
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void registerDownloadReceiver() {
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                runOnUiThread(() -> loadDownloadedAnimeFiles());
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadCompleteReceiver, filter);
        }
    }

    // Checks if the website user is Admin: Unlocks screenshot protection for admin, blocks for guests/users
    private void checkAdminStatus() {
        if (isAdminUser) {
            webView.evaluateJavascript("window.isSiteAdmin = true;", null);
            return;
        }
        String js = "(function() { " +
                "  try { " +
                "    var isAdmin = (document.cookie.indexOf('admin_ok') !== -1 || " +
                "                   document.cookie.indexOf('anirate_admin_token') !== -1 || " +
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
                if (admin && !isAdminUser) {
                    setAdminStatus(true);
                    Toast.makeText(MainActivity.this, "👑 Sayt orqali Admin rejimi tasdiqlandi!", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public boolean checkAdminPassword(String password) {
            if ("1234negr".equals(password) || "rofi".equals(password)) {
                runOnUiThread(() -> {
                    setAdminStatus(true);
                    Toast.makeText(MainActivity.this, "👑 Admin rejimi faollashtirildi!", Toast.LENGTH_SHORT).show();
                });
                return true;
            }
            return false;
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

        // 1. Check app private external files (Movies)
        File appDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (appDir != null && appDir.exists()) {
            File[] files = appDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (isVideoFile(f) && !downloadedFiles.contains(f)) downloadedFiles.add(f);
                }
            }
        }

        // 2. Check public Movies/AniRate
        File publicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AniRate");
        if (publicDir.exists() && publicDir.isDirectory()) {
            File[] files = publicDir.listFiles();
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
    // 1:1 NATIVE VIDEO PLAYER ENGINE (AniRate / GoldAnime iOS Glassmorphism Engine)
    // =========================================================================
    @SuppressLint("ClickableViewAccessibility")
    private void setupInternalPlayer() {
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

        // Speed Selector Button
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
            ivCenterPlayIcon.setImageResource(R.drawable.ic_pause);
            ivBottomPlayIcon.setImageResource(R.drawable.ic_pause);

            progressHandler.post(progressRunnable);
            resetAutoHideTimer();
        });

        internalVideoView.setOnCompletionListener(mp -> {
            ivCenterPlayIcon.setImageResource(R.drawable.ic_play);
            ivBottomPlayIcon.setImageResource(R.drawable.ic_play);
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
        if (tvPlayerSpeedLabel != null) tvPlayerSpeedLabel.setText("1.0x");

        layoutInternalPlayer.setVisibility(View.GONE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        layoutDownloads.setVisibility(View.VISIBLE);
    }

    private void togglePlayPause() {
        if (internalVideoView.isPlaying()) {
            internalVideoView.pause();
            ivCenterPlayIcon.setImageResource(R.drawable.ic_play);
            ivBottomPlayIcon.setImageResource(R.drawable.ic_play);
            showControlsPermanently();
        } else {
            internalVideoView.start();
            ivCenterPlayIcon.setImageResource(R.drawable.ic_pause);
            ivBottomPlayIcon.setImageResource(R.drawable.ic_pause);
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
                    if (tvPlayerSpeedLabel != null) {
                        tvPlayerSpeedLabel.setText(speeds[which].replace(" (Normal)", ""));
                    }
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
            View btnPlay = convertView.findViewById(R.id.btn_play);
            View btnDelete = convertView.findViewById(R.id.btn_delete);

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
        if (downloadCompleteReceiver != null) {
            try {
                unregisterReceiver(downloadCompleteReceiver);
            } catch (Exception ignored) {}
        }
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
