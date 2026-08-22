package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();



    /*
     * TermBox offline runtime installation.
     * These methods handle extraction of bundled Ubuntu rootfs, proot-distro,
     * Box64, and other offline runtime components from the APK assets.
     */

    private static final String TERMBOX_ASSETS_DIR = "termbox-runtime";
    private static final String TERMBOX_VERSION_FILE = TERMBOX_ASSETS_DIR + "/config/termbox-version.json";
    private static final String TERMBOX_BOOTSTRAP_DIR = TERMBOX_ASSETS_DIR + "/bootstrap";
    private static final String TERMBOX_PROOT_DIR = TERMBOX_ASSETS_DIR + "/proot";
    private static final String TERMBOX_UBUNTU_DIR = TERMBOX_ASSETS_DIR + "/ubuntu";
    private static final String TERMBOX_BOX64_DIR = TERMBOX_ASSETS_DIR + "/box64";
    private static final String TERMBOX_PROOT_DISTRO_DIR = TERMBOX_ASSETS_DIR + "/proot-distro";
    private static final String TERMBOX_CONFIG_DIR = TERMBOX_ASSETS_DIR + "/config";

    /**
     * Check if TermBox offline runtime assets are bundled in the APK.
     */
    public static boolean hasTermBoxAssets(Context context) {
        try {
            String[] assets = context.getAssets().list(TERMBOX_ASSETS_DIR);
            return assets != null && assets.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the TermBox environment version from bundled assets.
     */
    public static String getTermBoxVersion(Context context) {
        try {
            java.io.InputStream is = context.getAssets().open(TERMBOX_VERSION_FILE);
            byte[] data = new byte[is.available()];
            is.read(data);
            is.close();
            String json = new String(data);
            // Simple JSON parsing - extract termbox_version value
            int idx = json.indexOf("\"termbox_version\"");
            if (idx > 0) {
                int colonIdx = json.indexOf(":", idx);
                int quoteStart = json.indexOf("\"", colonIdx + 1);
                int quoteEnd = json.indexOf("\"", quoteStart + 1);
                return json.substring(quoteStart + 1, quoteEnd);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read TermBox version: " + e.getMessage());
        }
        return "unknown";
    }

    /**
     * Install TermBox offline runtime components.
     * This extracts Ubuntu rootfs, proot-distro, Box64, and configuration
     * files from the APK assets to the app's data directory.
     *
     * This must be called during first-run initialization to ensure
     * the device can operate completely offline.
     */
    public static void installTermBoxRuntime(Activity activity, Runnable whenDone) {
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing TermBox offline runtime...");

                    Context context = activity.getApplicationContext();
                    File filesDir = context.getFilesDir();

                    // Note: proot binary is already provided by the Termux bootstrap packages.
                    // No need to extract it separately.

                    // Extract Box64 binary
                    extractAsset(context, TERMBOX_BOX64_DIR + "/box64",
                        new File(filesDir, "usr/bin/box64"));

                    // Extract proot-distro scripts
                    extractAssetDirectory(context, TERMBOX_PROOT_DISTRO_DIR,
                        new File(filesDir, "usr/share/proot-distro"));

                    // Extract Ubuntu rootfs (large file - stored compressed)
                    File ubuntuDir = new File(filesDir, "ubuntu-root");
                    if (!ubuntuDir.exists()) {
                        ubuntuDir.mkdirs();
                        // The Ubuntu rootfs tar.xz is stored in assets and extracted on first run
                        // This is done streaming to avoid loading the entire archive into RAM
                        extractUbuntuRootfs(context, ubuntuDir);
                    }

                    // Extract TermBox configuration scripts
                    extractAssetDirectory(context, TERMBOX_CONFIG_DIR,
                        new File(filesDir, "usr/share/termbox/config"));

                    // Install TermBox shell scripts into $PREFIX/bin/
                    // These are the entry points for Ubuntu auto-login and host shell access
                    installTermBoxShellScripts(context, filesDir);

                    // Set executable permissions on binaries
                    // Note: proot permissions are already set by the Termux bootstrap.
                    setExecutable(new File(filesDir, "usr/bin/box64"));
                    setExecutable(new File(filesDir, "usr/bin/termbox-ubuntu"));
                    setExecutable(new File(filesDir, "usr/bin/termbox-host"));
                    setExecutable(new File(filesDir, "usr/bin/termbox-shell-wrapper"));
                    setExecutable(new File(filesDir, "usr/bin/termbox-exec"));

                    // Setup Box64 ELF detection wrapper
                    setupElfWrapper(filesDir);

                    Logger.logInfo(LOG_TAG, "TermBox offline runtime installed successfully.");

                    activity.runOnUiThread(whenDone);

                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "TermBox runtime installation failed: " + e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Installation Error", e);
                    showBootstrapErrorDialog(activity, whenDone,
                        Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                }
            }
        }.start();
    }

    /**
     * Extract a single asset file to a destination.
     */
    private static void extractAsset(Context context, String assetPath, File destFile) throws Exception {
        try {
            java.io.InputStream is = context.getAssets().open(assetPath);
            destFile.getParentFile().mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.close();
            is.close();
            Logger.logInfo(LOG_TAG, "Extracted asset: " + assetPath + " -> " + destFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to extract asset " + assetPath + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Extract an entire asset directory to a destination.
     */
    private static void extractAssetDirectory(Context context, String assetDir, File destDir) throws Exception {
        try {
            String[] files = context.getAssets().list(assetDir);
            if (files == null || files.length == 0) {
                // It's a file, not a directory - extract it
                extractAsset(context, assetDir, destDir);
                return;
            }
            destDir.mkdirs();
            for (String file : files) {
                String assetPath = assetDir + "/" + file;
                File destFile = new File(destDir, file);
                // Check if it's a file or directory
                String[] subFiles = context.getAssets().list(assetPath);
                if (subFiles != null && subFiles.length > 0) {
                    extractAssetDirectory(context, assetPath, destFile);
                } else {
                    extractAsset(context, assetPath, destFile);
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to extract directory " + assetDir + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Extract Ubuntu rootfs from bundled tar.xz assets.
     * Uses streaming extraction to avoid loading the entire archive into RAM.
     * The archive is extracted incrementally to handle large rootfs files.
     */
    private static void extractUbuntuRootfs(Context context, File destDir) throws Exception {
        Logger.logInfo(LOG_TAG, "Extracting Ubuntu rootfs to " + destDir.getAbsolutePath());

        // List Ubuntu rootfs assets
        String[] ubuntuAssets = context.getAssets().list(TERMBOX_UBUNTU_DIR);
        if (ubuntuAssets == null || ubuntuAssets.length == 0) {
            Logger.logError(LOG_TAG, "No Ubuntu rootfs assets found");
            return;
        }

        for (String asset : ubuntuAssets) {
            if (asset.endsWith(".tar.xz") || asset.endsWith(".tar.gz") || asset.endsWith(".tar")) {
                // Extract the tarball using streaming
                java.io.InputStream is = context.getAssets().open(TERMBOX_UBUNTU_DIR + "/" + asset);
                File tarFile = new File(destDir, asset);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tarFile);
                byte[] buffer = new byte[65536];
                int read;
                long total = 0;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    total += read;
                    if (total % (10 * 1024 * 1024) == 0) {
                        Logger.logInfo(LOG_TAG, "  Extracted " + (total / (1024 * 1024)) + " MB...");
                    }
                }
                fos.close();
                is.close();

                Logger.logInfo(LOG_TAG, "Ubuntu rootfs extracted: " + (total / (1024 * 1024)) + " MB");

                // Extract the tarball
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        "tar", "-xJf", tarFile.getAbsolutePath(),
                        "-C", destDir.getAbsolutePath(),
                        "--strip-components=0"
                    );
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()));
                    while (reader.readLine() != null) { /* consume output */ }
                    proc.waitFor();

                    // Clean up the tarball after successful extraction
                    tarFile.delete();
                    Logger.logInfo(LOG_TAG, "Ubuntu rootfs extracted successfully");
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Ubuntu rootfs tar extraction failed: " + e.getMessage());
                    Logger.logInfo(LOG_TAG, "Ubuntu rootfs tarball preserved at: " + tarFile.getAbsolutePath());
                }

                break; // Only extract one rootfs archive
            }
        }
    }

    /**
     * Set executable permission on a file.
     */
    private static void setExecutable(File file) {
        if (file.exists()) {
            file.setExecutable(true, false);
            file.setReadable(true, false);
        }
    }

    /**
     * Install TermBox shell scripts into $PREFIX/bin/.
     *
     * These scripts provide:
     * - termbox-ubuntu: Main entry point that launches Ubuntu via proot-distro
     * - termbox-host: Escape command to exit Ubuntu and return to native TermBox shell
     * - termbox-shell-wrapper: Default login shell that routes to Ubuntu automatically
     *
     * The termbox-shell-wrapper is placed at $PREFIX/bin/termbox-shell-wrapper and
     * is prioritized in the login shell selection, so new terminal sessions automatically
     * enter the Ubuntu ARM64 environment.
     *
     * To access the native TermBox shell, users can:
     * - Run 'termbox-host' from inside Ubuntu
     * - Use failsafe mode
     */
    private static void installTermBoxShellScripts(Context context, File filesDir) throws Exception {
        File binDir = new File(filesDir, "usr/bin");
        binDir.mkdirs();

        // Install termbox-ubuntu (main Ubuntu entry point)
        String ubuntuScript = "#!/bin/bash\n" +
            "# termbox-ubuntu - Enter Ubuntu ARM64 environment\n" +
            "# Copyright (c) TermBox Contributors - MIT License\n\n" +
            "TERMBOX_APP_DIR=\"${ANDROID_DATA:-/data/data/com.qali.termbox}\"\n" +
            "TERMBOX_PREFIX=\"${TERMBOX_APP_DIR}/files/usr\"\n" +
            "TERMBOX_HOME=\"${TERMBOX_APP_DIR}/files/home\"\n" +
            "TERMBOX_UROOT=\"${TERMBOX_APP_DIR}/files/ubuntu-root\"\n" +
            "PROOT=\"${TERMBOX_PREFIX}/bin/proot\"\n" +
            "BOX64=\"${TERMBOX_PREFIX}/bin/box64\"\n\n" +
            "# Check if already inside proot\n" +
            "if [ -f /etc/os-release ] && [ \"$(id -u 2>/dev/null)\" = \"0\" ]; then\n" +
            "  exec /bin/bash --login\n" +
            "fi\n\n" +
            "# Check if Ubuntu rootfs exists\n" +
            "if [ ! -d \"${TERMBOX_UROOT}\" ] || [ ! -f \"${TERMBOX_UROOT}/etc/os-release\" ]; then\n" +
            "  echo \"TermBox: Ubuntu rootfs not found. Falling back to native shell.\"\n" +
            "  exec \"${TERMBOX_PREFIX}/bin/bash\" --login\n" +
            "fi\n\n" +
            "# Export Box64 configuration\n" +
            "export BOX64_DYNAREC=1\n" +
            "export BOX64_DYNAREC_STRONGMEM=1\n" +
            "export BOX64_DYNAREC_BIGBLOCK=1\n" +
            "export BOX64_DYNAREC_SAFEFLAGS=1\n" +
            "export BOX64_DYNAREC_BLEEDING_EDGE=1\n" +
            "export BOX64_ENV=0\n" +
            "export BOX64_NOBANNER=0\n\n" +
            "# Launch Ubuntu via proot\n" +
            "exec \"${PROOT}\" \\\n" +
            "  --link2symlink --kill-on-exit --root-id --cwd /root \\\n" +
            "  -b /dev -b /proc -b /sys \\\n" +
            "  -b \"${TERMBOX_HOME}:/root\" \\\n" +
            "  -b \"${TERMBOX_HOME}/storage:/mnt/storage\" \\\n" +
            "  -b \"${TERMBOX_PREFIX}/tmp:/tmp\" \\\n" +
            "  -b \"${BOX64}:/usr/local/bin/box64\" \\\n" +
            "  -r \"${TERMBOX_UROOT}\" \\\n" +
            "  /usr/bin/env -i HOME=/root USER=root TERM=\"${TERM:-xterm-256color}\" \\\n" +
            "  LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
            "  BOX64_DYNAREC=1 BOX64_DYNAREC_STRONGMEM=1 BOX64_DYNAREC_BIGBLOCK=1 \\\n" +
            "  BOX64_DYNAREC_SAFEFLAGS=1 BOX64_DYNAREC_BLEEDING_EDGE=1 \\\n" +
            "  /bin/bash --login\n";

        writeScriptToFile(new File(binDir, "termbox-ubuntu"), ubuntuScript);

        // Install termbox-host (escape to native TermBox shell)
        String hostScript = "#!/bin/bash\n" +
            "# termbox-host - Exit Ubuntu to native TermBox shell\n" +
            "# Copyright (c) TermBox Contributors - MIT License\n\n" +
            "if [ \"${1:-}\" = \"help\" ] || [ \"${1:-}\" = \"--help\" ]; then\n" +
            "  echo \"termbox-host - Exit Ubuntu to native TermBox shell\"\n" +
            "  echo \"Usage: termbox-host\"\n" +
            "  echo \"From TermBox shell, re-enter Ubuntu: termbox-ubuntu\"\n" +
            "  exit 0\n" +
            "fi\n\n" +
            "TERMBOX_APP_DIR=\"${ANDROID_DATA:-/data/data/com.qali.termbox}\"\n" +
            "TERMBOX_PREFIX=\"${TERMBOX_APP_DIR}/files/usr\"\n\n" +
            "echo \"Exiting Ubuntu...\"\n" +
            "exec \"${TERMBOX_PREFIX}/bin/bash\" --login\n";

        writeScriptToFile(new File(binDir, "termbox-host"), hostScript);

        // Install termbox-shell-wrapper (default login shell that routes to Ubuntu)
        String wrapperScript = "#!/bin/bash\n" +
            "# termbox-shell-wrapper - Default login shell for TermBox\n" +
            "# Automatically enters Ubuntu ARM64 environment\n" +
            "# Copyright (c) TermBox Contributors - MIT License\n\n" +
            "export TERMBOX_DEFAULT_SESSION=\"ubuntu\"\n" +
            "export TERMBOX_HOST_SHELL=\"${PREFIX:-/data/data/com.qali.termbox/files/usr}/bin/bash\"\n\n" +
            "# If bash was invoked with arguments, execute them directly\n" +
            "if [ \"$1\" = \"-c\" ] || [ \"$#\" -gt 0 ]; then\n" +
            "  exec /bin/bash \"$@\"\n" +
            "fi\n\n" +
            "# Try termbox-ubuntu entry point\n" +
            "TERMBOX_UBUNTU=\"${PREFIX:-/data/data/com.qali.termbox/files/usr}/bin/termbox-ubuntu\"\n" +
            "if [ -x \"${TERMBOX_UBUNTU}\" ]; then\n" +
            "  exec \"${TERMBOX_UBUNTU}\"\n" +
            "fi\n\n" +
            "# Fallback to native shell\n" +
            "exec \"${PREFIX:-/data/data/com.qali.termbox/files/usr}/bin/bash\" --login\n";

        writeScriptToFile(new File(binDir, "termbox-shell-wrapper"), wrapperScript);

        Logger.logInfo(LOG_TAG, "TermBox shell scripts installed to " + binDir.getAbsolutePath());
    }

    /**
     * Write a script to a file and set it executable.
     */
    private static void writeScriptToFile(File file, String content) throws Exception {
        java.io.FileWriter writer = new java.io.FileWriter(file);
        writer.write(content);
        writer.close();
        file.setExecutable(true, false);
        file.setReadable(true, false);
    }

    /**
     * Setup the ELF architecture detection wrapper.
     * This creates a shell script that transparently routes x86_64 binaries through Box64.
     */
    private static void setupElfWrapper(File filesDir) throws Exception {
        File wrapperScript = new File(filesDir, "usr/bin/termbox-exec");
        wrapperScript.getParentFile().mkdirs();

        String wrapperContent = "#!/bin/bash\n" +
            "# TermBox ELF Architecture Detection Wrapper\n" +
            "# Automatically routes x86_64 binaries through Box64\n" +
            "TERMBOX_PREFIX=\"" + filesDir.getParent() + "/files/usr\"\n" +
            "BOX64=\"${TERMBOX_PREFIX}/bin/box64\"\n" +
            "\n" +
            "if [ -z \"$1\" ]; then\n" +
            "  echo \"Usage: termbox-exec <program> [args...]\"\n" +
            "  exit 1\n" +
            "fi\n" +
            "\n" +
            "# Check ELF magic and architecture\n" +
            "MAGIC=$(xxd -l 4 -p \"$1\" 2>/dev/null || head -c 4 \"$1\" 2>/dev/null | od -A n -t x1 | tr -d ' ')\n" +
            "if [ \"$MAGIC\" = \"7f454c46\" ]; then\n" +
            "  ELF_CLASS=$(xxd -s 4 -l 1 -p \"$1\" 2>/dev/null)\n" +
            "  if [ \"$ELF_CLASS\" = \"02\" ]; then\n" +
            "    ELF_MACHINE=$(xxd -s 18 -l 2 -e \"$1\" 2>/dev/null | awk '{print $2}')\n" +
            "    if [ \"$ELF_MACHINE\" = \"b7\" ]; then\n" +
            "      exec \"$1\" \"$@\"\n" +
            "    elif [ \"$ELF_MACHINE\" = \"3e\" ]; then\n" +
            "      if [ -x \"$BOX64\" ]; then\n" +
            "        exec \"$BOX64\" \"$1\" \"$@\"\n" +
            "      fi\n" +
            "    fi\n" +
            "  fi\n" +
            "fi\n" +
            "exec \"$1\" \"$@\"\n";

        java.io.FileWriter writer = new java.io.FileWriter(wrapperScript);
        writer.write(wrapperContent);
        writer.close();
        wrapperScript.setExecutable(true, false);
    }

}
