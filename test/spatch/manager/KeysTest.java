package spatch.manager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Verifies Keys.save keeps the previous signing.p12 as signing.p12.prev and overwrites older backups.
 *
 *   javac -d /tmp/kt -cp <android.jar>:<apksig.jar> src/spatch/manager/{Bundle,Clone,Keys,Releases}.java test/spatch/manager/KeysTest.java
 *   java -cp /tmp/kt:<apksig.jar> spatch.manager.KeysTest
 */
public final class KeysTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("keys").toFile();
        Keys first = Keys.loadOrCreate(dir);
        File current = new File(dir, "signing.p12"), prev = new File(dir, "signing.p12.prev");
        check(current.isFile() && !prev.exists(), "initial key has no previous backup");

        Keys second = generate();
        second.save(dir);
        check(Keys.loadOrCreate(dir).fingerprint().equals(second.fingerprint()), "second key becomes active");
        check(prev.isFile() && readInternal(prev).fingerprint().equals(first.fingerprint()), "first key is kept as signing.p12.prev");

        Keys third = generate();
        third.save(dir);
        check(Keys.loadOrCreate(dir).fingerprint().equals(third.fingerprint()), "third key becomes active");
        check(readInternal(prev).fingerprint().equals(second.fingerprint()), "older signing.p12.prev is overwritten by second key");

        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static Keys generate() throws Exception {
        File tmp = Files.createTempDirectory("keys-gen").toFile();
        return Keys.loadOrCreate(tmp);
    }

    private static Keys readInternal(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return Keys.read(in, "spatch-manager".toCharArray());
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }
}
