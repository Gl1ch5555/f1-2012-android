package com.f12012.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.content.Intent;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_GAME_FOLDER = 41;
    private static final int REQUEST_STORAGE_ACCESS = 42;
    private static final int REQUEST_LEGACY_STORAGE = 43;
    private static final int REQUEST_SAVE_REPORT = 44;
    private static final int REQUEST_DRIVER_ZIP = 45;
    private static final int REQUEST_DRIVER_PROBE = 46;
    private static final int REQUEST_ARCHIVE_FILE = 47;
    private static final int REQUEST_NOTIF_PERM = 48;
    private boolean playAfterProbe;
    private String pendingDownloadUrl;
    private boolean launcherResumed;
    private boolean checkingUpdates;
    private ReleaseUpdates.Result pendingUpdate;
    private boolean pendingUpdateManual;

    private static final String TREE_URI = "tree_uri";
    static final String GAME_FOLDER_NAME = "f1-2012-android";

    private TextView importStatus;
    private Button selectFolder;
    private Button launchGame;
    private Button sendReport;
    private File pendingReport;
    private boolean pendingGithub;
    private LauncherSaves launcherSaves;
    private final ExecutorService importer = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            playAfterProbe = state.getBoolean("play_after_probe");
            String name = state.getString("pending_report");
            if (name != null && name.equals(new File(name).getName())) {
                pendingReport = new File(new File(getCacheDir(), "reports"), name);
                pendingGithub = state.getBoolean("pending_github");
            }
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setContentView(buildScreen());
        launcherSaves = new LauncherSaves(this, importer, busy -> {
            launchGame.setEnabled(!busy);
            selectFolder.setEnabled(!busy);
        });
        launcherSaves.recover();
        GameDownloader.cleanupTempFiles(sharedGameRoot());
        prepareSharedGameFolder();
        checkUpdates(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        launcherResumed = true;
        if (optionsList != null) {
            refreshOptions();
        }
        if (checksList != null && hasStorageAccess()) {
            showChecks(sharedGameRoot());
        }
        attachDownloadService();
        if (pendingUpdate != null) {
            ReleaseUpdates.Result result = pendingUpdate;
            pendingUpdate = null;
            showUpdate(result, pendingUpdateManual);
        }
    }

    @Override protected void onPause() {
        launcherResumed = false;
        DownloadService.setListener(null);
        super.onPause();
    }

    private void checkUpdates(boolean manual) {
        if (checkingUpdates) return;
        checkingUpdates = true;
        if (optionsList != null) refreshOptions();
        ReleaseUpdates.check(this, manual, result -> {
            if (isFinishing() || isDestroyed()) return;
            checkingUpdates = false;
            refreshOptions();
            if (!launcherResumed) {
                pendingUpdate = result; pendingUpdateManual = manual;
            } else showUpdate(result, manual);
        });
    }

    private void showUpdate(ReleaseUpdates.Result result, boolean manual) {
        if (result.error != null) {
            if (manual) reportError(result.error);
            return;  // Offline/rate-limited checks never affect playing.
        }
        if (!ReleaseVersion.newer(result.tag, ReleaseUpdates.installed(this))) {
            if (manual) new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Обновления").setMessage("Установлена версия " + ReleaseUpdates.installed(this)
                            + ". Более новой версии на GitHub нет.")
                    .setPositiveButton("ОК", null).show();
            return;
        }
        android.content.SharedPreferences updates = ReleaseUpdates.prefs(this);
        if (!manual && result.tag.equals(updates.getString("notified_tag", ""))) return;
        updates.edit().putString("notified_tag", result.tag).apply();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Доступна новая версия: " + result.tag)
                .setMessage("Можно обновиться на GitHub или продолжить играть. Установите новый APK поверх текущего, чтобы сохранить настройки.")
                .setPositiveButton("Обновить", (dialog, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.url))); }
                    catch (ActivityNotFoundException error) { reportError("Не найдено приложение для открытия GitHub."); }
                })
                .setNegativeButton("Позже", null).show();
    }

    // ---- Screen --------------------------------------------------------------------------------------------

    private static final int ACCENT = 0xFFE10600;
    private LinearLayout checksList;
    private LinearLayout optionsList;
    private LinearLayout systemList;
    private LinearLayout shaderPanel;
    private ProgressBar shaderProgress;
    private TextView shaderText;
    private Button cancelDownload;
    private ShaderBuilder shaderBuilder;
    private Button downloadGame;
    private Button settingsButton;
    private AlertDialog settingsDialog;

    private View tabHome;
    private View tabGraphics;
    private View tabSystem;
    private Button[] navTabs;
    private TextView driverChip;
    private int activeTab = 0;

    private View buildScreen() {
        FrameLayout screen = new FrameLayout(this);
        screen.addView(new F1BackdropView(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View topStripe = new View(this);
        topStripe.setBackgroundColor(ACCENT);
        screen.addView(topStripe, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(14), dp(22), dp(14));
        screen.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ---- Top Paddock Bar ----
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topBarLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        topBarLp.bottomMargin = dp(12);
        root.addView(topBar, topBarLp);

        LinearLayout brandBox = new LinearLayout(this);
        brandBox.setOrientation(LinearLayout.HORIZONTAL);
        brandBox.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = label("F1 2012", 13, Color.WHITE, true);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(ACCENT);
        badgeBg.setCornerRadius(dp(6));
        badge.setBackground(badgeBg);
        brandBox.addView(badge);

        TextView verLabel = label("  XBOX 360 RECOMP  •  v" + ReleaseUpdates.installed(this), 11, 0xBBFFFFFF, true);
        brandBox.addView(verLabel);
        topBar.addView(brandBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable stripBg = new GradientDrawable();
        stripBg.setColor(0x990D111A);
        stripBg.setCornerRadius(dp(12));
        stripBg.setStroke(dp(1), 0x28FFFFFF);
        tabStrip.setBackground(stripBg);

        String[] tabNames = {"ГЛАВНАЯ", "ГРАФИКА", "СИСТЕМА"};
        navTabs = new Button[tabNames.length];
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            Button tab = new Button(this);
            tab.setText(tabNames[i]);
            tab.setAllCaps(false);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tab.setTypeface(Typeface.DEFAULT_BOLD);
            tab.setLetterSpacing(.08f);
            tab.setPadding(dp(16), dp(6), dp(16), dp(6));
            tab.setMinHeight(0);
            tab.setMinimumHeight(0);
            tab.setStateListAnimator(null);
            tab.setOnClickListener(v -> switchTab(idx));
            navTabs[i] = tab;
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            if (i > 0) tp.leftMargin = dp(4);
            tabStrip.addView(tab, tp);
        }
        topBar.addView(tabStrip);

        // ---- Tab Host ----
        FrameLayout tabHost = new FrameLayout(this);
        root.addView(tabHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 1) HOME TAB
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setGravity(Gravity.CENTER_VERTICAL);
        tabHome = columns;
        tabHost.addView(tabHome, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        columns.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.95f));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.banner);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        left.addView(logo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(138)));

        TextView subtitle = label("FORMULA 1 • 2012 SEASON • EGO 2.0 • ARM64", 11, 0xAAFFFFFF, true);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(12));
        left.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout chipsCard = card();
        chipsCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        driverChip = label("Драйвер Vulkan: Системный", 12, 0xEEFFFFFF, true);
        chipsCard.addView(driverChip);
        TextView deviceChip = label(Build.MANUFACTURER.toUpperCase() + " " + Build.MODEL
                + "  •  Android " + Build.VERSION.RELEASE, 11, 0x99FFFFFF, false);
        deviceChip.setPadding(0, dp(4), 0, 0);
        chipsCard.addView(deviceChip);
        left.addView(chipsCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        settingsButton = actionButton("НАСТРОЙКИ ГРАФИКИ И ДРАЙВЕРА", false);
        settingsButton.setOnClickListener(view -> switchTab(1));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        sbLp.topMargin = dp(10);
        left.addView(settingsButton, sbLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1.05f);
        rightParams.leftMargin = dp(22);
        columns.addView(right, rightParams);

        LinearLayout card = card();
        right.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(label("ДАННЫЕ ИГРЫ • TITLE ID 434D0847", 11, 0xFFFF5247, true));
        importStatus = label("", 13, 0xEEFFFFFF, false);
        importStatus.setPadding(0, dp(6), 0, dp(6));
        card.addView(importStatus);
        checksList = new LinearLayout(this);
        checksList.setOrientation(LinearLayout.VERTICAL);
        card.addView(checksList);

        shaderPanel = new LinearLayout(this);
        shaderPanel.setOrientation(LinearLayout.VERTICAL);
        shaderPanel.setPadding(0, dp(8), 0, 0);
        shaderPanel.setVisibility(View.GONE);
        shaderText = label("", 13, 0xFFFFFFFF, false);
        shaderPanel.addView(shaderText);
        shaderProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        shaderProgress.setMax(1000);
        shaderProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        shaderPanel.addView(shaderProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
        cancelDownload = actionButton("Отменить загрузку", false);
        cancelDownload.setVisibility(View.GONE);
        cancelDownload.setOnClickListener(view -> DownloadService.cancel(this));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        cancelParams.topMargin = dp(8);
        shaderPanel.addView(cancelDownload, cancelParams);
        card.addView(shaderPanel);

        launchGame = actionButton("ИГРАТЬ", true);
        launchGame.setVisibility(View.GONE);
        launchGame.setOnClickListener(view -> play());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        playParams.topMargin = dp(12);
        right.addView(launchGame, playParams);

        downloadGame = actionButton(
                GameDownloader.hasResumableDownload(sharedGameRoot())
                        ? "ПРОДОЛЖИТЬ ЗАГРУЗКУ (GOOGLE DRIVE)"
                        : "СКАЧАТЬ ИГРУ (GOOGLE DRIVE)",
                true);
        downloadGame.setOnClickListener(view -> promptOrStartDownload());
        LinearLayout.LayoutParams dlParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        dlParams.topMargin = dp(12);
        right.addView(downloadGame, dlParams);

        LinearLayout fileButtonsRow = new LinearLayout(this);
        fileButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fbrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fbrLp.topMargin = dp(10);
        right.addView(fileButtonsRow, fbrLp);

        selectFolder = actionButton("Папка с игрой", false);
        selectFolder.setOnClickListener(view -> selectGameFolder());
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        fileButtonsRow.addView(selectFolder, folderParams);

        Button selectArchive = actionButton("Архив / ISO", false);
        selectArchive.setOnClickListener(view -> selectLocalArchive());
        LinearLayout.LayoutParams archiveParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        archiveParams.leftMargin = dp(10);
        fileButtonsRow.addView(selectArchive, archiveParams);

        // 2) GRAPHICS TAB
        ScrollView graphicsScroll = new ScrollView(this);
        optionsList = new LinearLayout(this);
        optionsList.setOrientation(LinearLayout.VERTICAL);
        optionsList.setPadding(0, dp(2), 0, dp(12));
        graphicsScroll.addView(optionsList);
        tabGraphics = graphicsScroll;
        tabHost.addView(tabGraphics, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 3) SYSTEM TAB
        ScrollView systemScroll = new ScrollView(this);
        systemList = new LinearLayout(this);
        systemList.setOrientation(LinearLayout.VERTICAL);
        systemList.setPadding(0, dp(2), 0, dp(12));
        systemScroll.addView(systemList);
        tabSystem = systemScroll;
        tabHost.addView(tabSystem, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        sendReport = actionButton("Логи и диагностика", false);
        sendReport.setOnClickListener(view -> chooseReportDestination());
        refreshOptions();
        switchTab(0);
        return screen;
    }

    private void switchTab(int index) {
        activeTab = index;
        if (tabHome != null) tabHome.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (tabGraphics != null) tabGraphics.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (tabSystem != null) tabSystem.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (navTabs != null) {
            for (int i = 0; i < navTabs.length; i++) {
                boolean active = (i == index);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(active ? ACCENT : 0x00000000);
                bg.setCornerRadius(dp(9));
                navTabs[i].setBackground(bg);
                navTabs[i].setTextColor(active ? Color.WHITE : 0x99FFFFFF);
            }
        }
    }

    private void openSettingsDialog() {
        switchTab(1);
    }

    private void promptOrStartDownload() {
        if (!hasStorageAccess()) {
            prepareSharedGameFolder();
            return;
        }
        if (!GameDownloader.DEFAULT_GAME_URL.isEmpty()) {
            startDownload(GameDownloader.DEFAULT_GAME_URL);
            return;
        }
        String saved = getPreferences(MODE_PRIVATE).getString("last_download_url", "");
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("https://drive.google.com/file/d/... или прямая ссылка (.zip)");
        input.setText(saved);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x88FFFFFF);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Скачать файлы F1 2012")
                .setMessage("Вставь ссылку на Google Диск или прямой ZIP-архив с игрой (должен содержать default.xex):")
                .setView(input)
                .setPositiveButton("Скачать", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        getPreferences(MODE_PRIVATE).edit().putString("last_download_url", url).apply();
                        startDownload(url);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void selectLocalArchive() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(picker, REQUEST_ARCHIVE_FILE);
    }

    private void startLocalArchiveInstall(Uri uri) {
        downloadGame.setEnabled(false);
        selectFolder.setEnabled(false);
        launchGame.setVisibility(View.GONE);
        shaderPanel.setVisibility(View.VISIBLE);
        shaderProgress.setProgress(0);
        shaderText.setText("Подготовка к распаковке...");
        importer.execute(() -> GameDownloader.installFromUri(this, uri, sharedGameRoot(), new GameDownloader.Listener() {
            @Override
            public void onProgress(String statusText, int percent) {
                mainHandler.post(() -> {
                    shaderText.setText(statusText);
                    shaderProgress.setProgress(Math.max(0, Math.min(1000, percent * 10)));
                });
            }

            @Override
            public void onSuccess() {
                mainHandler.post(() -> {
                    shaderPanel.setVisibility(View.GONE);
                    downloadGame.setEnabled(true);
                    showSharedGameFolder("Игра успешно распакована!");
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    shaderPanel.setVisibility(View.GONE);
                    downloadGame.setEnabled(true);
                    selectFolder.setEnabled(true);
                    reportError("Ошибка распаковки: " + message);
                });
            }
        }));
    }

    private void startDownload(String url) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIF_PERM);
            return;
        }
        launchDownloadService(url);
    }

    private void launchDownloadService(String url) {
        downloadGame.setEnabled(false);
        selectFolder.setEnabled(false);
        launchGame.setVisibility(View.GONE);
        shaderPanel.setVisibility(View.VISIBLE);
        cancelDownload.setVisibility(View.VISIBLE);
        shaderProgress.setProgress(0);
        shaderText.setText("Подготовка к скачиванию...");
        attachDownloadService();
        DownloadService.start(this, url, sharedGameRoot());
    }

    private void attachDownloadService() {
        if (downloadGame == null) return;
        if (DownloadService.isRunning()) {
            downloadGame.setEnabled(false);
            selectFolder.setEnabled(false);
            launchGame.setVisibility(View.GONE);
            shaderPanel.setVisibility(View.VISIBLE);
            cancelDownload.setVisibility(View.VISIBLE);
            shaderText.setText(DownloadService.getLastStatus());
            shaderProgress.setProgress(Math.max(0, Math.min(1000, DownloadService.getLastPercent() * 10)));
        }
        DownloadService.setListener(new DownloadService.Listener() {
            @Override
            public void onProgress(String statusText, int percent) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    downloadGame.setEnabled(false);
                    selectFolder.setEnabled(false);
                    launchGame.setVisibility(View.GONE);
                    shaderPanel.setVisibility(View.VISIBLE);
                    cancelDownload.setVisibility(View.VISIBLE);
                    shaderText.setText(statusText);
                    shaderProgress.setProgress(Math.max(0, Math.min(1000, percent * 10)));
                });
            }

            @Override
            public void onSuccess() {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    shaderPanel.setVisibility(View.GONE);
                    cancelDownload.setVisibility(View.GONE);
                    downloadGame.setEnabled(true);
                    downloadGame.setText("СКАЧАТЬ ИГРУ (GOOGLE DRIVE)");
                    showSharedGameFolder("Файлы игры успешно скачаны!");
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    shaderPanel.setVisibility(View.GONE);
                    cancelDownload.setVisibility(View.GONE);
                    downloadGame.setEnabled(true);
                    downloadGame.setText(GameDownloader.hasResumableDownload(sharedGameRoot())
                            ? "ПРОДОЛЖИТЬ ЗАГРУЗКУ (GOOGLE DRIVE)"
                            : "СКАЧАТЬ ИГРУ (GOOGLE DRIVE)");
                    selectFolder.setEnabled(true);
                    reportError("Ошибка загрузки: " + message);
                });
            }

            @Override
            public void onCancelled() {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    shaderPanel.setVisibility(View.GONE);
                    cancelDownload.setVisibility(View.GONE);
                    downloadGame.setEnabled(true);
                    downloadGame.setText("СКАЧАТЬ ИГРУ (GOOGLE DRIVE)");
                    selectFolder.setEnabled(true);
                    setImportStatus("Загрузка отменена.");
                });
            }
        });
    }

    // ---- Shader library ------------------------------------------------------------------------------------

    private void play() {
        if (launcherSaves != null && launcherSaves.blocked()) return;
        if (GpuDrivers.selected(this) != null) {
            probeDriver(true);
            return;
        }
        playChecked();
    }

    private void playChecked() {
        launchGame.setEnabled(false);
        Diagnostics.recordLaunch(this);
        importer.execute(() -> {
            GameEdition edition = GameEdition.of(this, sharedGameRoot());
            String problem = "nativo".equals(GameOptions.get(this, GameOptions.RENDERER))
                    && !GameOptions.usesGles(this) ? Diagnostics.incompatibility(this) : null;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                launchGame.setEnabled(true);
                if (edition == null || !edition.supported() || !GameActivity.canLoad(edition)) {
                    String reason = edition == null ? "Не удалось прочитать default.xex. Проверьте файлы игры."
                            : !edition.supported() ? "Ваш файл относится к изданию " + edition.describe()
                                    + ". Этот APK включает: " + GameEdition.compiledNames()
                                    + ". Для каждого издания нужна своя сборка."
                            : "Чтобы сменить издание, полностью закройте приложение и откройте его снова.";
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Невозможно запустить это издание")
                            .setMessage(reason)
                            .setPositiveButton("Назад", null).show();
                    return;
                }
                continuePlay(problem);
            });
        });
    }

    private void continuePlay(String problem) {
        if (problem == null) {
            playCompatibleGame();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Попробовать другой рендерер")
                .setMessage(problem + "\n\nМожно использовать нативный рендерер на OpenGL ES (экспериментально, для GPU "
                        + "без совместимого Vulkan) или режим совместимости на Vulkan. Возможны графические ошибки "
                        + "или снижение скорости.")
                .setPositiveButton("Использовать OpenGL ES", (dialog, which) -> {
                    GameOptions.set(this, GameOptions.RENDERER, "nativo");
                    GameOptions.set(this, GameOptions.API, "gles");
                    refreshOptions();
                    playCompatibleGame();
                })
                .setNeutralButton("Совместимость Vulkan", (dialog, which) -> {
                    GameOptions.set(this, GameOptions.RENDERER, "xenos");
                    refreshOptions();
                    playCompatibleGame();
                })
                .setNegativeButton("Назад", null).show();
    }

    private void playCompatibleGame() {
        if (launcherSaves != null && launcherSaves.blocked()) return;
        startGame();
    }

    /** Starts the game; with LAN play, first the host server and the server's IP (a name is resolved here). */
    private void startGame() {
        if (LanSettings.MODE_OFF.equals(LanSettings.mode(this))) {
            startActivity(new Intent(this, GameActivity.class));
            return;
        }
        boolean host = LanSettings.MODE_HOST.equals(LanSettings.mode(this));
        if (host) {
            LanServerService.start(this);
        }
        launchGame.setEnabled(false);
        importer.execute(() -> {
            String server = LanSettings.effectiveServer(this);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                launchGame.setEnabled(true);
                if (server == null) {
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Локальная сеть (LAN)")
                            .setMessage(host
                                    ? "Телефон не подключён к Wi-Fi и не раздаёт точку доступа: никто не сможет подключиться."
                                    : "Сервер " + LanSettings.server(this) + " не найден.")
                            .setPositiveButton("Играть без LAN", (d, w) ->
                                    startActivity(new Intent(this, GameActivity.class)))
                            .setNegativeButton("Назад", null).show();
                    return;
                }
                startActivity(new Intent(this, GameActivity.class).putExtra(GameActivity.EXTRA_LAN_SERVER, server));
            });
        });
    }

    // ---- Tester reports ------------------------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (pendingReport != null) {
            state.putString("pending_report", pendingReport.getName());
            state.putBoolean("pending_github", pendingGithub);
        }
        state.putBoolean("play_after_probe", playAfterProbe);
        super.onSaveInstanceState(state);
    }

    private void chooseReportDestination() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Логи и диагностика")
                .setMessage("Отчёт включает модель телефона, Android, GPU, настройки и логи падения.")
                .setPositiveButton("Сохранить ZIP", (dialog, which) -> prepareReport(2))
                .setNeutralButton("GitHub", (dialog, which) -> prepareReport(1))
                .setNegativeButton("Отмена", null).show();
    }

    private void reportError(String message) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Диагностика").setMessage(message).setPositiveButton("ОК", null).show();
    }

    private void prepareReport(int destination) {
        sendReport.setEnabled(false);
        sendReport.setText("Сбор логов…");
        importer.execute(() -> {
            try {
                File report = Diagnostics.createReport(this);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    sendReport.setEnabled(true);
                    sendReport.setText("Логи и диагностика");
                    if (destination == 0) shareReportByEmail(report);
                    else saveReport(report, destination == 1);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    sendReport.setEnabled(true);
                    sendReport.setText("Логи и диагностика");
                    reportError("Не удалось собрать отчёт: " + error.getMessage());
                });
            }
        });
    }

    private void shareReportByEmail(File report) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".reports", report);
        Intent email = new Intent(Intent.ACTION_SEND);
        email.setType("application/zip");
        email.putExtra(Intent.EXTRA_EMAIL, new String[]{Diagnostics.EMAIL});
        email.putExtra(Intent.EXTRA_SUBJECT, "F1 2012 Android: " + Build.MODEL + " — диагностика");
        email.putExtra(Intent.EXTRA_TEXT, Diagnostics.summary(this) + "\nЧто произошло:\n\nШаги для воспроизведения:\n\nИздание игры:\n\nПрикрепляю отчёт диагностики.");
        email.putExtra(Intent.EXTRA_STREAM, uri);
        email.setClipData(ClipData.newUri(getContentResolver(), "Диагностика F1 2012", uri));
        email.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(email, "Отправить на " + Diagnostics.EMAIL)); }
        catch (ActivityNotFoundException error) { saveReport(report, false); }
    }

    private void saveReport(File report, boolean github) {
        pendingReport = report;
        pendingGithub = github;
        if (github) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Сообщить на GitHub")
                    .setMessage("Сначала сохраните ZIP. Затем откроется issue: опишите проблему и прикрепите сохранённый ZIP.")
                    .setPositiveButton("Сохранить и открыть GitHub", (dialog, which) -> chooseReportFile())
                    .setNegativeButton("Отмена", (dialog, which) -> pendingReport = null).show();
        } else chooseReportFile();
    }

    private void chooseReportFile() {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("application/zip");
        save.putExtra(Intent.EXTRA_TITLE, pendingReport.getName());
        try { startActivityForResult(save, REQUEST_SAVE_REPORT); }
        catch (ActivityNotFoundException error) {
            pendingReport = null;
            reportError("Нет приложения для сохранения файлов. Попробуйте отправить по почте.");
        }
    }

    private void finishReportSave(Uri destination) {
        File report = pendingReport;
        boolean github = pendingGithub;
        pendingReport = null;
        if (report == null || !report.isFile()) {
            reportError("Отчёт больше недоступен. Соберите новый.");
            return;
        }
        importer.execute(() -> {
            try (InputStream input = new FileInputStream(report);
                 java.io.OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Не удалось открыть файл назначения.");
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) reportError("Не удалось сохранить ZIP: " + error.getMessage());
                });
                return;
            }
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (github) {
                    String body = "### Что произошло\n\n### Шаги для воспроизведения\n\n### Издание игры\n\n### Устройство\n"
                            + Diagnostics.summary(this) + "\n### Диагностика\nПрикрепите сюда файл **" + report.getName() + "**, который вы только что сохранили.\n";
                    Uri issue = Uri.parse(Diagnostics.ISSUES).buildUpon()
                            .appendQueryParameter("title", "[Android] " + Build.MODEL + ": ")
                            .appendQueryParameter("body", body).build();
                    try { startActivity(new Intent(Intent.ACTION_VIEW, issue)); }
                    catch (ActivityNotFoundException error) { reportError("ZIP сохранён. Откройте " + Diagnostics.ISSUES + " и прикрепите его к issue."); }
                } else reportError("ZIP сохранён. Его можно прикрепить к issue или отправить на " + Diagnostics.EMAIL + ".");
            });
        });
    }

    /**
     * Builds nfsmw_shaders.nfsp from the game files (a minute or two, only once), or with gles
     * nfsmw_shaders_gles.nfsp from it.
     */
    private void buildShaders(boolean thenPlay, boolean gles) {
        if (shaderBuilder != null) {
            return;
        }
        launchGame.setEnabled(false);
        launchGame.setAlpha(.5f);
        selectFolder.setEnabled(false);
        shaderPanel.setVisibility(View.VISIBLE);
        shaderProgress.setProgress(0);
        shaderText.setText(gles ? "Генерация шейдеров OpenGL ES (только при первом запуске)…"
                : "Генерация шейдеров нативного рендерера (только при первом запуске)…");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shaderBuilder = new ShaderBuilder(this, sharedGameRoot(), gles, new ShaderBuilder.Listener() {
            @Override
            public void onProgress(float fraction, String text) {
                shaderProgress.setProgress(Math.round(fraction * 1000));
                shaderText.setText(text);
            }

            @Override
            public void onDone(File library) {
                shadersFinished();
                shaderPanel.setVisibility(View.GONE);
                showChecks(sharedGameRoot());
                if (thenPlay) {
                    playCompatibleGame();  // the next library, if one is missing, or the game
                }
            }

            @Override
            public void onError(String message) {
                shadersFinished();
                shaderText.setText("Не удалось сгенерировать шейдеры: " + message);
                shaderProgress.setProgress(0);
            }
        });
        shaderBuilder.start();
    }

    private void shadersFinished() {
        shaderBuilder = null;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        launchGame.setEnabled(true);
        launchGame.setAlpha(1f);
        selectFolder.setEnabled(true);
    }

    private void refreshOptions() {
        GpuDrivers.Driver driver = GpuDrivers.selected(this);
        String driverName = driver == null ? "Системный" : driver.name;
        if (driverChip != null) {
            driverChip.setText("Драйвер Vulkan: " + driverName);
        }

        optionsList.removeAllViews();
        optionsList.addView(label("ГРАФИКА И ДРАЙВЕР VULKAN", 11, 0xFFFF5247, true));
        optionsList.addView(optionRow("Профиль графики", profileLabel(), this::chooseProfile));
        optionsList.addView(optionRow("Драйвер Vulkan", driverName, this::chooseDriver));
        optionsList.addView(optionRow("Тест драйвера", "Проверить GPU и функции Vulkan", () -> probeDriver(false)));
        SharedPreferences controls = getSharedPreferences("nfsmw_controls", MODE_PRIVATE);
        boolean stretch = controls.getBoolean("stretch", true);
        optionsList.addView(optionRow("Формат экрана",
                stretch ? "На весь экран" : "Оригинал 16:9", () -> {
                    controls.edit().putBoolean("stretch", !stretch).apply();
                    refreshOptions();
                }));
        for (GameOptions.Option option : GameOptions.ALL) {
            String value = GameOptions.get(this, option.key);
            optionsList.addView(optionRow(option.title, option.label(value), () -> chooseOption(option)));
        }

        if (systemList != null) {
            systemList.removeAllViews();
            systemList.addView(label("СОХРАНЕНИЯ И ДИАГНОСТИКА", 11, 0xFFFF5247, true));
            systemList.addView(optionRow("Сохранения", "Экспорт, импорт и резервная копия", () -> launcherSaves.menu()));
            systemList.addView(optionRow("Логи и диагностика", "Собрать ZIP с логами и настройками", this::chooseReportDestination));
            systemList.addView(optionRow("Обновления",
                    checkingUpdates ? "Проверка GitHub…" : "Версия " + ReleaseUpdates.installed(this),
                    () -> checkUpdates(true)));
            systemList.addView(optionRow("Сбросить настройки графики", "Вернуть рекомендованные значения", () -> {
                GameOptions.prefs(this).edit().clear().apply();
                refreshOptions();
                setImportStatus("Настройки графики сброшены на рекомендованные.");
            }));
        }
    }

    // ---- LAN multiplayer -----------------------------------------------------------------------------------

    private void chooseLanMode() {
        String[] modes = {LanSettings.MODE_OFF, LanSettings.MODE_JOIN, LanSettings.MODE_HOST};
        String[] labels = {"Выкл", "Подключиться к серверу (ПК или другой телефон)",
                "Хост: этот телефон работает как сервер"};
        int checked = java.util.Arrays.asList(modes).indexOf(LanSettings.mode(this));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Локальная сеть (LAN)")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    String mode = modes[which];
                    LanSettings.mode(this, mode);
                    if (LanSettings.MODE_HOST.equals(mode)) {
                        // The server's notification (its IP, the players and the stop button).
                        if (Build.VERSION.SDK_INT >= 33) {
                            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 47);
                        }
                        LanServerService.start(this);
                    } else {
                        LanServerService.stop(this);
                    }
                    refreshOptions();
                    if (LanSettings.MODE_JOIN.equals(mode) && LanSettings.server(this).isEmpty()) {
                        chooseLanServer();
                    } else if (!LanSettings.MODE_OFF.equals(mode)) {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Как играть по LAN")
                                .setMessage("Все устройства должны быть в одной Wi-Fi сети (или точке доступа хоста), у каждого "
                                        + "свой gamertag. В игре: Xbox Live → Быстрая игра. Один создаёт лобби, "
                                        + "остальные подключаются.")
                                .setPositiveButton("Понятно", null).show();
                    }
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void editGamertag() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(LanSettings.gamertag(this));
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(15)});
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Gamertag")
                .setMessage("До 15 латинских букв, цифр и пробелов, начиная с буквы. Сохранения не изменятся.")
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!LanSettings.validGamertag(value)) {
                        reportError("Неверный gamertag: до 15 латинских букв, цифр и пробелов, начиная с буквы.");
                        return;
                    }
                    LanSettings.gamertag(this, value);
                    refreshOptions();
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void chooseLanServer() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("192.168.1.20");
        input.setText(LanSettings.server(this));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("LAN-сервер")
                .setMessage("IP-адрес ПК или телефона-хоста.")
                .setView(input)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!LanSettings.validAddress(value)) {
                        reportError("Некорректный адрес.");
                        return;
                    }
                    LanSettings.server(this, value);
                    refreshOptions();
                })
                .setNeutralButton("Найти в сети", (dialog, which) -> searchLanServers())
                .setNegativeButton("Отмена", null).show();
    }

    private void searchLanServers() {
        AlertDialog searching = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Поиск серверов в локальной сети…").setCancelable(false).show();
        importer.execute(() -> {
            java.util.List<BuscadorServidores.Encontrado> found = BuscadorServidores.buscar();
            mainHandler.post(() -> {
                searching.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (found == null) {
                    reportError("Телефон не подключён к локальной сети.");
                    return;
                }
                if (found.isEmpty()) {
                    reportError("Серверы не найдены. Введите IP хоста вручную.");
                    return;
                }
                String[] items = new String[found.size()];
                for (int i = 0; i < items.length; i++) {
                    BuscadorServidores.Encontrado e = found.get(i);
                    items[i] = (e.nombre.isEmpty() ? e.ip : e.nombre + " · " + e.ip) + " · " + e.jugadores + " игр.";
                }
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Найденные серверы")
                        .setItems(items, (dialog, which) -> {
                            LanSettings.server(this, found.get(which).ip);
                            refreshOptions();
                        })
                        .setNegativeButton("Отмена", null).show();
            });
        });
    }

    private void chooseDriver() {
        java.util.List<GpuDrivers.Driver> drivers = GpuDrivers.list(this);
        String[] names = new String[drivers.size() + 1];
        names[0] = "Системный драйвер";
        int selected = 0;
        for (int i = 0; i < drivers.size(); ++i) {
            names[i + 1] = drivers.get(i).name;
            if (drivers.get(i).id.equals(GpuDrivers.selectedId(this))) selected = i + 1;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Драйвер Vulkan")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    GpuDrivers.select(this, which == 0 ? "" : drivers.get(which - 1).id);
                    refreshOptions(); dialog.dismiss();
                })
                .setPositiveButton("Импорт ZIP", (dialog, which) -> {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(picker, REQUEST_DRIVER_ZIP);
                })
                .setNeutralButton("Удалить выбранный", (dialog, which) -> {
                    GpuDrivers.Driver driver = GpuDrivers.selected(this);
                    if (driver == null) return;
                    try { GpuDrivers.remove(this, driver); refreshOptions(); }
                    catch (IOException error) { reportError(error.getMessage()); }
                })
                .setNegativeButton("Назад", null).show();
    }

    private void probeDriver(boolean playAfter) {
        playAfterProbe = playAfter;
        launchGame.setEnabled(false);
        startActivityForResult(new Intent(this, GpuProbeActivity.class), REQUEST_DRIVER_PROBE);
    }

    private void showDriverReport(String report) {
        try {
            Diagnostics.acceptGpu(this, report);
            org.json.JSONObject gpu = new org.json.JSONObject(report);
            boolean failed = gpu.optBoolean("driverLoadFailed") || gpu.has("probeError");
            String text = "GPU: " + gpu.optString("gpu", "недоступно")
                    + "\nVulkan: " + gpu.optString("vulkan", "недоступно")
                    + "\nНаборов дескрипторов: " + gpu.optInt("maxBoundDescriptorSets")
                    + "\n" + (gpu.optBoolean("compatible") ? "Функции нативного рендерера поддерживаются."
                            : "Недостающие функции: " + gpu.optJSONArray("missing"));
            if (failed) text = "Не удалось проверить драйвер. " + gpu.optString("probeError", "Ошибка загрузки Vulkan.");
            if (playAfterProbe && !failed) { playAfterProbe = false; playChecked(); return; }
            playAfterProbe = false;
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Тест драйвера Vulkan").setMessage(text)
                    .setPositiveButton("ОК", null)
                    .setNeutralButton("Системный", (dialog, which) -> {
                        GpuDrivers.select(this, ""); refreshOptions();
                    }).show();
        } catch (Exception error) { playAfterProbe = false; reportError("Некорректные данные диагностики: " + error.getMessage()); }
    }

    private java.util.Map<String,String> profileValues(String prefix) {
        java.util.Map<String,String> values = new java.util.LinkedHashMap<>();
        for (String key : GraphicsPresets.KEYS) values.put(key, prefix.isEmpty() ? GameOptions.get(this, key)
                : GameOptions.prefs(this).getString(prefix + key, ""));
        return values;
    }

    private String profileLabel() {
        SharedPreferences prefs = GameOptions.prefs(this);
        return profileValues("").equals(profileValues("preset_applied_")) ? prefs.getString("preset_name", "Свой") : "Свой";
    }

    private void chooseProfile() {
        boolean saved = GameOptions.prefs(this).getBoolean("preset_has_backup", false);
        String[] names = saved ? new String[]{"Батарея", "Сбалансированный", "Производительность", "Качество", "Мои сохранённые настройки"}
                : GraphicsPresets.NAMES;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Профиль графики")
                .setItems(names, (dialog, which) -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(names[which]).setMessage(which == 4 ? "Будут восстановлены ваши сохранённые настройки графики."
                                : "Будут изменены параметры рендера, VSync и пропуска кадров. Ваши текущие настройки сохранятся для отката.")
                        .setPositiveButton("Применить при следующем запуске", (confirm, button) -> applyProfile(which))
                        .setNegativeButton("Отмена", null).show())
                .setNegativeButton("Закрыть", null).show();
    }

    private void applyProfile(int profile) {
        SharedPreferences prefs = GameOptions.prefs(this);
        java.util.Map<String,String> next = profile == 4 ? profileValues("preset_backup_") : GraphicsPresets.values(profile);
        // Validate against the launcher's actual options before changing any preference.
        for (java.util.Map.Entry<String,String> entry : next.entrySet()) {
            if (!java.util.Arrays.asList(GameOptions.find(entry.getKey()).values).contains(entry.getValue())) {
                reportError("Сохранённые настройки не подходят к этой версии."); return;
            }
        }
        SharedPreferences.Editor edit = prefs.edit();
        if (profile != 4) {
            java.util.Map<String,String> previous = prefs.getBoolean("preset_has_backup", false) ? profileValues("preset_backup_") : null;
            java.util.Map<String,String> backup = GraphicsPresets.preserve(profileValues(""), profileValues("preset_applied_"), previous);
            for (java.util.Map.Entry<String,String> entry : backup.entrySet()) edit.putString("preset_backup_" + entry.getKey(), entry.getValue());
            edit.putBoolean("preset_has_backup", true).putString("preset_name", GraphicsPresets.NAMES[profile]);
        } else edit.putString("preset_name", "Свой");
        for (java.util.Map.Entry<String,String> entry : next.entrySet()) {
            edit.putString(entry.getKey(), entry.getValue());
            edit.putString("preset_applied_" + entry.getKey(), entry.getValue());
        }
        if (!edit.commit()) reportError("Не удалось сохранить настройки.");
        refreshOptions();
    }

    private void chooseOption(GameOptions.Option option) {
        String current = GameOptions.get(this, option.key);
        int checked = 0;
        for (int i = 0; i < option.values.length; i++) {
            if (option.values[i].equals(current)) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(option.title)
                .setSingleChoiceItems(option.labels, checked, (dialog, which) -> {
                    GameOptions.set(this, option.key, option.values[which]);
                    dialog.dismiss();
                    refreshOptions();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private View optionRow(String title, String value, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());
        row.addView(label(title, 14, 0xFFFFFFFF, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView current = label(value + "  ›", 13, ACCENT, true);
        current.setGravity(Gravity.END);
        row.addView(current);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void showChecks(File folder) {
        checksList.removeAllViews();
        addCheck("default.xex", new File(folder, "default.xex").isFile(), true);
    }

    private void addCheck(String name, boolean ok, boolean required) {
        String mark = ok ? "✓  " : (required ? "✗  " : "!  ");
        String warning = ok || required ? "" : " · создадутся при нажатии ИГРАТЬ";
        TextView row = label(mark + name + warning, 13,
                ok ? 0xFF7CD992 : (required ? 0xFFFF6B6B : 0xFFFFC857), false);
        row.setPadding(0, dp(2), 0, dp(2));
        checksList.addView(row);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC121A22);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);
        return card;
    }

    private Button actionButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 19 : 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(primary ? .10f : .02f);
        GradientDrawable bg = primary
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xFFE10600, 0xFF9E0400})
                : new GradientDrawable();
        if (!primary) {
            bg.setColor(0x20FFFFFF);
            bg.setStroke(dp(1), 0x3DFFFFFF);
        }
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setLetterSpacing(.06f);
        }
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void selectGameFolder() {
        if (!hasStorageAccess()) {
            prepareSharedGameFolder();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        String saved = getPreferences(MODE_PRIVATE).getString(TREE_URI, null);
        if (saved != null && Build.VERSION.SDK_INT >= 26) {
            intent.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(saved));
        }
        startActivityForResult(intent, REQUEST_GAME_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (launcherSaves != null && launcherSaves.result(requestCode, resultCode, data)) return;
        if (requestCode == REQUEST_DRIVER_PROBE) {
            launchGame.setEnabled(true);
            showDriverReport(resultCode == RESULT_OK && data != null ? data.getStringExtra("gpu_report")
                    : "{\"probeError\":\"Проверка была закрыта или отменена. Вы можете вернуться к системному драйверу.\",\"driverLoadFailed\":true}");
            return;
        }
        if (requestCode == REQUEST_DRIVER_ZIP) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri zip = data.getData();
            setImportStatus("Импорт драйвера Vulkan…");
            importer.execute(() -> {
                try {
                    GpuDrivers.Driver driver = GpuDrivers.importZip(this, zip);
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        GpuDrivers.select(this, driver.id); refreshOptions();
                        setImportStatus("Драйвер импортирован: " + driver.name + ". Запустите «Тест драйвера» для проверки.");
                    });
                } catch (Exception error) {
                    mainHandler.post(() -> reportError("Не удалось импортировать драйвер: " + error.getMessage()));
                }
            });
            return;
        }
        if (requestCode == REQUEST_ARCHIVE_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                startLocalArchiveInstall(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_SAVE_REPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) finishReportSave(data.getData());
            else pendingReport = null;
            return;
        }
        if (requestCode == REQUEST_STORAGE_ACCESS || requestCode == REQUEST_LEGACY_STORAGE) {
            if (hasStorageAccess()) prepareSharedGameFolder();
            else {
                selectFolder.setEnabled(true);
                setImportStatus("Выдайте разрешение на доступ к хранилищу для использования Внутренняя память/" + GAME_FOLDER_NAME + ".");
            }
            return;
        }
        if (requestCode != REQUEST_GAME_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(tree, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException error) {
            setImportStatus("Не удалось сохранить разрешение для папки: " + error.getMessage());
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString(TREE_URI, tree.toString()).apply();
        importGameFolder(tree);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LEGACY_STORAGE) {
            if (hasStorageAccess()) prepareSharedGameFolder();
            else {
                selectFolder.setEnabled(true);
                setImportStatus("Выдайте разрешение на доступ к хранилищу для использования Внутренняя память/" + GAME_FOLDER_NAME + ".");
            }
        } else if (requestCode == REQUEST_NOTIF_PERM) {
            if (pendingDownloadUrl != null) {
                String url = pendingDownloadUrl;
                pendingDownloadUrl = null;
                launchDownloadService(url);
            }
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void prepareSharedGameFolder() {
        if (!hasStorageAccess()) {
            selectFolder.setEnabled(false);
            launchGame.setVisibility(View.GONE);
            setImportStatus("Для использования Внутренняя память/" + GAME_FOLDER_NAME + " разрешите доступ к файлам.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent settings = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(settings, REQUEST_STORAGE_ACCESS);
            } else {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_LEGACY_STORAGE);
            }
            return;
        }

        selectFolder.setEnabled(false);
        launchGame.setVisibility(View.GONE);
        setImportStatus("Проверка файлов игры во внутренней памяти...");
        importer.execute(() -> {
            File gameRoot = sharedGameRoot();
            File oldPrivateRoot = privateGameRoot();
            File parent = gameRoot.getParentFile();
            File staging = new File(parent, "." + GAME_FOLDER_NAME + ".importing");
            File previous = new File(parent, "." + GAME_FOLDER_NAME + ".previous");
            try {
                if (isValidGameFolder(gameRoot)) {
                    if (oldPrivateRoot.exists()) deleteRecursively(oldPrivateRoot);
                    mainHandler.post(() -> showSharedGameFolder(null));
                    return;
                }

                if (isValidGameFolder(oldPrivateRoot)) {
                    mainHandler.post(() -> setImportStatus("Перемещение игры в Внутренняя память/" + GAME_FOLDER_NAME + "..."));
                    deleteRecursively(staging);
                    deleteRecursively(previous);
                    copyDirectory(oldPrivateRoot, staging, staging.getCanonicalFile(), new long[]{0});
                    if (!isValidGameFolder(staging)) throw new IOException("Копия не прошла проверку файлов.");
                    installStagedGame(staging, gameRoot, previous);
                    deleteRecursively(oldPrivateRoot);
                    deleteRecursively(previous);
                    mainHandler.post(() -> showSharedGameFolder("Игра перенесена из внутреннего хранилища."));
                    return;
                }

                if (!gameRoot.mkdirs() && !gameRoot.isDirectory()) {
                    throw new IOException("Не удалось создать " + gameRoot.getAbsolutePath());
                }
                mainHandler.post(() -> showSharedGameFolder(null));
            } catch (Exception error) {
                try { deleteRecursively(staging); } catch (IOException ignored) {}
                mainHandler.post(() -> {
                    setImportStatus("Не удалось подготовить папку игры: " + error.getMessage());
                    selectFolder.setEnabled(true);
                });
            }
        });
    }

    private File sharedGameRoot() {
        return new File(Environment.getExternalStorageDirectory(), GAME_FOLDER_NAME);
    }

    private File privateGameRoot() {
        return new File(new File(getFilesDir(), "f1_2012"), "game_root");
    }

    private static boolean isValidGameFolder(File folder) {
        return new File(folder, "default.xex").isFile();
    }

    private static void installStagedGame(File staging, File gameRoot, File previous) throws IOException {
        boolean movedPrevious = false;
        if (gameRoot.exists()) {
            Files.move(gameRoot.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
            movedPrevious = true;
        }
        try {
            Files.move(staging.toPath(), gameRoot.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            if (movedPrevious) Files.move(previous.toPath(), gameRoot.toPath(), StandardCopyOption.REPLACE_EXISTING);
            throw error;
        }
    }

    private void importGameFolder(Uri tree) {
        selectFolder.setEnabled(false);
        launchGame.setVisibility(View.GONE);
        setImportStatus("Копирование игры в Внутренняя память/" + GAME_FOLDER_NAME + "...");
        importer.execute(() -> {
            File gameRoot = sharedGameRoot();
            File parent = gameRoot.getParentFile();
            File staging = new File(parent, "." + GAME_FOLDER_NAME + ".importing");
            File previous = new File(parent, "." + GAME_FOLDER_NAME + ".previous");
            try {
                deleteRecursively(staging);
                deleteRecursively(previous);
                DocumentFile source = DocumentFile.fromTreeUri(this, tree);
                if (source == null || !source.isDirectory()) throw new IOException("Не удалось открыть выбранную папку.");
                copyDirectory(source, staging, staging.getCanonicalFile(), new long[]{0});
                if (!isValidGameFolder(staging)) {
                    throw new IOException("Папка должна содержать default.xex (F1 2012 Xbox 360).");
                }
                installStagedGame(staging, gameRoot, previous);
                File oldPrivateRoot = privateGameRoot();
                if (oldPrivateRoot.exists()) deleteRecursively(oldPrivateRoot);
                deleteRecursively(previous);
                mainHandler.post(() -> showSharedGameFolder(null));
            } catch (Exception error) {
                try { deleteRecursively(staging); } catch (IOException ignored) {}
                mainHandler.post(() -> setImportStatus("Ошибка импорта: " + error.getMessage()));
            } finally {
                mainHandler.post(() -> selectFolder.setEnabled(shaderBuilder == null));
            }
        });
    }

    private void copyDirectory(DocumentFile source, File destination, File safeRoot, long[] copiedBytes) throws IOException {
        if (!destination.getCanonicalFile().toPath().startsWith(safeRoot.toPath())) {
            throw new IOException("Папка игры содержит недопустимый путь.");
        }
        if (!destination.mkdirs() && !destination.isDirectory()) throw new IOException("Не удалось создать " + destination);
        for (DocumentFile child : source.listFiles()) {
            String name = child.getName();
            if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
                    name.contains("/") || name.contains("\\")) {
                throw new IOException("Папка игры содержит недопустимое имя файла.");
            }
            File target = new File(destination, name);
            if (child.isDirectory()) {
                copyDirectory(child, target, safeRoot, copiedBytes);
            } else if (child.isFile()) {
                File parent = target.getParentFile();
                if (parent == null || !parent.getCanonicalFile().toPath().startsWith(safeRoot.toPath())) {
                    throw new IOException("Файл выходит за пределы папки импорта.");
                }
                if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Не удалось создать папку игры.");
                try (InputStream input = getContentResolver().openInputStream(child.getUri());
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (input == null) throw new IOException("Не удалось прочитать " + name);
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        copiedBytes[0] += count;
                        if ((copiedBytes[0] & ((32L * 1024 * 1024) - 1)) < count) {
                            long total = copiedBytes[0];
                            mainHandler.post(() -> setImportStatus("Скопировано " + (total / (1024 * 1024)) + " МБ..."));
                        }
                    }
                    output.getFD().sync();
                }
            }
        }
    }

    private void copyDirectory(File source, File destination, File safeRoot, long[] copiedBytes) throws IOException {
        if (!destination.getCanonicalFile().toPath().startsWith(safeRoot.toPath())) {
            throw new IOException("Папка игры содержит недопустимый путь.");
        }
        if (!destination.mkdirs() && !destination.isDirectory()) throw new IOException("Не удалось создать " + destination);
        File[] children = source.listFiles();
        if (children == null) throw new IOException("Не удалось прочитать " + source.getAbsolutePath());
        byte[] buffer = new byte[1024 * 1024];
        for (File child : children) {
            File target = new File(destination, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, target, safeRoot, copiedBytes);
            } else if (child.isFile()) {
                try (InputStream input = new FileInputStream(child);
                     FileOutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        copiedBytes[0] += count;
                        if ((copiedBytes[0] & ((128L * 1024 * 1024) - 1)) < count) {
                            long total = copiedBytes[0];
                            mainHandler.post(() -> setImportStatus("Перенесено " + (total / (1024 * 1024)) + " МБ..."));
                        }
                    }
                    output.getFD().sync();
                }
            }
        }
    }

    private void showSharedGameFolder(String notice) {
        File gameRoot = sharedGameRoot();
        selectFolder.setEnabled(true);
        if (downloadGame != null) downloadGame.setEnabled(true);
        showChecks(gameRoot);
        if (isValidGameFolder(gameRoot)) {
            setImportStatus((notice == null ? "" : notice + "\n") + "Папка игры: " + GAME_FOLDER_NAME);
            launchGame.setVisibility(View.VISIBLE);
            if (downloadGame != null) downloadGame.setVisibility(View.GONE);
            selectFolder.setText("Сменить папку игры");
        } else {
            setImportStatus("Скачай архив игры с Google Диска или выбери распакованную папку с default.xex.");
            launchGame.setVisibility(View.GONE);
            if (downloadGame != null) downloadGame.setVisibility(View.VISIBLE);
            selectFolder.setText("Выбрать папку с игрой");
        }
    }

    private void setImportStatus(String message) {
        if (importStatus != null) importStatus.setText(message);
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete()) throw new IOException("Не удалось удалить " + file.getName());
    }

    @Override
    protected void onDestroy() {
        if (shaderBuilder != null) {
            shaderBuilder.cancel();
        }
        importer.shutdown();
        super.onDestroy();
    }
}
