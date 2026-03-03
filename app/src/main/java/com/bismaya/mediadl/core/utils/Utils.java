/*
 * Copyright (C) 2016-2025 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.bismaya.mediadl.core.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import org.apache.commons.io.IOUtils;
import org.libtorrent4j.FileStorage;
import com.bismaya.mediadl.R;
import com.bismaya.mediadl.core.HttpConnection;
import com.bismaya.mediadl.core.RepositoryHelper;
import com.bismaya.mediadl.core.exception.FetchLinkException;
import com.bismaya.mediadl.core.filter.TorrentFilter;
import com.bismaya.mediadl.core.filter.TorrentFilterCollection;
import com.bismaya.mediadl.core.model.data.metainfo.BencodeFileItem;
import com.bismaya.mediadl.core.model.data.preferences.PrefTheme;
import com.bismaya.mediadl.core.settings.SettingsRepository;
import com.bismaya.mediadl.core.sorting.BaseSorting;
import com.bismaya.mediadl.core.sorting.TorrentSorting;
import com.bismaya.mediadl.core.sorting.TorrentSortingComparator;
import com.bismaya.mediadl.core.system.FileSystemFacade;
import com.bismaya.mediadl.core.system.SafFileSystem;
import com.bismaya.mediadl.core.system.SystemFacade;
import com.bismaya.mediadl.core.system.SystemFacadeHelper;
import com.bismaya.mediadl.receiver.BootReceiver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/*
 * General utils.
 */

public class Utils {
    private static final String TAG = Utils.class.getSimpleName();

    public static final String INFINITY_SYMBOL = "∞";
    public static final String MAGNET_PREFIX = "magnet";
    public static final String HTTP_PREFIX = "http";
    public static final String HTTPS_PREFIX = "https";
    public static final String INFOHASH_PREFIX = "magnet:?xt=urn:btih:";
    public static final String FILE_PREFIX = "file";
    public static final String CONTENT_PREFIX = "content";
    public static final String TRACKER_URL_PATTERN =
            "^(https?|udp)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]";
    public static final String HASH_PATTERN = "\\b[0-9a-fA-F]{5,40}\\b";
    public static final String MIME_TORRENT = "application/x-bittorrent";
    public static final String MIME_TEXT_PLAIN = "text/plain";
    public static final String NEWLINE_PATTERN = "\\r\\n|\\r|\\n";
    private static final String FEED_MIME_TYPE_PATTERN = "^(application|text)/((atom|rss)\\+)?xml";
    private static final String FEED_FILE_PATH_PATTERN = ".*\\.(xml|rss|atom)";

    /*
     * Returns the list of BencodeFileItem objects, extracted from FileStorage.
     * The order of addition in the list corresponds to the order of indexes in libtorrent4j.FileStorage
     */

    public static ArrayList<BencodeFileItem> getFileList(@NonNull FileStorage storage) {
        ArrayList<BencodeFileItem> files = new ArrayList<>();
        for (int i = 0; i < storage.numFiles(); i++) {
            BencodeFileItem file = new BencodeFileItem(storage.filePath(i), i, storage.fileSize(i));
            files.add(file);
        }

        return files;
    }

    public static boolean checkConnectivity(@NonNull Context context) {
        SystemFacade systemFacade = SystemFacadeHelper.getSystemFacade(context);
        NetworkInfo netInfo = systemFacade.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnected() && isNetworkTypeAllowed(context);
    }

    public static boolean isNetworkTypeAllowed(@NonNull Context context) {
        SystemFacade systemFacade = SystemFacadeHelper.getSystemFacade(context);

        SettingsRepository pref = RepositoryHelper.getSettingsRepository(context);
        boolean enableRoaming = pref.enableRoaming();
        boolean unmeteredOnly = pref.unmeteredConnectionsOnly();

        boolean noUnmeteredOnly;
        boolean noRoaming;

        NetworkCapabilities caps = systemFacade.getNetworkCapabilities();
        /*
         * Use ConnectivityManager#isActiveNetworkMetered() instead of NetworkCapabilities#NET_CAPABILITY_NOT_METERED,
         * since Android detection VPN as metered, including on Android 9, oddly enough.
         * I think this is due to what VPN services doesn't use setUnderlyingNetworks() method.
         *
         * See for details: https://developer.android.com/about/versions/pie/android-9.0-changes-all#network-capabilities-vpn
         */
        boolean unmetered = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                !systemFacade.isActiveNetworkMetered();
        noUnmeteredOnly = !unmeteredOnly || unmetered;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            noRoaming = !enableRoaming || caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        } else {
            NetworkInfo netInfo = systemFacade.getActiveNetworkInfo();
            noRoaming = netInfo != null && !(enableRoaming && netInfo.isRoaming());
        }

        return noUnmeteredOnly && noRoaming;
    }

    public static boolean isMetered(@NonNull Context context) {
        SystemFacade systemFacade = SystemFacadeHelper.getSystemFacade(context);

        NetworkCapabilities caps = systemFacade.getNetworkCapabilities();
        return caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                systemFacade.isActiveNetworkMetered();
    }

    public static boolean isRoaming(@NonNull Context context) {
        SystemFacade systemFacade = SystemFacadeHelper.getSystemFacade(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            NetworkCapabilities caps = systemFacade.getNetworkCapabilities();
            return caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        } else {
            NetworkInfo netInfo = systemFacade.getActiveNetworkInfo();
            return netInfo != null && netInfo.isRoaming();
        }
    }

    /*
     * Returns the link as "magnet:?xt=urn:btih:hash".
     */

    public static String normalizeMagnetHash(@NonNull String hash) {
        return INFOHASH_PREFIX + hash;
    }

    /*
     * Don't use app context (its doesn't reload after configuration changes)
     */

    public static boolean isTwoPane(@NonNull Context context) {
        return context.getResources().getBoolean(R.bool.isTwoPane);
    }

    /*
     * Tablets (from 7"), notebooks, TVs
     *
     * Don't use app context (its doesn't reload after configuration changes)
     */

    public static boolean isLargeScreenDevice(@NonNull Context context) {
        return context.getResources().getBoolean(R.bool.isLargeScreenDevice);
    }

    /*
     * Returns true if link has the form "http[s][udp]://[www.]name.domain/...".
     *
     * Returns false if the link is not valid.
     */

    public static boolean isValidTrackerUrl(@NonNull String url) {
        if (TextUtils.isEmpty(url))
            return false;

        Pattern pattern = Pattern.compile(TRACKER_URL_PATTERN);
        Matcher matcher = pattern.matcher(url.trim());

        return matcher.matches();
    }

    public static boolean isHash(@NonNull String hash) {
        if (TextUtils.isEmpty(hash))
            return false;

        Pattern pattern = Pattern.compile(HASH_PATTERN);
        Matcher matcher = pattern.matcher(hash.trim());

        return matcher.matches();
    }

    /*
     * Return system text line separator (in android it '\n').
     */

    public static String getLineSeparator() {
        return System.lineSeparator();
    }

    @Nullable
    public static ClipData getClipData(@NonNull Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (!clipboard.hasPrimaryClip())
            return null;

        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0)
            return null;

        return clip;
    }

    public static List<CharSequence> getClipboardText(@NonNull Context context) {
        ArrayList<CharSequence> clipboardText = new ArrayList<>();

        ClipData clip = Utils.getClipData(context);
        if (clip == null)
            return clipboardText;

        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence item = clip.getItemAt(i).getText();
            if (item == null)
                continue;
            clipboardText.add(item);
        }

        return clipboardText;
    }

    public static void reportError(@Nullable Throwable error,
                                   @Nullable String comment
    ) {
        if (error != null) {
            android.util.Log.e(TAG, comment != null ? comment : "Error", error);
        }
    }

    public static int dpToPx(@NonNull Context context, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics());
    }

    @SuppressLint("DiscouragedApi")
    public static int getDefaultBatteryLowLevel() {
        return Resources.getSystem().getInteger(
                Resources.getSystem().getIdentifier("config_lowBatteryWarningLevel", "integer", "android"));
    }

    public static float getBatteryLevel(@NonNull Context context) {
        Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null)
            return 50.0f;
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        /* Error checking that probably isn't needed but I added just in case */
        if (level == -1 || scale == -1)
            return 50.0f;

        return ((float) level / (float) scale) * 100.0f;
    }

    public static boolean isBatteryCharging(@NonNull Context context) {
        Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null)
            return false;
        int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;
    }

    public static boolean isBatteryLow(@NonNull Context context) {
        return Utils.getBatteryLevel(context) <= Utils.getDefaultBatteryLowLevel();
    }

    public static boolean isBatteryBelowThreshold(@NonNull Context context, int threshold) {
        return Utils.getBatteryLevel(context) <= threshold;
    }

    public static boolean shouldRequestStoragePermission(@NonNull Activity activity) {
        return ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        );
    }

    /*
     * Workaround for start service in Android 8+ if app no started.
     * We have a window of time to get around to calling startForeground() before we get ANR,
     * if work is longer than a millisecond but less than a few seconds.
     */

    public static void startServiceBackground(@NonNull Context context, @NonNull Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Postpone the service start until the main thread is free to avoid ANR
            new Handler(Looper.getMainLooper())
                    .post(() -> context.startForegroundService(i));
        } else {
            context.startService(i);
        }
    }

    public static void enableBootReceiver(@NonNull Context context, boolean enable) {
        SettingsRepository pref = RepositoryHelper.getSettingsRepository(context);
        boolean schedulingStart = pref.enableSchedulingStart();
        boolean schedulingStop = pref.enableSchedulingShutdown();
        boolean autostart = pref.autostart();
        int flag = (!(enable || schedulingStart || schedulingStop || autostart) ?
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED :
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        ComponentName bootReceiver = new ComponentName(context, BootReceiver.class);
        context.getPackageManager()
                .setComponentEnabledSetting(bootReceiver, flag, PackageManager.DONT_KILL_APP);
    }

    public static void enableBootReceiverIfNeeded(@NonNull Context context) {
        SettingsRepository pref = RepositoryHelper.getSettingsRepository(context);
        boolean schedulingStart = pref.enableSchedulingStart();
        boolean schedulingStop = pref.enableSchedulingShutdown();
        boolean autostart = pref.autostart();
        int flag = (!(schedulingStart || schedulingStop || autostart) ?
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED :
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        ComponentName bootReceiver = new ComponentName(context, BootReceiver.class);
        context.getPackageManager()
                .setComponentEnabledSetting(bootReceiver, flag, PackageManager.DONT_KILL_APP);
    }

    public static byte[] fetchHttpUrl(@NonNull Context context,
                                      @NonNull String url) throws FetchLinkException {
        if (!Utils.checkConnectivity(context)) {
            throw new FetchLinkException("No network connection");
        }

        try {
            var urlObj = new URL(url);
            var connection = (HttpURLConnection) urlObj.openConnection();

            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream inputStream = connection.getInputStream();
                     var byteArrayOutputStream = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        byteArrayOutputStream.write(buffer, 0, bytesRead);
                    }
                    return byteArrayOutputStream.toByteArray();
                }
            } else {
                throw new FetchLinkException("Error while downloading file: " + responseCode);
            }
        } catch (IOException e) {
            throw new FetchLinkException(e);
        }
    }

    /*
     * Without additional information (e.g -DEBUG)
     */

    public static String getAppVersionNumber(@NonNull String versionName) {
        int index = versionName.indexOf("-");
        if (index >= 0)
            versionName = versionName.substring(0, index);

        return versionName;
    }

    /*
     * Return version components in these format: [major, minor, revision]
     */

    public static int[] getVersionComponents(@NonNull String versionName) {
        int[] version = new int[3];

        /* Discard additional information */
        versionName = getAppVersionNumber(versionName);

        String[] components = versionName.split("\\.");
        if (components.length < 2)
            return version;

        try {
            version[0] = Integer.parseInt(components[0]);
            version[1] = Integer.parseInt(components[1]);
            if (components.length >= 3)
                version[2] = Integer.parseInt(components[2]);

        } catch (NumberFormatException e) {
            /* Ignore */
        }

        return version;
    }

    public static String makeSha1Hash(@NonNull String s) {
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        messageDigest.update(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sha1 = new StringBuilder();
        for (byte b : messageDigest.digest()) {
            if ((0xff & b) < 0x10)
                sha1.append("0");
            sha1.append(Integer.toHexString(0xff & b));
        }

        return sha1.toString();
    }

    public static SSLContext getSSLContext() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);

        TrustManager[] trustManagers = tmf.getTrustManagers();
        final X509TrustManager origTrustManager = (X509TrustManager) trustManagers[0];

        TrustManager[] wrappedTrustManagers = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return origTrustManager.getAcceptedIssuers();
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                        origTrustManager.checkClientTrusted(certs, authType);
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                        origTrustManager.checkServerTrusted(certs, authType);
                    }
                }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, wrappedTrustManagers, null);

        return sslContext;
    }

    /*
     * Return path to the current torrent download directory.
     * If the directory doesn't exist, the function creates it automatically
     */

    public static Uri getTorrentDownloadPath(@NonNull Context appContext) {
        SettingsRepository pref = RepositoryHelper.getSettingsRepository(appContext);
        String path = pref.saveTorrentsIn();

        FileSystemFacade fs = SystemFacadeHelper.getFileSystemFacade(appContext);

        path = (TextUtils.isEmpty(path) ? fs.getDefaultDownloadPath() : path);

        return (path == null ? null : Uri.parse(fs.normalizeFileSystemPath(path)));
    }

    public static boolean isFileSystemPath(@NonNull Uri path) {
        String scheme = path.getScheme();
        if (scheme == null)
            throw new IllegalArgumentException("Scheme of " + path.getPath() + " is null");

        return scheme.equals(ContentResolver.SCHEME_FILE);
    }

    public static boolean isSafPath(@NonNull Context appContext, @NonNull Uri path) {
        return SafFileSystem.getInstance(appContext).isSafPath(path);
    }

    public static int getRandomColor() {
        return ((int) (Math.random() * 16777215)) | (0xFF << 24);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public static Intent buildExactAlarmPermissionRequest(@NonNull Context context) {
        var uri = Uri.fromParts("package", context.getPackageName(), null);
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(uri);
    }

    public static void requestExactAlarmPermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(buildExactAlarmPermissionRequest(context));
        }
    }

    public static boolean hasManageExternalStoragePermission(@NonNull Context appContext) {
        try {
            var info = appContext.getPackageManager().getPackageInfo(
                    appContext.getPackageName(),
                    PackageManager.GET_PERMISSIONS
            );
            if (info.requestedPermissions == null) {
                return false;
            }
            for (var p : info.requestedPermissions) {
                if ("android.permission.MANAGE_EXTERNAL_STORAGE".equals(p)) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to get permissions", e);
            return false;
        }

        return false;
    }

    public static TorrentFilter getForegroundNotifyFilter(
            @NonNull Context context,
            @NonNull SettingsRepository pref
    ) {
        String val = pref.foregroundNotifyStatusFilter();
        if (val.equals(context.getString(R.string.pref_foreground_notify_status_all_value))) {
            return TorrentFilterCollection.all();
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_status_downloading_value))) {
            return TorrentFilterCollection.statusDownloading();
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_status_downloaded_value))) {
            return TorrentFilterCollection.statusDownloaded();
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_status_downloading_metadata_value))) {
            return TorrentFilterCollection.statusDownloadingMetadata();
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_status_error_value))) {
            return TorrentFilterCollection.statusError();
        }
        throw new IllegalStateException("Unknown filter type: " + val);
    }

    public static TorrentSortingComparator getForegroundNotifySorting(
            @NonNull Context context,
            @NonNull SettingsRepository pref
    ) {
        String val = pref.foregroundNotifySorting();
        if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_name_asc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.name, TorrentSorting.Direction.ASC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_name_desc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.name, TorrentSorting.Direction.DESC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_date_added_asc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.dateAdded, TorrentSorting.Direction.ASC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_date_added_desc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.dateAdded, TorrentSorting.Direction.DESC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_size_asc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.size, TorrentSorting.Direction.ASC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_size_desc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.size, TorrentSorting.Direction.DESC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_progress_asc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.progress, TorrentSorting.Direction.ASC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_progress_desc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.progress, TorrentSorting.Direction.DESC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_eta_asc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.ETA, TorrentSorting.Direction.ASC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_eta_desc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.ETA, TorrentSorting.Direction.DESC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_peers_asc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.peers, TorrentSorting.Direction.ASC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_peers_desc_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.peers, TorrentSorting.Direction.DESC));
        } else if (val.equals(context.getString(R.string.pref_foreground_notify_sorting_no_sorting_value))) {
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.none, TorrentSorting.Direction.ASC));
        }
        throw new IllegalStateException("Unknown sorting type: " + val);
    }

    public static boolean matchFeedMimeType(@NonNull String mimeType) {
        var pattern = Pattern.compile(FEED_MIME_TYPE_PATTERN, Pattern.CASE_INSENSITIVE);
        return pattern.matcher(mimeType).matches();
    }

    public static boolean matchFeedFilePath(@NonNull String path) {
        var pattern = Pattern.compile(FEED_FILE_PATH_PATTERN, Pattern.CASE_INSENSITIVE);
        return pattern.matcher(path).matches();
    }

    public static void restartApp(@NonNull Context context) {
        var packageManager = context.getPackageManager();
        var intent = packageManager.getLaunchIntentForPackage(context.getPackageName());
        var componentName = intent.getComponent();
        var mainIntent = Intent.makeRestartActivityTask(componentName);
        // Required for API 34 and later
        // Ref: https://developer.android.com/about/versions/14/behavior-changes-14#safer-intents
        mainIntent.setPackage(context.getPackageName());
        context.startActivity(mainIntent);
        Runtime.getRuntime().exit(0);
    }

}
