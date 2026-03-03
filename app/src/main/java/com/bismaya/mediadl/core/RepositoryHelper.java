/*
 * Copyright (C) 2019-2021 Yaroslav Pronin <proninyaroslav@mail.ru>
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

package com.bismaya.mediadl.core;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bismaya.mediadl.core.settings.SettingsRepository;
import com.bismaya.mediadl.core.settings.SettingsRepositoryImpl;
import com.bismaya.mediadl.core.storage.AppDatabase;
import com.bismaya.mediadl.core.storage.FeedRepository;
import com.bismaya.mediadl.core.storage.FeedRepositoryImpl;
import com.bismaya.mediadl.core.storage.TagRepository;
import com.bismaya.mediadl.core.storage.TagRepositoryImpl;
import com.bismaya.mediadl.core.storage.TorrentRepository;
import com.bismaya.mediadl.core.storage.TorrentRepositoryImpl;

public class RepositoryHelper
{
    private static FeedRepositoryImpl feedRepo;
    private static TorrentRepositoryImpl torrentRepo;
    private static SettingsRepositoryImpl settingsRepo;
    private static TagRepository tagRepo;

    public synchronized static TorrentRepository getTorrentRepository(@NonNull Context appContext)
    {
        if (torrentRepo == null)
            torrentRepo = new TorrentRepositoryImpl(appContext,
                    AppDatabase.getInstance(appContext));

        return torrentRepo;
    }

    public synchronized static FeedRepository getFeedRepository(@NonNull Context appContext)
    {
        if (feedRepo == null)
            feedRepo = new FeedRepositoryImpl(appContext,
                    AppDatabase.getInstance(appContext));

        return feedRepo;
    }

    public synchronized static SettingsRepository getSettingsRepository(@NonNull Context appContext)
    {
        if (settingsRepo == null)
            settingsRepo = new SettingsRepositoryImpl(appContext);

        return settingsRepo;
    }

    public synchronized static TagRepository getTagRepository(@NonNull Context appContext)
    {
        if (tagRepo == null)
            tagRepo = new TagRepositoryImpl(AppDatabase.getInstance(appContext));

        return tagRepo;
    }
}
