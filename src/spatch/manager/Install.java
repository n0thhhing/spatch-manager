package spatch.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Install {
    private final Context context;
    private final SharedPreferences prefs;
    private final Bundle.Progress progress;
    volatile boolean replaceImport; // the chosen APKM was the wrong one and the user tapped Install again
    volatile boolean inheriting, inheritFailed; // an update replacing base.apk alone; a failed one goes back to all five

    Install(Context context, SharedPreferences prefs, Bundle.Progress progress) {
        this.context = context;
        this.prefs = prefs;
        this.progress = progress;
    }

    /** Patches and signs the bundle's output for the Spotify install (or the clone), ready for the installer. */
    List<File> build(Releases.Release release, Clone clone, Keys keys) throws Exception {
        File bundle = new File(context.getCacheDir(), release.tag + ".zip");
        File dir = new File(context.getCacheDir(), "build"), signedDir = new File(dir, "signed");
        List<File> unsigned;
        try (Bundle b = Releases.openBundle(release.url, release.size, bundle, progress)) {
            File[] old = context.getCacheDir().listFiles((parent, name) -> name.endsWith(".zip") && !name.equals(bundle.getName()));
            if (old != null) for (File f : old) f.delete();
            MainActivity.delete(dir);
            signedDir.mkdirs();
            if (clone != null && !b.clonable()) throw new java.io.IOException("These patches can't make a clone yet; clones need patches from Spatch 0.8.1 or later.");
            inheriting = clone == null && keepsSplits(b, keys);
            File input = input(b);
            unsigned = input != null ? b.apply(input, dir, inheriting, clone, progress) : fromInstalled(b, dir, inheriting, clone);
        }
        List<File> signed = new ArrayList<>();
        for (int i = 0; i < unsigned.size(); i++) {
            File in = unsigned.get(i), out = new File(signedDir, in.getName());
            progress.step("Signing " + in.getName(), i, unsigned.size());
            keys.sign(in, out);
            in.delete();
            signed.add(out);
        }
        return signed;
    }

    /**
     * The APKM to patch, or null to patch the installed Spotify instead. In order: a saved APKM
     * that matches the bundle, an unmodified installed Spotify of the right version (or its kept
     * original), then a download from the invite's source. A chosen APKM that doesn't match is never
     * replaced silently: the first tap explains, a second one downloads over it.
     */
    private File input(spatch.manager.Bundle b) throws Exception {
        File saved = apkm();
        Sources.Invite source = invite();
        if (saved.isFile()) {
            progress.step("Checking the saved APKM", 0, 1);
            if (spatch.manager.Bundle.sha256(saved).equals(b.apkmSha)) return saved;
            if (!prefs.getBoolean(MainActivity.DOWNLOADED, false) && !replaceImport) {
                if (source == null) throw new java.io.IOException("The APKM you chose isn't the Spotify " + b.spotify + " one these patches need. Choose that one.");
                replaceImport = true;
                throw new java.io.IOException("The APKM you chose isn't the Spotify " + b.spotify + " one these patches need. Tap Install again to download the right one from " + source.host() + " instead.");
            }
        }
        if (installedOriginal(b)) return null;
        if (source == null) throw new java.io.IOException("Choose the Spotify " + b.spotify + " APKM, or add an invite to a Spatch source.");
        progress.step("Asking " + source.host() + " for Spotify " + b.spotify, 0, 1);
        Sources.Entry entry = Sources.find(source, b);
        Sources.download(source, entry, saved, progress);
        prefs.edit().putBoolean(MainActivity.DOWNLOADED, true).commit();
        replaceImport = false;
        return saved;
    }

    /** The installed Spotify is the right version and its original base.apk is at hand, kept or still installed. */
    private boolean installedOriginal(spatch.manager.Bundle b) throws java.io.IOException {
        PackageInfo info = spotify();
        if (info == null || info.getLongVersionCode() != b.spotifyCode) return false;
        if (originalBase().isFile()) return true;
        progress.step("Checking the installed Spotify", 0, 1);
        return spatch.manager.Bundle.sha256(new File(info.applicationInfo.sourceDir)).equals(b.baseSha());
    }

    Sources.Invite invite() {
        return Sources.parse(prefs.getString(MainActivity.INVITE, null));
    }

    /** Our own Spatch of the same Spotify version is installed, so its splits (never patched) can stay. */
    private boolean keepsSplits(spatch.manager.Bundle b, Keys keys) {
        PackageInfo info = spotify();
        return !inheritFailed && info != null && keys != null && signedByUs(info, keys) && info.getLongVersionCode() == b.spotifyCode
            && info.applicationInfo.splitNames != null && info.applicationInfo.splitNames.length > 0;
    }

    /**
     * No APKM: the original base.apk kept from an earlier install, or else the installed Spotify's
     * own when it is still unmodified; the splits always come from the installed Spotify.
     */
    private List<File> fromInstalled(spatch.manager.Bundle b, File dir, boolean baseOnly, Clone clone) throws Exception {
        PackageInfo info = spotify();
        if (info == null) throw new java.io.IOException("Choose the Spotify " + b.spotify + " APKM first.");
        if (info.getLongVersionCode() != b.spotifyCode) {
            throw new java.io.IOException("The Spotify on this phone is version " + info.versionName + ", and Spatch needs " + b.spotify + ". Choose the " + b.spotify + " APKM.");
        }
        File kept = originalBase();
        if (!kept.isFile()) {
            File base = new File(info.applicationInfo.sourceDir);
            progress.step("Checking the installed Spotify", 0, 1);
            if (!spatch.manager.Bundle.sha256(base).equals(b.baseSha())) {
                throw new java.io.IOException("The Spotify on this phone is already patched, so it has no original to patch. Choose the Spotify " + b.spotify + " APKM once; the manager keeps what it needs for later updates.");
            }
            File tmp = new File(context.getFilesDir(), "original-base.apk.part");
            try (InputStream in = new java.io.FileInputStream(base); OutputStream out = new FileOutputStream(tmp)) {
                spatch.manager.Bundle.copy(in, out);
            }
            if (!tmp.renameTo(kept)) throw new java.io.IOException("Couldn't keep the original Spotify.");
        }
        List<File> splits = new ArrayList<>();
        if (!baseOnly && info.applicationInfo.splitSourceDirs != null) for (String path : info.applicationInfo.splitSourceDirs) splits.add(new File(path));
        return b.apply(kept, splits, dir, clone, progress);
    }

    File apkm() {
        return new File(context.getFilesDir(), "spotify.apkm");
    }

    /** The unmodified base.apk, kept so updates need neither an APKM nor a stock install. */
    File originalBase() {
        return new File(context.getFilesDir(), "original-base.apk");
    }

    PackageInfo installed(String pkg) {
        try {
            return context.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private PackageInfo spotify() {
        return installed(MainActivity.SPOTIFY);
    }

    static boolean signedByUs(PackageInfo info, Keys keys) {
        try {
            if (info.signingInfo == null) return false;
            byte[] ours = keys.cert.getEncoded();
            for (Signature s : info.signingInfo.getApkContentsSigners()) if (Arrays.equals(s.toByteArray(), ours)) return true;
        } catch (Exception ignored) {
            // unreadable certificate: treat as someone else's
        }
        return false;
    }
}
