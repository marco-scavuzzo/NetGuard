package eu.faircode.netguard;

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015 by Marcel Bokhorst (M66B)
*/

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.util.Map;

public class Receiver extends BroadcastReceiver {
    private static final String TAG = "NetGuard.Receiver";

    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.i(TAG, "Received " + intent);
        Util.logExtras(intent);

        if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
            // Application added
            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                // Show notification
                int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
                notifyApplication(uid, context);
            }

        } else if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
            // Application removed
            if (intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)) {
                // Remove settings
                String packageName = intent.getData().getSchemeSpecificPart();
                Log.i(TAG, "Deleting settings package=" + packageName);
                context.getSharedPreferences("wifi", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                context.getSharedPreferences("other", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                context.getSharedPreferences("screen_other", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                context.getSharedPreferences("roaming", Context.MODE_PRIVATE).edit().remove(packageName).apply();

                int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
                if (uid > 0)
                    NotificationManagerCompat.from(context).cancel(uid);
            }

        } else {
            // Upgrade settings
            upgrade(true, context);

            // Start service
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (prefs.getBoolean("enabled", false))
                try {
                    if (VpnService.prepare(context) == null)
                        SinkholeService.start("receiver", context);
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    Util.sendCrashReport(ex, context);
                }

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm.isInteractive())
                SinkholeService.reloadStats("receiver", context);
        }
    }

    public static void notifyApplication(int uid, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        try {
            // Get application info
            PackageManager pm = context.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages.length < 1)
                throw new PackageManager.NameNotFoundException(Integer.toString(uid));
            ApplicationInfo info = pm.getApplicationInfo(packages[0], 0);
            String name = (String) pm.getApplicationLabel(info);

            // Check system application
            boolean system = ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
            boolean manage_system = prefs.getBoolean("manage_system", false);
            if (system && !manage_system)
                return;

            // Build notification
            Intent main = new Intent(context, ActivityMain.class);
            main.putExtra(ActivityMain.EXTRA_SEARCH, name);
            PendingIntent pi = PendingIntent.getActivity(context, 999, main, PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Builder notification = new NotificationCompat.Builder(context)
                    .setSmallIcon(R.drawable.ic_security_white_24dp)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.msg_installed, name))
                    .setContentIntent(pi)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
                    .setAutoCancel(true);

            // Get defaults
            SharedPreferences prefs_wifi = context.getSharedPreferences("wifi", Context.MODE_PRIVATE);
            SharedPreferences prefs_other = context.getSharedPreferences("other", Context.MODE_PRIVATE);
            boolean wifi = prefs_wifi.getBoolean(packages[0], prefs.getBoolean("whitelist_wifi", true));
            boolean other = prefs_other.getBoolean(packages[0], prefs.getBoolean("whitelist_other", true));

            // Build Wi-Fi action
            Intent riWifi = new Intent(context, SinkholeService.class);
            riWifi.putExtra(SinkholeService.EXTRA_COMMAND, SinkholeService.Command.set);
            riWifi.putExtra(SinkholeService.EXTRA_NETWORK, "wifi");
            riWifi.putExtra(SinkholeService.EXTRA_UID, uid);
            riWifi.putExtra(SinkholeService.EXTRA_PACKAGE, packages[0]);
            riWifi.putExtra(SinkholeService.EXTRA_BLOCKED, !wifi);

            PendingIntent piWifi = PendingIntent.getService(context, uid, riWifi, PendingIntent.FLAG_UPDATE_CURRENT);
            notification.addAction(
                    wifi ? R.drawable.wifi_on : R.drawable.wifi_off,
                    context.getString(wifi ? R.string.title_allow : R.string.title_block),
                    piWifi
            );

            // Build mobile action
            Intent riOther = new Intent(context, SinkholeService.class);
            riOther.putExtra(SinkholeService.EXTRA_COMMAND, SinkholeService.Command.set);
            riOther.putExtra(SinkholeService.EXTRA_NETWORK, "other");
            riOther.putExtra(SinkholeService.EXTRA_UID, uid);
            riOther.putExtra(SinkholeService.EXTRA_PACKAGE, packages[0]);
            riOther.putExtra(SinkholeService.EXTRA_BLOCKED, !other);
            PendingIntent piOther = PendingIntent.getService(context, uid + 10000, riOther, PendingIntent.FLAG_UPDATE_CURRENT);
            notification.addAction(
                    other ? R.drawable.other_on : R.drawable.other_off,
                    context.getString(other ? R.string.title_allow : R.string.title_block),
                    piOther
            );

            // Show notification
            NotificationManagerCompat.from(context).notify(uid, notification.build());

        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }
    }

    public static void upgrade(boolean initialized, Context context) {
        synchronized (context.getApplicationContext()) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int oldVersion = prefs.getInt("version", -1);
            int newVersion = Util.getSelfVersionCode(context);
            if (oldVersion == newVersion)
                return;
            Log.i(TAG, "Upgrading from version " + oldVersion + " to " + newVersion);

            SharedPreferences.Editor editor = prefs.edit();
            if (initialized) {
                if (oldVersion < 38) {
                    Log.i(TAG, "Converting screen wifi/mobile");
                    editor.putBoolean("screen_wifi", prefs.getBoolean("unused", false));
                    editor.putBoolean("screen_other", prefs.getBoolean("unused", false));
                    editor.remove("unused");

                    SharedPreferences unused = context.getSharedPreferences("unused", Context.MODE_PRIVATE);
                    SharedPreferences screen_wifi = context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE);
                    SharedPreferences screen_other = context.getSharedPreferences("screen_other", Context.MODE_PRIVATE);

                    Map<String, ?> punused = unused.getAll();
                    SharedPreferences.Editor edit_screen_wifi = screen_wifi.edit();
                    SharedPreferences.Editor edit_screen_other = screen_other.edit();
                    for (String key : punused.keySet()) {
                        edit_screen_wifi.putBoolean(key, (Boolean) punused.get(key));
                        edit_screen_other.putBoolean(key, (Boolean) punused.get(key));
                    }
                    edit_screen_wifi.apply();
                    edit_screen_other.apply();

                    // TODO: delete unused
                }
            } else {
                editor.putBoolean("whitelist_wifi", false);
                editor.putBoolean("whitelist_other", false);
            }
            editor.putInt("version", newVersion);
            editor.apply();
        }
    }
}