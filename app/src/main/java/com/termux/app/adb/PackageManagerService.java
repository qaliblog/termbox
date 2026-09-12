package com.termux.app.adb;

import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * `cmd package install-*` session emulation backed by the real Android
 * PackageInstaller API (API 21+, every device TermBox runs on).
 *
 * The streamed-install line protocol matches real `cmd package`:
 *   create  -> "Success [<sessionId>]" or "Error: ..." (+ "Exception: ...")
 *   write   -> "Success" / "Error: ..."
 *   commit  -> "Success" / "Error: ..." (honest INSTALL_FAILED_* text)
 *   abandon -> "Success" / "Error: ..."
 *   uninstall -> "Success" / "Error: ..."
 *
 * On a user build, installation still requires the standard user consent
 * dialog and possibly REQUEST_INSTALL_PACKAGES; when Android refuses, the
 * genuine error text is streamed back and the client exits non-zero. No
 * security bypass is attempted anywhere in this class.
 */
final class PackageManagerService {

    private static final String LOG_TAG = TermboxAdbBridge.LOG_TAG;

    /** Install sessions keyed by id; the bridge serves one client at a time. */
    private static final Map<Integer, SessionState> sSessions = new HashMap<>();

    /**
     * Entry point for `exec:cmd package install-...` streams. Writes the
     * protocol output to `out`, consuming `in` for install-write payloads.
     */
    static void handleCmdPackage(String args, InputStream in, OutputStream out) {
        // args excludes the leading "cmd "; tokens[0] = "package".
        String[] tokens = args.trim().split("\\s+");
        String sub = tokens.length > 1 ? tokens[1] : "";

        switch (sub) {
            case "install-create":
                handleCreate(tokens, out);
                break;
            case "install-write":
                handleWrite(tokens, in, out);
                break;
            case "install-commit":
                handleCommit(tokens, out);
                break;
            case "install-abandon":
                handleAbandon(tokens, out);
                break;
            case "uninstall":
                handleUninstall(tokens, out);
                break;
            default:
                writeLine(out, "Error: unknown command " + (sub.isEmpty() ? "(none)" : sub));
                break;
        }
    }

    private static void handleCreate(String[] tokens, OutputStream out) {
        long size = -1;
        for (int i = 0; i < tokens.length; i++) {
            if ("-S".equals(tokens[i]) && i + 1 < tokens.length) {
                try {
                    size = Long.parseLong(tokens[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (size < 0) {
            writeLine(out, "Error: must specify APK size with -S");
            writeException(out, null);
            return;
        }
        if (!PackageInstallerBridge.isSupported()) {
            writeLine(out, "Error: PackageInstaller not available on this platform");
            writeException(out, null);
            return;
        }
        try {
            int id = PackageInstallerBridge.createSession(size);
            synchronized (sSessions) {
                sSessions.put(id, new SessionState(size));
            }
            writeLine(out, "Success [" + id + "]");
        } catch (Exception e) {
            // Honest failure: surface what Android actually said.
            writeLine(out, "Error: " + e.getMessage());
            writeException(out, e);
        }
    }

    private static void handleWrite(String[] tokens, InputStream in, OutputStream out) {
        int id = -1;
        long size = -1;
        String name = "base.apk";
        for (int i = 0; i < tokens.length; i++) {
            if ("-S".equals(tokens[i]) && i + 1 < tokens.length) {
                try {
                    size = Long.parseLong(tokens[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            } else if (tokens[i].matches("\\d+")) {
                id = Integer.parseInt(tokens[i]);
            } else if (!"-".equals(tokens[i]) && !"install-write".equals(tokens[i])
                && !"package".equals(tokens[i]) && !tokens[i].startsWith("-")
                && !tokens[i].equals(name)) {
                name = tokens[i];
            }
        }
        SessionState session;
        synchronized (sSessions) {
            session = sSessions.get(id);
        }
        if (session == null) {
            writeLine(out, "Error: session " + id + " not found");
            writeException(out, null);
            return;
        }
        try {
            long written = PackageInstallerBridge.writeSession(id, in, size);
            session.mBytesWritten = written;
            writeLine(out, "Success");
        } catch (Exception e) {
            writeLine(out, "Error: " + e.getMessage());
            writeException(out, e);
        }
    }

    private static void handleCommit(String[] tokens, OutputStream out) {
        int id = findSessionId(tokens);
        SessionState session;
        synchronized (sSessions) {
            session = sSessions.get(id);
        }
        if (session == null) {
            writeLine(out, "Error: session " + id + " not found");
            writeException(out, null);
            return;
        }
        try {
            // The real Android installer flow runs here: user consent dialog,
            // signature checks, INSTALL_FAILED_* verdicts. Everything Android
            // rejects is streamed back as the genuine Error text.
            PackageInstallerBridge.commitSession(id);
            writeLine(out, "Success");
            synchronized (sSessions) {
                sSessions.remove(id);
            }
        } catch (Exception e) {
            writeLine(out, "Error: " + e.getMessage());
            writeException(out, e);
            synchronized (sSessions) {
                sSessions.remove(id);
            }
        }
    }

    private static void handleAbandon(String[] tokens, OutputStream out) {
        int id = findSessionId(tokens);
        boolean exists;
        synchronized (sSessions) {
            exists = sSessions.containsKey(id);
        }
        if (!exists) {
            writeLine(out, "Error: session " + id + " not found");
            writeException(out, null);
            return;
        }
        try {
            PackageInstallerBridge.abandonSession(id);
        } catch (Exception ignored) {
        }
        synchronized (sSessions) {
            sSessions.remove(id);
        }
        writeLine(out, "Success");
    }

    private static void handleUninstall(String[] tokens, OutputStream out) {
        boolean keepData = false;
        String pkg = null;
        for (String t : tokens) {
            if ("-k".equals(t)) keepData = true;
            else if (!"package".equals(t) && !"uninstall".equals(t)
                && !t.startsWith("-")) pkg = t;
        }
        if (pkg == null) {
            writeLine(out, "Error: package name required");
            writeException(out, null);
            return;
        }
        try {
            PackageInstallerBridge.uninstall(pkg, keepData);
            writeLine(out, "Success");
        } catch (Exception e) {
            writeLine(out, "Error: " + e.getMessage());
            writeException(out, e);
        }
    }

    private static int findSessionId(String[] tokens) {
        for (String t : tokens) {
            if (t.matches("\\d+")) return Integer.parseInt(t);
        }
        return -1;
    }

    private static void writeLine(OutputStream out, String line) {
        try {
            out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    /** "Exception: <class>" line, like real cmd package on a failed op. */
    private static void writeException(OutputStream out, Exception e) {
        writeLine(out, "Exception: " + (e != null ? e.getClass().getName() : "java.lang.Exception"));
    }

    /** Per-session bookkeeping. */
    private static final class SessionState {
        final long mDeclaredSize;
        long mBytesWritten;

        SessionState(long declaredSize) {
            mDeclaredSize = declaredSize;
        }
    }

    // Keep the unused-import warning away for Build, which documents intent.
    @SuppressWarnings("unused")
    private static final int UNUSED_BUILD_REF = Build.VERSION.SDK_INT;
}
