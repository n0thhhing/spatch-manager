package spatch.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Four pages under a navigation bar: Spatch (Spotify install, build options, invites),
 * Clones (multi-instance clone manager), Diagnostics (self-test dashboard & crash log inspect),
 * and Changes (releases, display modes, and phone signing key).
 */
public final class MainActivity extends Activity {
    static final String SPOTIFY = "com.spotify.music", INVITE = "source_invite", DOWNLOADED = "apkm_downloaded";
    private static final String NOTIFY = "notify_updates";
    private static final String PAGE = "page", CLONE_PKG = "clone_package", CLONE_NAME = "clone_name", CLONE_INSTALLED = "clone_installed:";
    static final String INSTALLED = "installed_bundle", NIGHTLY = "nightly_patches", BRANCH = "nightly_branch", UPDATE = "start_update";
    static final String TOKEN = "github_token";
    private static final String CLONES_SET = "clones_packages";
    private static final String PENDING_KEY = "pending_install_key", PENDING_TAG = "pending_install_tag", PENDING_AT = "pending_install_at";
    private static final int PICK_APKM = 1, EXPORT_KEY = 2, IMPORT_KEY = 3, PICK_ICON = 4;
    private static final String[] PAGES = {"Spatch", "Clones", "Diagnostics", "Changes"};
    private static final String[] PAGE_ICONS = {"music_note", "content_copy", "stethoscope", "system_update"};
    private static final String[] HEADINGS = {"Spatch Manager", "Clones", "Diagnostics", "Changes"};
    private static final String[] LINES = {"Patched here, signed with this phone's key", "Second Spotifys with their own names", "What the phone and Spatch report", "What's new in each release"};

    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    Keys keys;
    Releases releases;
    String releaseError;
    Install install;
    private Diagnostics diagnostics;
    private String pendingTag, pendingKey, pendingDone;
    private boolean busy, installingSelf, conflict, autoUpdate;
    private Look look;
    private LinearLayout statusCard, changesBox, clonesListBox, buildBox;
    private TextView buildTitle, buildMeta;
    private ProgressBar buildBar;
    private String buildUrl = Releases.PAGE;
    private boolean buildWasActive, resumed;
    private Builds.Run build;
    private Look.StepTracker stepTracker;

    private android.widget.ImageView spotifyIcon;
    private TextView stateBadge;
    private TextView spotifyStatus, apkmStatus, patchStatus, whatsNew, keyStatus, managerStatus, status, cloneStatus;
    private Hero heading;
    private Button displayMode, choose, seeChanges, patch, uninstall, managerUpdate, check, refreshChanges, exportKey, importKey, invite;
    private Button cloneInstall, cloneIconPick, cloneIconReset;
    private EditText clonePkg, cloneName;
    private android.widget.ImageView cloneIcon;
    private LinearLayout[] pages;
    private LinearLayout nav;
    private ScrollView scroll;
    private ProgressBar progress;
    private Switch notify, nightly;
    private android.widget.Button branchButton, tokenButton;

    /** The branch whose nightly to install: "" is main, anything else a branch-<name> prerelease. */
    private String branch() {
        return prefs.getString(BRANCH, "");
    }

    private String branchLabel() {
        return "Branch: " + (branch().isEmpty() ? "main" : branch());
    }

    /** Branches with their own nightly, from the last release check; picking one turns Nightly patches on. */
    private void chooseBranch() {
        java.util.List<String> names = new ArrayList<>();
        names.add("main");
        if (releases != null) names.addAll(releases.branches);
        if (!branch().isEmpty() && !names.contains(branch())) names.add(branch());
        String[] labels = names.toArray(new String[0]);
        int selected = Math.max(0, names.indexOf(branch().isEmpty() ? "main" : branch()));
        new AlertDialog.Builder(this).setTitle("Nightly branch").setSingleChoiceItems(labels, selected, (dialog, index) -> {
            String picked = index == 0 ? "" : labels[index];
            dialog.dismiss();
            if (picked.equals(branch())) return;
            prefs.edit().putString(BRANCH, picked).apply();
            Look.label(branchButton, branchLabel());
            if (!picked.isEmpty() && !nightly.isChecked()) nightly.setChecked(true);
            releases = null;
            checkUpdates();
        }).setNegativeButton("Cancel", null).show();
    }

    private String tokenLabel() {
        String t = prefs.getString(TOKEN, null);
        return t != null && !t.trim().isEmpty() ? "Token: set" : "GitHub token";
    }

    private void editToken() {
        String current = prefs.getString(TOKEN, null);
        String pasted = clipboardText();
        String initial = current != null && !current.isEmpty() ? current
            : pasted != null && (pasted.startsWith("ghp_") || pasted.startsWith("github_pat_")) ? pasted : null;
        Runnable remove = () -> {
            prefs.edit().remove(TOKEN).apply();
            Releases.setToken(null);
            say("GitHub token removed.");
            if (tokenButton != null) Look.label(tokenButton, tokenLabel());
            checkUpdates();
        };
        prompt("GitHub personal access token",
            "GitHub limits anonymous requests to 60/hour per IP. Adding a token (classic or fine-grained with public repo read) raises the limit to 5,000/hour.",
            "ghp_… or github_pat_…", true, initial, "Save", val -> {
                if (val.isEmpty()) {
                    remove.run();
                } else {
                    prefs.edit().putString(TOKEN, val).apply();
                    Releases.setToken(val);
                    say("GitHub token saved. Rate limit is now 5,000 requests/hour.");
                    if (tokenButton != null) Look.label(tokenButton, tokenLabel());
                    checkUpdates();
                }
            }, current != null && !current.isEmpty() ? remove : null);
    }

    private final BroadcastReceiver installResult = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            busy = false;
            stepTracker.setVisibility(View.GONE);
            delete(new File(getCacheDir(), "build"));
            boolean cloning = !installingSelf && !INSTALLED.equals(pendingKey);
            if (!installingSelf) clearPending();
            if (code == PackageInstaller.STATUS_SUCCESS) {
                install.inheritFailed = false;
                if (!installingSelf && pendingTag != null) prefs.edit().putString(pendingKey, pendingTag).apply();
                say(installingSelf ? "Spatch Manager is up to date." : pendingDone);
            } else if (code == PackageInstaller.STATUS_FAILURE_CONFLICT || code == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                conflict = !installingSelf && !cloning;
                say(installingSelf ? "This manager build is signed with a different key; install the new one from GitHub by hand."
                    : cloning ? "This clone could not be installed. If it already exists, update it with the same signing key used to create it. "
                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    : "Spotify on this phone is signed with a different key. Uninstall it first (that removes its downloads), or import the key it was signed with.");
            } else if (code == PackageInstaller.STATUS_FAILURE_ABORTED) {
                say("Install cancelled.");
            } else if (install.inheriting && !installingSelf) {
                install.inheritFailed = true;
                say("The quick update didn't take (" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "). Tap again to reinstall all of Spatch.");
            } else {
                say("Install failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
            refresh();
        }
    };

    private void clearPending() {
        prefs.edit().remove(PENDING_KEY).remove(PENDING_TAG).remove(PENDING_AT).apply();
    }

    private void recoverPendingInstall() {
        String key = prefs.getString(PENDING_KEY, null), tag = prefs.getString(PENDING_TAG, null);
        long at = prefs.getLong(PENDING_AT, 0);
        if (key == null || tag == null || at <= 0) return;
        String pkg = INSTALLED.equals(key) ? SPOTIFY : key.startsWith(CLONE_INSTALLED) ? key.substring(CLONE_INSTALLED.length()) : null;
        PackageInfo info = pkg == null ? null : installed(pkg);
        if (info != null && info.lastUpdateTime > at) {
            prefs.edit().putString(key, tag).remove(PENDING_KEY).remove(PENDING_TAG).remove(PENDING_AT).apply();
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context context) {
        String mode=context.getSharedPreferences("manager",MODE_PRIVATE).getString("theme_mode","system");
        if(!mode.equals("system")){
            android.content.res.Configuration config=new android.content.res.Configuration(context.getResources().getConfiguration());
            config.uiMode=(config.uiMode&~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                |(mode.equals("dark")?android.content.res.Configuration.UI_MODE_NIGHT_YES:android.content.res.Configuration.UI_MODE_NIGHT_NO);
            context=context.createConfigurationContext(config);
        }
        super.attachBaseContext(context);
    }

    private boolean appearancePending;
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config){
        super.onConfigurationChanged(config);
        if(prefs!=null && prefs.getString("theme_mode","system").equals("system")
            && look.dark!=((config.uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES)){
            if(busy)appearancePending=true;else recreate();
        }
    }

    @Override
    protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); // the page draws its own heading
        prefs = getSharedPreferences("manager", MODE_PRIVATE);
        install = new Install(this, prefs, this::step);
        recoverPendingInstall();
        Releases.setToken(prefs.getString(TOKEN, null));
        look = new Look(this);
        getWindow().getDecorView().setBackgroundColor(look.surface);
        getWindow().setStatusBarColor(look.surface);
        if (!look.dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(layout());
        autoUpdate = getIntent().getBooleanExtra(UPDATE, false);
        shared(getIntent());
        UpdateCheck.schedule(this, notify.isChecked());
        if (notify.isChecked() && android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 0);
        }
        registerReceiver(installResult, new IntentFilter(Installer.ACTION), Context.RECEIVER_NOT_EXPORTED);
        refresh();
        worker.execute(() -> {
            try {
                Keys k = Keys.loadOrCreate(getFilesDir());
                main.post(() -> {
                    keys = k;
                    refresh();
                });
            } catch (Exception e) {
                main.post(() -> say("Couldn't open the signing key: " + e.getMessage()));
            }
        });
        checkUpdates();
    }

    /** The update notification, tapped while the manager was already open. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        shared(intent);
        if (intent.getBooleanExtra(UPDATE, false)) {
            autoUpdate = true;
            checkUpdates();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // Spotify may have been installed or removed meanwhile
        resumed = true;
        main.post(pollBuild);
    }

    @Override
    protected void onPause() {
        resumed = false;
        main.removeCallbacks(pollBuild);
        super.onPause();
    }

    /** Reads the nightly's newest Actions run; while it builds, again every 30 s (GitHub allows 60 unsigned requests an hour). */
    private final Runnable pollBuild = new Runnable() {
        public void run() {
            main.removeCallbacks(this);
            new Thread(() -> {
                Builds.Run run;
                try {
                    run = Builds.newest(branch());
                } catch (Exception offline) {
                    run = null; // offline or rate limited: the card just hides
                }
                Builds.Run r = run;
                main.post(() -> showBuild(r));
            }).start();
        }
    };

    /** Home's one line of news: a nightly building right now, else the latest change or release note. */
    private void showHeadline(Releases.Release bundle) {
        String headline = nightly.isChecked() && build != null && build.active
            ? "Nightly building: " + build.title + (build.progress > 0 ? "  •  " + Math.round(build.progress * 100) + "%" : "")
            : nightly.isChecked() && releases != null && !releases.commits.isEmpty()
            ? "Latest change: " + releases.commits.get(0).title
            : bundle != null && !bundle.notes.isEmpty() ? "What's new: " + Releases.summary(bundle.notes) : "";
        whatsNew.setText(headline);
        whatsNew.setVisibility(headline.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showBuild(Builds.Run r) {
        if (buildBox == null || isDestroyed()) return;
        boolean active = r != null && r.active;
        if (buildWasActive && !active) checkUpdates(); // finished: the new nightly is out, or the card says why not
        buildWasActive = active;
        build = r;
        if (whatsNew != null) showHeadline(releases == null ? null : releases.bundle(nightly.isChecked()));
        buildBox.setVisibility(r == null ? View.GONE : View.VISIBLE);
        if (r == null) return;
        buildUrl = r.url;
        buildTitle.setText(r.title);
        long minutes = r.started == 0 ? -1 : Math.max(0, (System.currentTimeMillis() - r.started) / 60000);
        long left = r.started == 0 || r.typical == 0 ? -1 : (r.started + r.typical - System.currentTimeMillis() + 59_999) / 60000;
        String time = minutes < 0 ? "" : left > 0 ? "  •  about " + left + " min left" : "  •  " + minutes + " min";
        buildMeta.setText(r.commit + "  •  " + (active ? r.step + time : "Build failed, tap for the log"));
        buildMeta.setTextColor(active ? look.primary : look.onSurfaceVariant);
        buildBar.setVisibility(active ? View.VISIBLE : View.GONE);
        buildBar.setIndeterminate(r.progress <= 0);
        if (r.progress > 0) {
            buildBar.setMax(1000);
            buildBar.setProgress(Math.round(r.progress * 1000));
        }
        if (active && resumed) main.postDelayed(pollBuild, 30_000);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(installResult);
        worker.shutdownNow();
        super.onDestroy();
    }

    private View layout() {
        LinearLayout page = look.column();
        page.setPadding(look.dp(16), look.dp(56), look.dp(16), look.dp(32));

        heading = new Hero(this, look);
        page.addView(heading, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, look.dp(132)));

        statusCard = look.column();
        statusCard.setPadding(look.dp(20), look.dp(16), look.dp(20), look.dp(16));
        statusCard.setBackground(look.shape(look.secondaryContainer, 28));
        status = look.text("", 15, look.onSecondaryContainer, false);
        statusCard.addView(status);
        stepTracker = look.stepTracker();
        stepTracker.setVisibility(View.GONE);
        statusCard.addView(stepTracker, look.spaced(10));
        progress = look.progress();
        progress.setVisibility(View.GONE);
        statusCard.addView(progress, look.spaced(8));
        statusCard.setVisibility(View.GONE);
        page.addView(statusCard, look.spaced(12));

        pages = new LinearLayout[PAGES.length];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = look.column();
            page.addView(pages[i]);
        }

        // --- Page 0: Spatch ---

        LinearLayout manager = look.card(pages[0], "Spatch Manager");
        managerStatus = look.text("", 14, look.onSurfaceVariant, false);
        manager.addView(managerStatus, look.spaced(6));
        managerUpdate = look.button("Update manager", "system_update", Look.Kind.FILLED, v -> updateManager());
        manager.addView(managerUpdate, look.spaced(10));
        check = look.button("Check for updates", "refresh", Look.Kind.TEXT, v -> checkUpdates());
        manager.addView(check, look.spaced(4));

        LinearLayout spotify = look.card(pages[0], "Spotify");
        spotifyStatus = look.text("", 16, look.onSurface, false);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        spotifyIcon = new android.widget.ImageView(this);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(look.dp(52), look.dp(52));
        il.setMarginEnd(look.dp(14));
        head.addView(spotifyIcon, il);
        LinearLayout headWords = look.column();
        stateBadge = look.badge("", look.onPrimaryContainer, look.primaryContainer);
        headWords.addView(stateBadge, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        headWords.addView(spotifyStatus, look.spaced(4));
        head.addView(headWords, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        spotify.addView(head);
        apkmStatus = look.text("", 14, look.onSurfaceVariant, false);
        spotify.addView(apkmStatus, look.spaced(6));
        patchStatus = look.text("", 14, look.onSurfaceVariant, false);
        spotify.addView(patchStatus, look.spaced(2));
        whatsNew = look.text("", 13, look.primary, false);
        whatsNew.setVisibility(View.GONE);
        spotify.addView(whatsNew, look.spaced(4));
        patch = look.button("Install Spatch", "download", Look.Kind.FILLED, v -> patch());
        spotify.addView(patch, look.spaced(12));
        choose = look.button("Choose APKM", "folder_open", Look.Kind.TONAL, v -> pickApkm());
        seeChanges = look.button("What's new", "system_update", Look.Kind.TONAL, v -> show(3));
        spotify.addView(look.row(choose, seeChanges), look.spaced(8));
        invite = look.button("Add an invite", "link", Look.Kind.TEXT, v -> editInvite());
        spotify.addView(invite, look.spaced(4));
        uninstall = look.button("Uninstall Spotify", "delete", Look.Kind.TONAL, v -> startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + SPOTIFY))));
        spotify.addView(uninstall, look.spaced(8));

        LinearLayout patchOptions = look.card(pages[0], "Patch options");
        nightly = new M3Switch(this, look);
        nightly.setChecked(prefs.getBoolean(NIGHTLY, false));
        nightly.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(NIGHTLY, on).apply();
            refresh();
        });
        patchOptions.addView(look.switchRow("Nightly patches", "Built from every change before it's released; less tested", nightly));
        branchButton = look.button(branchLabel(), "tune", Look.Kind.TONAL, v -> chooseBranch());
        tokenButton = look.button(tokenLabel(), "key", Look.Kind.TONAL, v -> editToken());
        patchOptions.addView(look.row(branchButton, tokenButton), look.spaced(6));

        notify = new M3Switch(this, look);
        notify.setChecked(prefs.getBoolean(NOTIFY, true));
        notify.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(NOTIFY, on).apply();
            UpdateCheck.schedule(this, on);
            if (on && Build.VERSION.SDK_INT >= 33) requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 0);
        });
        patchOptions.addView(look.switchRow("Tell me about new patches", "Checks twice a day; tap the notification to update", notify));

        Switch playful = new M3Switch(this, look);
        playful.setChecked(prefs.getBoolean(Look.MOTION, true));
        playful.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(Look.MOTION, on).apply());
        patchOptions.addView(look.switchRow("Playful motion", "Jelly navigation, springy pages and pops; Android's animation setting still applies", playful));

        // --- Page 1: Clones ---
        cloneLayout(pages[1]);

        // --- Page 2: Diagnostics ---
        diagnostics = new Diagnostics(this, look);
        diagnostics.layout(pages[2]);

        // --- Page 3: Changes ---
        changesLayout(pages[3]);

        scroll = new ScrollView(this);
        scroll.addView(page);
        nav = look.navBar(PAGES, PAGE_ICONS, this::show);
        LinearLayout root = look.column();
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(nav);
        // Android 15 draws apps edge to edge: keep the page clear of the status bar, the bar clear of navigation.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            scroll.setPadding(0, top, 0, 0);
            page.setPadding(look.dp(16), look.dp(24), look.dp(16), look.dp(32));
            nav.setPadding(0, 0, 0, bottom);
            return insets;
        });
        getWindow().setNavigationBarColor(look.container);
        show(Math.min(prefs.getInt(PAGE, 0), PAGES.length - 1));
        return root;
    }

    private void show(int index) {
        boolean change = !pages[index].isShown();
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        if (change && Look.motion(this)) {
            LinearLayout page = pages[index];
            for (int i = 0; i < page.getChildCount(); i++) { // cards rise one after another
                View card = page.getChildAt(i);
                card.setAlpha(0f);
                card.setTranslationY(look.dp(28 + 10 * Math.min(i, 4)));
                card.animate().alpha(1f).translationY(0f).setStartDelay(45L * Math.min(i, 6)).setDuration(380)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            }
        }
        heading.set(HEADINGS[index], LINES[index]);
        look.select(nav, index);
        scroll.scrollTo(0, 0);
        prefs.edit().putInt(PAGE, index).apply();
    }

    static final class CloneInfo {
        final String pkg;
        final String label;
        final PackageInfo info;
        final boolean signedByUs;
        final String tag;

        CloneInfo(String pkg, String label, PackageInfo info, boolean signedByUs, String tag) {
            this.pkg = pkg;
            this.label = label;
            this.info = info;
            this.signedByUs = signedByUs;
            this.tag = tag;
        }
    }

    private List<CloneInfo> scanClones() {
        List<CloneInfo> list = new ArrayList<>();
        Set<String> pkgs = new HashSet<>();
        PackageManager pm = getPackageManager();
        try {
            for (PackageInfo pi : pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)) {
                String p = pi.packageName;
                if (p != null && p.startsWith("com.spotify.music.") && !p.equals(SPOTIFY)) {
                    pkgs.add(p);
                }
            }
        } catch (Exception ignored) {}
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(CLONE_INSTALLED)) {
                pkgs.add(key.substring(CLONE_INSTALLED.length()));
            }
        }
        for (String p : prefs.getStringSet(CLONES_SET, Collections.emptySet())) {
            pkgs.add(p);
        }
        String lastPkg = prefs.getString(CLONE_PKG, null);
        if (lastPkg != null && !lastPkg.equals(SPOTIFY)) pkgs.add(lastPkg);

        List<String> sorted = new ArrayList<>(pkgs);
        Collections.sort(sorted);
        for (String p : sorted) {
            PackageInfo pi = installed(p);
            String label = p;
            if (pi != null) {
                try {
                    CharSequence cs = pm.getApplicationLabel(pi.applicationInfo);
                    if (cs != null && cs.length() > 0) label = cs.toString();
                } catch (Exception ignored) {}
            } else if (p.equals(prefs.getString(CLONE_PKG, ""))) {
                label = prefs.getString(CLONE_NAME, p);
            }
            boolean ours = pi != null && keys != null && signedByUs(pi);
            String tag = prefs.getString(CLONE_INSTALLED + p, null);
            list.add(new CloneInfo(p, label, pi, ours, tag));
        }
        return list;
    }

    private String nextClonePkg() {
        String[] suggestions = {"two", "three", "four", "five", "six", "work", "alt"};
        for (String s : suggestions) {
            String candidate = "com.spotify.music." + s;
            if (installed(candidate) == null) return candidate;
        }
        return "com.spotify.music.clone" + (System.currentTimeMillis() % 1000);
    }

    private void cloneLayout(LinearLayout page) {
        TextView note = look.text("Manage multiple isolated Spotify instances on this phone. Each clone has its own separate login, local downloads, and launcher icon.", 15, look.onSurfaceVariant, false);
        note.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        page.addView(note);

        LinearLayout listCard = look.card(page, "Installed Clones");
        clonesListBox = look.column();
        listCard.addView(clonesListBox);

        LinearLayout createCard = look.card(page, "Create New Clone");
        cloneStatus = look.text("", 14, look.onSurfaceVariant, false);
        createCard.addView(cloneStatus, look.spaced(2));

        cloneName = new EditText(this);
        cloneName.setText(prefs.getString(CLONE_NAME, "spatch"));
        cloneName.setHint("spatch");
        createCard.addView(look.field("App name", cloneName), look.spaced(12));

        clonePkg = new EditText(this);
        String suggestedPkg = prefs.getString(CLONE_PKG, nextClonePkg());
        clonePkg.setText(suggestedPkg);
        clonePkg.setHint("com.spotify.music.two");
        clonePkg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        clonePkg.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                refresh();
            }
        });
        createCard.addView(look.field("Package name", clonePkg), look.spaced(10));

        TextView iconLabel = look.text("Icon", 13, look.onSurfaceVariant, true);
        createCard.addView(iconLabel, look.spaced(10));
        cloneIcon = new android.widget.ImageView(this);
        cloneIcon.setClipToOutline(true);
        cloneIcon.setBackground(look.shape(look.containerHigh, 28));
        cloneIconPick = look.button("Choose", "image", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_ICON));
        cloneIconReset = look.button("Spotify's", Look.Kind.TEXT, v -> {
            cloneIconFile().delete();
            refresh();
        });
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(look.dp(56), look.dp(56));
        iconSize.setMarginEnd(look.dp(16));
        iconRow.addView(cloneIcon, iconSize);
        iconRow.addView(look.row(cloneIconPick, cloneIconReset), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        createCard.addView(iconRow, look.spaced(6));

        cloneInstall = look.button("Install clone", "content_copy", Look.Kind.FILLED, v -> installClone());
        createCard.addView(cloneInstall, look.spaced(16));
    }

    private void renderClones() {
        if (clonesListBox == null) return;
        clonesListBox.removeAllViews();
        List<CloneInfo> clones = scanClones();
        int installedCount = 0;
        for (CloneInfo c : clones) {
            if (c.info == null) continue;
            installedCount++;
            LinearLayout item = look.column();
            item.setPadding(look.dp(16), look.dp(12), look.dp(16), look.dp(12));
            item.setBackground(look.shape(look.containerHigh, 20, Look.blend(look.containerHigh, look.outline, 0.22f), 1f));

            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setClipToOutline(true);
            icon.setBackground(look.shape(look.container, 14));
            try {
                icon.setImageDrawable(c.info.applicationInfo.loadIcon(getPackageManager()));
            } catch (Exception e) {
                icon.setImageDrawable(spotifyIcon());
            }
            header.addView(icon, new LinearLayout.LayoutParams(look.dp(44), look.dp(44)));

            LinearLayout words = look.column();
            words.setPadding(look.dp(12), 0, look.dp(8), 0);
            words.addView(look.text(c.label, 16, look.onSurface, true));
            String sub = c.pkg + " • " + c.info.versionName + (c.tag != null ? " (" + c.tag.replaceFirst("^(patches-v|bundle-)", "") + ")" : "");
            words.addView(look.text(sub, 13, look.onSurfaceVariant, false));
            header.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView badge = c.signedByUs ? look.badge("Phone Key", look.onTertiaryContainer, look.tertiaryContainer)
                                          : look.badge("Other Key", look.onPrimary, look.primary);
            header.addView(badge);
            item.addView(header);

            Button open = look.button("Open", "open_in_new", Look.Kind.TEXT, v -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage(c.pkg);
                if (launch != null) startActivity(launch);
                else say("Couldn't open " + c.label);
            });
            Button upd = look.button("Update", "system_update", Look.Kind.TONAL, v -> {
                try {
                    File ic = cloneIconFile();
                    patch(new Clone(c.pkg, c.label, ic.isFile() ? java.nio.file.Files.readAllBytes(ic.toPath()) : null));
                } catch (Exception e) {
                    say("Update failed: " + e.getMessage());
                }
            });
            look.enable(upd, !busy && patch.isEnabled());
            Button uninst = look.button("Uninstall", "delete", Look.Kind.TEXT, v -> {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + c.pkg)));
            });
            for (Button b : new Button[] {open, upd, uninst}) b.setPadding(look.dp(6), 0, look.dp(6), 0); // three to a row: room for the words
            item.addView(look.row(open, upd, uninst), look.spaced(8));

            clonesListBox.addView(item, look.spaced(8));
        }
        if (installedCount == 0) {
            clonesListBox.addView(look.text("No clones are installed yet. Use the form below to create your first clone.", 14, look.onSurfaceVariant, false));
        }
    }

    private void changesLayout(LinearLayout page) {
        TextView note = look.text("Recent commits in Nightly and release notes from published Spatch updates.", 15, look.onSurfaceVariant, false);
        note.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        page.addView(note);
        refreshChanges = look.button("Refresh", "refresh", Look.Kind.TONAL, v -> {
            checkUpdates();
            main.post(pollBuild);
        });
        Button openGithub = look.button("GitHub releases", "open_in_new", Look.Kind.TONAL, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Releases.PAGE))));
        page.addView(look.row(refreshChanges, openGithub), look.spaced(10));
        buildBox = look.column();
        buildBox.setVisibility(View.GONE);
        page.addView(buildBox);
        LinearLayout build = look.card(buildBox, "Nightly build");
        buildTitle = look.text("", 15, look.onSurface, true);
        build.addView(buildTitle);
        buildMeta = look.text("", 13, look.primary, false);
        build.addView(buildMeta, look.spaced(2));
        buildBar = look.progress();
        build.addView(buildBar, look.spaced(10));
        build.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(buildUrl))));
        changesBox = look.column();
        page.addView(changesBox);

        LinearLayout options = look.card(page, "Appearance");
        displayMode = look.button("Display mode: " + displayModeLabel(), "tune", Look.Kind.TONAL, v -> {
            String[] modes = {"system", "light", "dark", "black"};
            String[] labels = {"System (Dynamic)", "Light", "Dark", "Pitch Black (AMOLED)"};
            int selected = Arrays.asList(modes).indexOf(prefs.getString("theme_mode", "system"));
            if (selected < 0) selected = 0;
            new AlertDialog.Builder(this).setTitle("Display mode").setSingleChoiceItems(labels, selected, (dialog, index) -> {
                prefs.edit().putString("theme_mode", modes[index]).apply();
                dialog.dismiss();
                recreate();
            }).setNegativeButton("Cancel", null).show();
        });
        options.addView(displayMode, look.spaced(6));

        LinearLayout key = look.card(page, "Signing key");
        TextView keyNote = look.text("Spatch and its clones are signed with this phone's key. Export it to keep it safe, or import the key an earlier Spatch was signed with so updates install over it.", 14, look.onSurfaceVariant, false);
        key.addView(keyNote);
        keyStatus = look.text("", 13, look.onSurfaceVariant, false);
        keyStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.addView(keyStatus, look.spaced(8));
        exportKey = look.button("Export", "key", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-pkcs12").putExtra(Intent.EXTRA_TITLE, "spatch-key.p12"), EXPORT_KEY));
        importKey = look.button("Import", "file_open", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_KEY));
        key.addView(look.row(exportKey, importKey), look.spaced(12));
    }

    private String displayModeLabel() {
        String mode = prefs.getString("theme_mode", "system");
        switch (mode) {
            case "light": return "Light";
            case "dark": return "Dark";
            case "black": return "Pitch Black";
            default: return "System";
        }
    }

    private void renderChanges(String installed) {
        changesBox.removeAllViews();
        if (releases == null) {
            LinearLayout card = look.card(changesBox, "Changes");
            card.addView(look.text(releaseError != null ? releaseError : "Looking for changes…", 14, look.onSurfaceVariant, false));
            if (releaseError != null && (releaseError.contains("limit") || releaseError.contains("403") || releaseError.contains("401"))) {
                card.addView(look.button(tokenLabel(), "key", Look.Kind.TONAL, v -> editToken()), look.spaced(8));
            }
            return;
        }
        if (!releases.commits.isEmpty() || releases.nightly != null) {
            LinearLayout card = look.card(changesBox, "Nightly commits");
            if (releases.commits.isEmpty()) {
                card.addView(look.text(Releases.version(releases.nightly), 13, look.primary, true));
                if (!releases.nightly.notes.isEmpty()) card.addView(look.text(releases.nightly.notes, 14, look.onSurfaceVariant, false), look.spaced(4));
            } else {
                for (int i = 0; i < releases.commits.size(); i++) {
                    Releases.Note c = releases.commits.get(i);
                    // The nightly's tag fingerprints the patch sources; commits are matched by the release's commit.
                    boolean newest = releases.nightly != null && releases.nightly.commit != null ? ("nightly-" + releases.nightly.commit).equals(c.tag) : i == 0;
                    boolean onPhone = (installed != null && (installed.startsWith(c.tag) || newest && releases.nightly != null && installed.startsWith(releases.nightly.tag)))
                        || ownName().endsWith("-" + c.tag.replaceFirst("^nightly-", ""));
                    String meta = c.tag.replaceFirst("^nightly-", "")
                        + (c.date.isEmpty() ? "" : "  •  " + c.date)
                        + (onPhone ? "  •  Installed" : newest ? "  •  Newest nightly" : "");
                    LinearLayout words = look.column();
                    words.setPadding(0, 0, 0, look.dp(14));
                    words.addView(look.text(c.title, 15, look.onSurface, true));
                    words.addView(look.text(meta, 13, onPhone || newest ? look.primary : look.onSurfaceVariant, onPhone || newest), look.spaced(2));
                    if (!c.body.isEmpty()) words.addView(look.text(c.body, 13, look.onSurfaceVariant, false), look.spaced(4));
                    LinearLayout row = new LinearLayout(this);
                    row.addView(look.timeline(i == 0, i == releases.commits.size() - 1, onPhone || newest), new LinearLayout.LayoutParams(look.dp(24), LinearLayout.LayoutParams.MATCH_PARENT));
                    LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    wl.setMarginStart(look.dp(10));
                    row.addView(words, wl);
                    card.addView(row, look.spaced(i == 0 ? 6 : 0));
                }
            }
        }
        String own = ownName();
        for (Releases.Note n : releases.notes) {
            LinearLayout card = look.card(changesBox, n.title);
            boolean onPhone = (installed != null && installed.startsWith(n.tag))
                || (n.tag.startsWith("manager-v") && n.tag.substring("manager-v".length()).equals(own));
            String meta = n.tag + (n.date.isEmpty() ? "" : "  •  " + n.date) + (onPhone ? "  •  Installed" : "");
            card.addView(look.text(meta, 13, onPhone ? look.primary : look.onSurfaceVariant, onPhone));
            card.addView(look.text(n.body, 14, look.onSurfaceVariant, false), look.spaced(6));
        }
    }

    private void installClone() {
        String pkg = clonePkg.getText().toString().trim(), name = cloneName.getText().toString().trim();
        String problem = Clone.problem(pkg);
        if (problem != null) {
            say(problem);
            return;
        }
        if (name.isEmpty()) {
            say("Give the clone a name.");
            return;
        }
        prefs.edit().putString(CLONE_PKG, pkg).putString(CLONE_NAME, name).apply();
        Set<String> set = new HashSet<>(prefs.getStringSet(CLONES_SET, Collections.emptySet()));
        set.add(pkg);
        prefs.edit().putStringSet(CLONES_SET, set).apply();
        try {
            File icon = cloneIconFile();
            patch(new Clone(pkg, name, icon.isFile() ? java.nio.file.Files.readAllBytes(icon.toPath()) : null));
        } catch (java.io.IOException e) {
            say("Couldn't read the icon: " + e.getMessage());
        }
    }

    /** The chosen picture as an adaptive icon's foreground: centred in the 72dp a launcher shows, on its own average colour. */
    static byte[] iconPng(android.graphics.Bitmap picture) {
        int size = 432, inset = 72; // 108dp at 4x; the mask shows the middle 72dp
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(android.graphics.Bitmap.createScaledBitmap(picture, 1, 1, true).getPixel(0, 0) | 0xff000000);
        int w = picture.getWidth(), h = picture.getHeight(), side = Math.min(w, h);
        android.graphics.Rect from = new android.graphics.Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
        canvas.drawBitmap(picture, from, new android.graphics.Rect(inset, inset, size - inset, size - inset), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
        return png.toByteArray();
    }

    private File cloneIconFile() {
        return new File(getFilesDir(), "clone_icon.png");
    }

    private PackageInfo installed(String pkg) {
        return install.installed(pkg);
    }

    /** Puts every label and button in line with the current state. */
    private void refresh() {
        if(appearancePending && !busy){appearancePending=false;recreate();return;}
        PackageInfo info = spotify();
        boolean ours = info != null && keys != null && signedByUs(info);
        String installed = prefs.getString(INSTALLED, null);
        spotifyStatus.setText(info == null ? "Spotify isn't installed."
            : ours ? "Spatch is installed" + (installed != null ? ": patches " + shownPatches(installed) : "") + ". Spotify " + info.versionName + "."
            : "Spotify " + info.versionName + " is installed with another key. To install over it, import the key it was signed with (Signing key, Import key); otherwise it has to be uninstalled first.");
        File apkm = install.apkm();
        Sources.Invite source = install.invite();
        apkmStatus.setText(apkm.isFile() ? (prefs.getBoolean(DOWNLOADED, false) ? "Spotify downloaded (" : "APKM saved (") + apkm.length() / (1 << 20) + " MB)."
            : install.originalBase().isFile() ? "The original Spotify is kept, so no APKM is needed."
            : source != null ? "Spotify downloads from " + source.host() + " when you install."
            : info != null && !ours ? "No APKM needed if the installed Spotify is the unmodified version Spatch supports."
            : "Choose the Spotify APKM that Spatch supports, or add an invite to a Spatch source.");
        Releases.Release bundle = releases == null ? null : releases.bundle(nightly.isChecked());
        patchStatus.setText(bundle != null ? "Newest patches: " + Releases.version(bundle) + "."
            : releaseError != null ? releaseError : "Looking for patches…");

        showHeadline(bundle);
        String wanted = bundle == null ? null : bundle.tag;
        boolean current = ours && (wanted == null || wanted.equals(installed));
        spotifyIcon.setImageDrawable(info != null ? info.applicationInfo.loadIcon(getPackageManager()) : getApplicationInfo().loadIcon(getPackageManager()));
        spotifyIcon.setAlpha(info == null ? 0.4f : 1f);
        stateBadge.setText(info == null ? "Not installed" : !ours ? "Another key" : current ? "Up to date" : "Update ready");
        int badgeBg = current ? look.primaryContainer : ours ? look.tertiaryContainer : look.secondaryContainer;
        int badgeFg = current ? look.onPrimaryContainer : ours ? look.onTertiaryContainer : look.onSecondaryContainer;
        stateBadge.setTextColor(badgeFg);
        android.graphics.drawable.GradientDrawable bd = look.shape(badgeBg, 10);
        bd.setStroke(look.dp(1), Look.blend(badgeBg, badgeFg, 0.25f));
        stateBadge.setBackground(bd);
        Look.label(patch, !ours ? "Install Spatch" : wanted != null && !wanted.equals(installed) ? "Update Spatch" : "Reinstall Spatch");
        Look.label(choose, apkm.isFile() ? "Another APKM" : "Choose APKM");
        look.enable(patch, !busy && keys != null && bundle != null && (apkm.isFile() || info != null || source != null));
        Look.label(invite, source == null ? "Add an invite" : "Invite for " + source.host());
        nightly.setEnabled(!busy);
        uninstall.setVisibility(conflict && info != null && !ours ? View.VISIBLE : View.GONE);
        try {
            keyStatus.setText(keys == null ? "Making this phone's key…" : "Fingerprint\n" + keys.fingerprint().substring(0, 32).replaceAll("(.{4})(?!$)", "$1 "));
        } catch (Exception e) {
            keyStatus.setText("Key unreadable.");
        }
        Releases.Release mgrUpdate = managerUpdateTarget();
        managerStatus.setText("Spatch Manager " + ownName() + (mgrUpdate != null
            ? " (" + (mgrUpdate.tag.startsWith("nightly-") ? Releases.version(mgrUpdate) : "version " + Releases.version(mgrUpdate)) + " is available)."
            : " is up to date."));
        managerUpdate.setVisibility(mgrUpdate != null ? View.VISIBLE : View.GONE);
        for (Button b : Arrays.asList(displayMode, choose, seeChanges, managerUpdate, check, refreshChanges, importKey, invite, cloneIconPick, cloneIconReset, diagnostics.runDiagnosticsBtn, diagnostics.copyDiagnosticsBtn, diagnostics.refreshExitsBtn)) {
            if (b != null) look.enable(b, !busy);
        }
        look.enable(exportKey, !busy && keys != null);
        renderChanges(installed);
        renderClones();

        String pkg = clonePkg.getText().toString().trim();
        String problem = Clone.problem(pkg);
        PackageInfo clone = problem == null ? installed(pkg) : null;
        boolean cloneOurs = clone != null && keys != null && signedByUs(clone);
        String cloneTag = prefs.getString(CLONE_INSTALLED + pkg, null);
        cloneStatus.setText(problem != null ? problem
            : clone == null ? "Ready to create " + pkg + "."
            : cloneOurs ? "Installed" + (cloneTag != null ? ", patches " + cloneTag.replaceFirst("^(patches-v|bundle-)", "") : "") + ". Spotify " + clone.versionName + "."
            : pkg + " is another app, signed with another key.");
        Look.label(cloneInstall, cloneOurs ? "Update clone" : "Install clone");
        look.enable(cloneInstall, problem == null && (clone == null || cloneOurs) && patch.isEnabled());
        File icon = cloneIconFile();
        if (icon.isFile()) cloneIcon.setImageBitmap(android.graphics.BitmapFactory.decodeFile(icon.getPath()));
        else cloneIcon.setImageDrawable(spotifyIcon());
        cloneIconReset.setVisibility(icon.isFile() ? View.VISIBLE : View.INVISIBLE);
        clonePkg.setEnabled(!busy);
        cloneName.setEnabled(!busy);
    }

    /** Spotify's launcher icon as installed, or the manager's own when Spotify isn't. */
    private android.graphics.drawable.Drawable spotifyIcon() {
        PackageInfo info = spotify();
        return info != null ? info.applicationInfo.loadIcon(getPackageManager()) : getApplicationInfo().loadIcon(getPackageManager());
    }

    private void checkUpdates() {
        releaseError = null;
        refresh();
        new Thread(() -> {
            try {
                Releases r = Releases.fetch(branch());
                main.post(() -> {
                    releases = r;
                    if (r.bundle == null) releaseError = "No patches are published yet.";
                    refresh();
                    if (autoUpdate && !busy && patch.isEnabled()) patch(); // from the notification
                    autoUpdate = false;
                });
            } catch (Exception e) {
                main.post(() -> {
                    releaseError = "Couldn't reach GitHub: " + e.getMessage();
                    refresh();
                });
            }
        }).start();
    }

    private void pickApkm() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_APKM);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_ICON) {
            work("Making the icon", () -> {
                android.graphics.Bitmap picture = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(getContentResolver(), uri), (d, info, s) -> {
                    d.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    int side = Math.min(info.getSize().getWidth(), info.getSize().getHeight());
                    if (side > 1024) d.setTargetSampleSize(side / 1024); // big photos: no need for more than the icon shows
                });
                byte[] png = iconPng(picture);
                File tmp = new File(getFilesDir(), "clone_icon.png.part");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(png);
                }
                if (!tmp.renameTo(cloneIconFile())) throw new java.io.IOException("Couldn't save the icon.");
                done("Icon chosen. It's used the next time you install the clone.");
            });
        } else if (request == PICK_APKM) {
            work("Copying the APKM", () -> {
                File tmp = new File(getFilesDir(), "spotify.apkm.part");
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
                    Bundle.copy(in, out);
                }
                if (!tmp.renameTo(install.apkm())) throw new java.io.IOException("Couldn't save the APKM.");
                prefs.edit().putBoolean(DOWNLOADED, false).apply();
                install.replaceImport = false;
                done("APKM saved. It's checked when you install.");
            });
        } else if (request == EXPORT_KEY) {
            password("Protect the exported key", "Export", pass -> work("Exporting the key", () -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    keys.write(out, pass);
                }
                done("Key exported. Keep it and its password somewhere safe.");
            }));
        } else if (request == IMPORT_KEY) {
            password("Password of the key file", "Import", pass -> new AlertDialog.Builder(this)
                .setTitle("Replace this phone's key?")
                .setMessage("Spatch installed with the current key won't update until it's reinstalled with the new one.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace", (d, w) -> work("Importing the key", () -> {
                    Keys k;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        k = Keys.read(in, pass);
                    } catch (java.security.GeneralSecurityException | java.io.IOException e) {
                        throw new java.io.IOException("Couldn't read that key: the password is wrong, or the file uses encryption this phone can't read (see the manager README to re-export it).", e);
                    }
                    k.save(getFilesDir());
                    main.post(() -> keys = k);
                    done("Key imported.");
                }))
                .show());
        }
    }

    private void patch() {
        patch(null);
    }

    /** Installs Spatch, or with a clone, a second Spatch under the clone's package. */
    private void patch(Clone clone) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            say("Allow Spatch Manager to install apps, then tap Install again.");
            return;
        }
        final Releases.Release release = releases.bundle(nightly.isChecked());
        final String tag = release.tag;
        conflict = false;
        work("Getting the patches", () -> {
            List<File> signed = install.build(release, clone, keys);
            step("Installing", 1, 1);
            String key = clone == null ? INSTALLED : CLONE_INSTALLED + clone.pkg;
            prefs.edit().putString(PENDING_KEY, key).putString(PENDING_TAG, tag).putLong(PENDING_AT, System.currentTimeMillis()).commit();
            main.post(() -> {
                pendingTag = tag;
                pendingKey = key;
                pendingDone = clone == null ? "Spatch is installed. Open Spotify to use it." : clone.label + " is installed. Find it in your apps.";
                installingSelf = false;
            });
            Installer.install(this, signed, install.inheriting ? SPOTIFY : null);
        }, true);
    }

    /** An invite shared to the manager (long-press the link, Share, Spatch Manager) is saved as it is. */
    private void shared(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("https?://\\S+").matcher(text == null ? "" : text);
        text = link.find() ? link.group() : null; // chat apps share the link with words around it
        Sources.Invite shared = Sources.parse(text);
        if (shared == null) {
            say("That isn't an invite link. It looks like https://…/index.json#name.code.");
            return;
        }
        prefs.edit().putString(INVITE, text.trim()).apply();
        say("Invite saved. Install Spatch downloads Spotify from " + shared.host() + " when it needs to.");
        refresh();
    }

    /** Paste, replace or remove the invite; the clipboard's is offered when it holds one. */
    private void editInvite() {
        String current = prefs.getString(INVITE, null);
        String pasted = clipboardText();
        String initial = current != null ? current : pasted != null && Sources.parse(pasted) != null ? pasted : null;
        prompt("Invite to a Spatch source",
            "Whoever runs the source gives you this link. With it, Install Spatch downloads Spotify for you.",
            "https://…/index.json#name.code", false, initial, "Save", val -> {
                if (Sources.parse(val) == null) {
                    say("That isn't an invite link. It looks like https://…/index.json#name.code.");
                    return;
                }
                prefs.edit().putString(INVITE, val).apply();
                say("Invite saved. Install Spatch downloads Spotify from " + install.invite().host() + " when it needs to.");
                refresh();
            }, current == null ? null : () -> {
                prefs.edit().remove(INVITE).apply();
                say("Invite removed.");
                refresh();
            });
    }

    private Releases.Release managerUpdateTarget() {
        if (releases == null) return null;
        if (nightly.isChecked() && releases.nightlyManager != null) {
            String sha = releases.nightlyManager.tag.substring("nightly-".length());
            if (!ownName().endsWith("-" + sha)) return releases.nightlyManager;
        }
        return Releases.code(releases.manager) > ownCode() ? releases.manager : null;
    }

    private void updateManager() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        final Releases.Release release = managerUpdateTarget();
        if (release == null) return;
        work("Downloading the manager", () -> {
            File apk = new File(getCacheDir(), "manager.apk");
            Releases.download(release.url, release.size, apk, this::step, "Downloading the manager");
            main.post(() -> installingSelf = true);
            Installer.install(this, java.util.Collections.singletonList(apk), null);
        }, true);
    }

    private interface Job {
        void run() throws Exception;
    }

    private void work(String what, Job job) {
        work(what, job, false);
    }

    /** Runs job off the main thread; an install keeps the busy state until its result arrives. */
    private void work(String what, Job job, boolean install) {
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        say(what + "…");
        stepTracker.setStage(0);
        stepTracker.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        refresh();
        worker.execute(() -> {
            try {
                job.run();
                if (install) main.post(() -> {
                    progress.setVisibility(View.GONE);
                    stepTracker.setStage(3);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    say("Waiting for the installer…");
                });
            } catch (Exception e) {
                done(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    private void step(String what, int done, int total) {
        main.post(() -> {
            say(what + "…");
            int stage = 0;
            if (what.contains("Patch") || what.contains("base.apk") || what.contains("Preparing")) stage = 1;
            else if (what.contains("Sign")) stage = 2;
            else if (what.contains("Install") || what.contains("Waiting")) stage = 3;
            stepTracker.setStage(stage);
            stepTracker.setVisibility(View.VISIBLE);
            progress.setIndeterminate(false);
            progress.setMax(total);
            progress.setProgress(done);
        });
    }

    private void done(String message) {
        main.post(() -> {
            busy = false;
            progress.setVisibility(View.GONE);
            stepTracker.setVisibility(View.GONE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            say(message);
            refresh();
        });
    }

    private void password(String title, String action, java.util.function.Consumer<char[]> then) {
        prompt(title, null, null, true, null, action, val -> {
            if (val.isEmpty()) say("A password is needed.");
            else then.accept(val.toCharArray());
        }, null);
    }

    private String clipboardText() {
        android.content.ClipboardManager clips = getSystemService(android.content.ClipboardManager.class);
        android.content.ClipData clip = clips == null ? null : clips.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
        return text == null ? null : text.toString().trim();
    }

    private void prompt(String title, String message, String hint, boolean secret, String initial,
            String action, java.util.function.Consumer<String> onOk, Runnable onRemove) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        if (hint != null) field.setHint(hint);
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (initial != null) field.setText(initial);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(look.dp(24), look.dp(8), look.dp(24), 0);
        box.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton(action, (d, w) -> onOk.accept(secret && message == null ? field.getText().toString() : field.getText().toString().trim()));
        if (message != null) dialog.setMessage(message);
        if (onRemove != null) dialog.setNeutralButton("Remove", (d, w) -> onRemove.run());
        dialog.show();
    }

    PackageInfo spotify() {
        return installed(SPOTIFY);
    }

    boolean signedByUs(PackageInfo info) {
        return Install.signedByUs(info, keys);
    }

    private String ownName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

        private int ownCode() {
        try {
            return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** An installed tag as read: the newest nightly's commit instead of its patch fingerprint. */
    private String shownPatches(String installed) {
        Releases.Release n = releases == null ? null : releases.nightly;
        if (n != null && n.commit != null && installed.startsWith(n.tag)) return "nightly " + n.commit + installed.substring(n.tag.length());
        return installed.replaceFirst("^(patches-v|bundle-)", "");
    }

    static void delete(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) delete(c);
        f.delete();
    }

    void say(String message) {
        status.setText(message);
        statusCard.setVisibility(message.isEmpty() && progress.getVisibility() != View.VISIBLE ? View.GONE : View.VISIBLE);
    }
}
