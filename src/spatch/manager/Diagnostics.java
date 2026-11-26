package spatch.manager;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class Diagnostics {
    private final MainActivity activity;
    private final Look look;
    private TextView diagnosticsText, exitsText;
    Button runDiagnosticsBtn, copyDiagnosticsBtn, refreshExitsBtn;
    private Button openBatterySettingsBtn, openAppDetailsBtn, launchSpotifyBtn;

    Diagnostics(MainActivity activity, Look look) {
        this.activity = activity;
        this.look = look;
    }

    void layout(LinearLayout page) {
        TextView note = look.text("Verify Spatch installation health, keystore signatures, split APK architecture, and inspect system crash reports.", 15, look.onSurfaceVariant, false);
        note.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        page.addView(note);

        LinearLayout testsCard = look.card(page, "Diagnostics & Self-Tests");
        runDiagnosticsBtn = look.button("Run self-tests", "tune", Look.Kind.FILLED, v -> runDiagnostics());
        copyDiagnosticsBtn = look.button("Copy report", "content_copy", Look.Kind.TONAL, v -> copyDiagnostics());
        testsCard.addView(look.row(runDiagnosticsBtn, copyDiagnosticsBtn));
        diagnosticsText = look.text("Tap 'Run self-tests' to verify signing keys, split packages, and system permissions.", 13, look.onSurfaceVariant, false);
        diagnosticsText.setTypeface(android.graphics.Typeface.MONOSPACE);
        diagnosticsText.setPadding(look.dp(12), look.dp(12), look.dp(12), look.dp(12));
        diagnosticsText.setBackground(look.shape(look.containerHigh, 16, Look.blend(look.containerHigh, look.outline, 0.22f), 1f));
        testsCard.addView(diagnosticsText, look.spaced(10));

        LinearLayout exitsCard = look.card(page, "Spotify Process Exits & Crashes");
        TextView exitsNote = look.text("OS-level historical process terminations from ActivityManager.", 14, look.onSurfaceVariant, false);
        exitsCard.addView(exitsNote);
        exitsText = look.text("Loading process history…", 13, look.onSurfaceVariant, false);
        exitsText.setTypeface(android.graphics.Typeface.MONOSPACE);
        exitsText.setPadding(look.dp(12), look.dp(12), look.dp(12), look.dp(12));
        exitsText.setBackground(look.shape(look.containerHigh, 16, Look.blend(look.containerHigh, look.outline, 0.22f), 1f));
        exitsCard.addView(exitsText, look.spaced(10));
        refreshExitsBtn = look.button("Refresh crash history", "refresh", Look.Kind.TONAL, v -> updateExitReasons());
        exitsCard.addView(refreshExitsBtn, look.spaced(10));

        LinearLayout shortcutsCard = look.card(page, "System Settings & Shortcuts");
        openBatterySettingsBtn = look.button("Battery whitelist", "tune", Look.Kind.TONAL, v -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                activity.say("Settings unavailable.");
            }
        });
        openAppDetailsBtn = look.button("Spotify details", "open_in_new", Look.Kind.TONAL, v -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + MainActivity.SPOTIFY)));
            } catch (Exception e) {
                activity.say("App details unavailable.");
            }
        });
        launchSpotifyBtn = look.button("Launch Spotify", "music_note", Look.Kind.TONAL, v -> {
            Intent i = activity.getPackageManager().getLaunchIntentForPackage(MainActivity.SPOTIFY);
            if (i != null) activity.startActivity(i);
            else activity.say("Spotify isn't installed.");
        });
        shortcutsCard.addView(look.row(openBatterySettingsBtn, openAppDetailsBtn));
        shortcutsCard.addView(launchSpotifyBtn, look.spaced(8));
    }

    private void runDiagnostics() {
        diagnosticsText.setText("Running self-tests…");
        activity.worker.execute(() -> {
            StringBuilder sb = new StringBuilder();
            int pass = 0, warn = 0, fail = 0;

            if (activity.keys != null) {
                pass++;
                sb.append("PASS Phone Signing Keystore\n");
                try {
                    sb.append("     Fingerprint: ").append(activity.keys.fingerprint().substring(0, 32)).append("\n");
                } catch (Exception e) {
                    sb.append("     Fingerprint: unreadable\n");
                }
            } else {
                fail++;
                sb.append("FAIL Phone Signing Keystore: missing or unreadable\n");
            }

            PackageInfo info = activity.spotify();
            if (info != null) {
                boolean ours = activity.keys != null && activity.signedByUs(info);
                if (ours) {
                    pass++;
                    sb.append("PASS Spotify Signature Match\n");
                    sb.append("     Installed: Spotify ").append(info.versionName).append(" (signed with phone key)\n");
                } else {
                    warn++;
                    sb.append("WARN Spotify Signature Mismatch\n");
                    sb.append("     Installed with different key; import earlier key or reinstall to update\n");
                }
                if (info.applicationInfo != null && info.applicationInfo.splitNames != null && info.applicationInfo.splitNames.length > 0) {
                    pass++;
                    sb.append("PASS Split APK Architecture\n");
                    sb.append("     ").append(info.applicationInfo.splitNames.length).append(" splits installed: ").append(String.join(", ", info.applicationInfo.splitNames)).append("\n");
                } else {
                    warn++;
                    sb.append("WARN No Split APKs detected (monolithic base)\n");
                }
            } else {
                sb.append("INFO Spotify is not currently installed\n");
            }

            File base = activity.install.originalBase();
            if (base.isFile()) {
                pass++;
                sb.append("PASS Preserved Original base.apk\n");
                sb.append("     ").append(base.length() / (1 << 20)).append(" MB cached for fast updates\n");
            } else {
                sb.append("INFO Original base.apk not cached yet\n");
            }

            File apkm = activity.install.apkm();
            if (apkm.isFile()) {
                pass++;
                sb.append("PASS Spotify APKM Saved\n");
                sb.append("     ").append(apkm.length() / (1 << 20)).append(" MB available\n");
            } else {
                sb.append("INFO No APKM cached on device\n");
            }

            if (activity.getPackageManager().canRequestPackageInstalls()) {
                pass++;
                sb.append("PASS App Install Permission: Allowed\n");
            } else {
                fail++;
                sb.append("FAIL App Install Permission: Not granted\n");
            }

            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                pass++;
                sb.append("PASS Battery Optimization: Unrestricted (background checks active)\n");
            } else {
                warn++;
                sb.append("WARN Battery Optimization: Optimized (Android may delay background checks)\n");
            }

            if (Build.VERSION.SDK_INT >= 33) {
                if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
                    pass++;
                    sb.append("PASS Notifications: Granted\n");
                } else {
                    warn++;
                    sb.append("WARN Notifications: Not granted\n");
                }
            }

            if (activity.releases != null && activity.releases.bundle != null) {
                pass++;
                sb.append("PASS Releases API: Connected\n");
                sb.append("     Latest version: ").append(Releases.version(activity.releases.bundle)).append("\n");
            } else if (activity.releaseError != null) {
                warn++;
                sb.append("WARN Releases API: ").append(activity.releaseError).append("\n");
            }

            sb.append("\nResults: ").append(pass).append(" passed, ").append(warn).append(" warnings, ").append(fail).append(" failed");
            String resultText = sb.toString();
            activity.main.post(() -> {
                if (diagnosticsText != null) diagnosticsText.setText(look.verdicts(resultText));
            });
        });
    }

    private void copyDiagnostics() {
        android.content.ClipboardManager clips = activity.getSystemService(android.content.ClipboardManager.class);
        if (clips != null && diagnosticsText != null) {
            clips.setPrimaryClip(android.content.ClipData.newPlainText("Spatch Diagnostics", diagnosticsText.getText()));
            activity.say("Diagnostics report copied to clipboard.");
        }
    }

    private void updateExitReasons() {
        if (Build.VERSION.SDK_INT < 30) {
            if (exitsText != null) exitsText.setText("Historical process exit monitoring requires Android 11+ (API 30).");
            return;
        }
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                if (exitsText != null) exitsText.setText("ActivityManager is not available.");
                return;
            }
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(MainActivity.SPOTIFY, 0, 8);
            if (exits == null || exits.isEmpty()) {
                if (exitsText != null) exitsText.setText("No process exits or crashes recorded by the OS for Spotify.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            for (ApplicationExitInfo exit : exits) {
                String reasonStr;
                switch (exit.getReason()) {
                    case ApplicationExitInfo.REASON_CRASH: reasonStr = "CRASH (Java Exception)"; break;
                    case ApplicationExitInfo.REASON_CRASH_NATIVE: reasonStr = "NATIVE CRASH (Signal)"; break;
                    case ApplicationExitInfo.REASON_ANR: reasonStr = "ANR (Freeze / Not Responding)"; break;
                    case ApplicationExitInfo.REASON_LOW_MEMORY: reasonStr = "LOW MEMORY (LMK)"; break;
                    case ApplicationExitInfo.REASON_USER_REQUESTED: reasonStr = "USER REQUESTED (Force stop / Swiped)"; break;
                    case ApplicationExitInfo.REASON_EXIT_SELF: reasonStr = "EXIT SELF (Clean exit)"; break;
                    default: reasonStr = "REASON #" + exit.getReason(); break;
                }
                sb.append("• ").append(sdf.format(new Date(exit.getTimestamp()))).append("\n");
                sb.append("  ").append(reasonStr).append(" (PID ").append(exit.getPid()).append(")\n");
                String desc = exit.getDescription();
                if (desc != null && !desc.trim().isEmpty()) {
                    sb.append("  ").append(desc.trim()).append("\n");
                }
                sb.append("\n");
            }
            if (exitsText != null) exitsText.setText(sb.toString().trim());
        } catch (SecurityException e) {
            if (exitsText != null) {
                exitsText.setText("Cross-app exit history requires android.permission.DUMP.\n\n"
                    + "To grant via ADB:\n"
                    + "adb shell pm grant spatch.manager android.permission.DUMP");
            }
        } catch (Exception e) {
            if (exitsText != null) exitsText.setText("Couldn't read exit history: " + e.getMessage());
        }
    }
}
