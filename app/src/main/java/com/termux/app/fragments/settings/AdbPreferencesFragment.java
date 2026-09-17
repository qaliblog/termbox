package com.termux.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.adb.TermboxAdbBridge;
import com.termux.app.adb.remote.AdbKeyStore;
import com.termux.app.adb.remote.AdbSettingsStore;
import com.termux.app.adb.remote.AdbTransportManager;
import com.termux.app.adb.remote.RemoteDevice;

/**
 * ADB &amp; Wireless Debugging section inside the existing TermBox settings
 * screen (spec §7). Not a separate activity — a normal sub-screen fragment,
 * styled like every other preferences fragment in the app.
 *
 * It drives the same {@link AdbTransportManager} the guest-side `adb connect`
 * uses, so the status line reflects real transport state (spec §17: never
 * infer "Connected" from a process merely existing).
 */
@Keep
public class AdbPreferencesFragment extends PreferenceFragmentCompat {

    private static final String KEY_STATUS = "adb_status";
    private static final String KEY_DEVICE = "adb_device";
    private static final String KEY_ENABLED = "adb_enabled";
    private static final String KEY_AUTO_RECONNECT = "adb_auto_reconnect";
    private static final String KEY_CONNECT = "adb_connect";
    private static final String KEY_DISCONNECT = "adb_disconnect";
    private static final String KEY_REFRESH = "adb_refresh";
    private static final String KEY_PUBLIC_KEY = "adb_public_key";

    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(AdbPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.adb_preferences, rootKey);

        // Switches persist through AdbPreferencesDataStore (adb_enabled=false
        // also tears down live transports).
        configureActions(context);
        refreshStatus();
    }

    private void configureActions(@NonNull Context context) {
        Preference connect = findPreference(KEY_CONNECT);
        if (connect != null) {
            connect.setOnPreferenceClickListener(preference -> {
                runAsync(() -> {
                    String host = AdbTransportManager.savedEndpointHost();
                    if (host == null) {
                        return context.getString(R.string.adb_status_unconfigured);
                    }
                    // The manager reports honest results: "connected to ..." only
                    // after a real CNXN/AUTH handshake (spec §23).
                    return AdbTransportManager.connect(host, AdbTransportManager.savedEndpointPort());
                });
                return true;
            });
        }

        Preference disconnect = findPreference(KEY_DISCONNECT);
        if (disconnect != null) {
            disconnect.setOnPreferenceClickListener(preference -> {
                runAsync(AdbTransportManager::disconnectAll);
                return true;
            });
        }

        Preference refresh = findPreference(KEY_REFRESH);
        if (refresh != null) {
            refresh.setOnPreferenceClickListener(preference -> {
                refreshStatus();
                return true;
            });
        }

        Preference publicKey = findPreference(KEY_PUBLIC_KEY);
        if (publicKey != null) {
            publicKey.setOnPreferenceClickListener(preference -> {
                runAsync(() -> {
                    try {
                        return context.getString(R.string.adb_public_key_prefix) + " "
                            + AdbKeyStore.publicKeyLine(context);
                    } catch (Exception e) {
                        return context.getString(R.string.adb_error_key, e.getMessage());
                    }
                });
                return true;
            });
        }
    }

    /** Recompute the status/device lines from actual transport state. */
    private void refreshStatus() {
        Context context = getContext();
        if (context == null) return;

        AdbSettingsStore settings = AdbSettingsStore.get(context);
        AdbTransportManager.State state = AdbTransportManager.statusState();

        Preference status = findPreference(KEY_STATUS);
        if (status != null) {
            String summary;
            switch (state) {
                case CONNECTED:
                    summary = context.getString(R.string.adb_status_connected);
                    break;
                case CONNECTING:
                    summary = context.getString(R.string.adb_status_connecting);
                    break;
                case DISCONNECTED:
                    summary = context.getString(R.string.adb_status_disconnected);
                    break;
                case ERROR:
                    summary = context.getString(R.string.adb_status_error);
                    break;
                default:
                    summary = context.getString(R.string.adb_status_unconfigured);
                    break;
            }
            String host = settings.getLastHost();
            if (host != null && state != AdbTransportManager.State.CONNECTED) {
                summary += " (" + host + ":" + settings.getLastPort() + ")";
                summary += "\n" + context.getString(R.string.adb_status_saved_endpoint);
            }
            status.setSummary(summary);
        }

        Preference device = findPreference(KEY_DEVICE);
        if (device != null) {
            RemoteDevice online = AdbTransportManager.anyDevice();
            if (online != null) {
                device.setVisible(true);
                device.setSummary(online.getSpec()
                    + (online.getBanner() == null || online.getBanner().isEmpty()
                    ? "" : "\n" + online.getBanner()));
            } else {
                device.setVisible(false);
            }
        }
    }

    /** Runs a blocking operation off the UI thread and shows the result in the status line. */
    private void runAsync(java.util.concurrent.Callable<String> work) {
        final Context context = getContext();
        if (context == null) return;

        final Preference status = findPreference(KEY_STATUS);
        mMain.post(() -> {
            if (status != null) status.setSummary(context.getString(R.string.adb_status_working));
        });

        new Thread(() -> {
            String result;
            try {
                result = work.call();
            } catch (Exception e) {
                result = context.getString(R.string.adb_error_generic, e.getMessage());
            }
            final String resultLine = result;
            mMain.post(() -> {
                Preference statusPref = findPreference(KEY_STATUS);
                if (statusPref != null && resultLine != null) {
                    statusPref.setSummary(resultLine);
                }
                refreshStatus();
            });
        }, "adb-settings-action").start();
    }

    /** Data store bridging the switches to {@link AdbSettingsStore}. */
    private static class AdbPreferencesDataStore extends PreferenceDataStore {

        private final Context mContext;
        private final AdbSettingsStore mSettings;

        private static AdbPreferencesDataStore sInstance;

        private AdbPreferencesDataStore(Context context) {
            mContext = context;
            mSettings = AdbSettingsStore.get(context);
        }

        public static synchronized AdbPreferencesDataStore getInstance(Context context) {
            if (sInstance == null) {
                sInstance = new AdbPreferencesDataStore(context);
            }
            return sInstance;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            if (KEY_ENABLED.equals(key)) {
                mSettings.setEnabled(value);
                if (!value) {
                    AdbTransportManager.disconnectAll();
                }
            }
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            if (KEY_ENABLED.equals(key)) {
                return mSettings.isEnabled();
            }
            if (KEY_AUTO_RECONNECT.equals(key)) {
                return mSettings.isAutoReconnect();
            }
            return defValue;
        }
    }
}
