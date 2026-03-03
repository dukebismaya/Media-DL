/*
 * Copyright (C) 2018-2021 Yaroslav Pronin <proninyaroslav@mail.ru>
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

package com.bismaya.mediadl.receiver;

import static com.bismaya.mediadl.service.Scheduler.SCHEDULER_WORK_START_APP;
import static com.bismaya.mediadl.service.Scheduler.SCHEDULER_WORK_STOP_APP;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;

import com.bismaya.mediadl.core.RepositoryHelper;
import com.bismaya.mediadl.core.model.TorrentEngine;
import com.bismaya.mediadl.core.settings.SettingsRepository;
import com.bismaya.mediadl.core.utils.Utils;
import com.bismaya.mediadl.service.Scheduler;
import com.bismaya.mediadl.ui.TorrentNotifier;

/*
 * The receiver for AlarmManager scheduling
 */

public class SchedulerReceiver extends BroadcastReceiver
{
    @Override
    public void onReceive(Context context, Intent intent)
    {
        if (intent.getAction() == null)
            return;

        var appContext = context.getApplicationContext();
        var pref = RepositoryHelper.getSettingsRepository(appContext);
        var notifier = TorrentNotifier.getInstance(appContext);

        switch (intent.getAction()) {
            case SCHEDULER_WORK_START_APP: {
                onStartApp(appContext, pref, notifier);
                break;
            }
            case SCHEDULER_WORK_STOP_APP: {
                onStopApp(appContext, pref, notifier);
                break;
            }
        }
    }

    private void onStartApp(Context appContext, SettingsRepository pref, TorrentNotifier notifier)
    {
        if (!pref.enableSchedulingStart())
            return;

        boolean oneshot = pref.schedulingRunOnlyOnce();
        if (oneshot) {
            pref.enableSchedulingStart(false);
        } else if (!Scheduler.setStartAppAlarm(appContext, pref.schedulingStartTime())) {
            notifier.makeExactAlarmPermissionNotify();
        }
        if (pref.schedulingSwitchWiFi())
            ((WifiManager)appContext.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE)).setWifiEnabled(true);

        TorrentEngine.getInstance(appContext).start();
        Utils.enableBootReceiverIfNeeded(appContext);
    }

    private void onStopApp(Context appContext, SettingsRepository pref, TorrentNotifier notifier)
    {
        if (!pref.enableSchedulingShutdown())
            return;

        boolean oneshot = pref.schedulingRunOnlyOnce();
        if (oneshot) {
            pref.enableSchedulingShutdown(false);
        } else if (!Scheduler.setStartAppAlarm(appContext, pref.schedulingShutdownTime())) {
            notifier.makeExactAlarmPermissionNotify();
        }

        TorrentEngine.getInstance(appContext).forceStop();

        if (pref.schedulingSwitchWiFi())
            ((WifiManager)appContext.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE)).setWifiEnabled(false);

        Utils.enableBootReceiverIfNeeded(appContext);
    }
}
