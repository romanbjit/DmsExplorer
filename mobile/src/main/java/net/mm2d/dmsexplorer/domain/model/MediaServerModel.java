/*
 * Copyright (c) 2017 大前良介 (OHMAE Ryosuke)
 *
 * This software is released under the MIT License.
 * http://opensource.org/licenses/MIT
 */

package net.mm2d.dmsexplorer.domain.model;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.mm2d.android.upnp.cds.CdsObject;
import net.mm2d.android.upnp.cds.MediaServer;
import net.mm2d.dmsexplorer.domain.entity.ContentDirectoryEntry;
import net.mm2d.dmsexplorer.domain.entity.ContentDirectoryEntry.EntryListener;

import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

/**
 * @author <a href="mailto:ryo@mm2d.net">大前良介 (OHMAE Ryosuke)</a>
 */
public class MediaServerModel implements EntryListener {
    public interface PlaybackTargetObserver {
        void update(CdsObject object);
    }

    private PlaybackTargetObserver mPlaybackTargetObserver;
    private static final String DELIMITER = " < ";
    private final MediaServer mMediaServer;
    private final Deque<ContentDirectoryEntry> mHistoryStack = new LinkedList<>();
    private String mPath;
    private ExploreListener EXPLORE_LISTENER = (list, inProgress) -> {
    };
    private volatile ExploreListener mExploreListener = EXPLORE_LISTENER;

    public interface ExploreListener {
        void onUpdate(@NonNull List<CdsObject> list, boolean inProgress);
    }

    public MediaServerModel(@NonNull final MediaServer server, @NonNull final PlaybackTargetObserver observer) {
        mMediaServer = server;
        mPlaybackTargetObserver = observer;
    }

    @NonNull
    public MediaServer getMediaServer() {
        return mMediaServer;
    }

    public void initialize() {
        prepareEntry(new ContentDirectoryEntry());
    }

    public void terminate() {
        setExploreListener(null);
        for (final ContentDirectoryEntry directory : mHistoryStack) {
            directory.terminate();
        }
        mHistoryStack.clear();
    }

    public boolean enterChild(@NonNull final CdsObject object) {
        final ContentDirectoryEntry directory = mHistoryStack.peekFirst();
        final ContentDirectoryEntry child = directory.enterChild(object);
        if (child == null) {
            return false;
        }
        directory.setEntryListener(null);
        prepareEntry(child);
        updatePlaybackTarget();
        return true;
    }

    private void prepareEntry(@NonNull final ContentDirectoryEntry directory) {
        mHistoryStack.offerFirst(directory);
        mPath = makePath();
        directory.setEntryListener(this);
        mExploreListener.onUpdate(Collections.emptyList(), true);
        directory.clearState();
        directory.setBrowseResult(mMediaServer.browse(directory.getParentId()));
    }

    public boolean exitToParent() {
        if (mHistoryStack.size() < 2) {
            return false;
        }
        mExploreListener.onUpdate(Collections.emptyList(), true);
        final ContentDirectoryEntry directory = mHistoryStack.pollFirst();
        directory.terminate();
        mPath = makePath();
        final ContentDirectoryEntry parent = mHistoryStack.peekFirst();
        parent.setEntryListener(this);
        mExploreListener.onUpdate(parent.getList(), parent.isInProgress());
        updatePlaybackTarget();
        return true;
    }

    public void reload() {
        mExploreListener.onUpdate(Collections.emptyList(), true);
        final ContentDirectoryEntry directory = mHistoryStack.peekFirst();
        if (directory == null) {
            return;
        }
        directory.clearState();
        directory.setBrowseResult(mMediaServer.browse(directory.getParentId()));
    }

    public void setExploreListener(@Nullable final ExploreListener listener) {
        mExploreListener = listener != null ? listener : EXPLORE_LISTENER;
        final ContentDirectoryEntry directory = mHistoryStack.peekFirst();
        mExploreListener.onUpdate(directory.getList(), directory.isInProgress());
    }

    public String getUdn() {
        return mMediaServer.getUdn();
    }

    public String getTitle() {
        return mMediaServer.getFriendlyName();
    }

    public String getPath() {
        return mPath;
    }

    public void setSelectedObject(@NonNull final CdsObject object) {
        mHistoryStack.peekFirst().setSelectedObject(object);
        updatePlaybackTarget();
    }

    private void updatePlaybackTarget() {
        mPlaybackTargetObserver.update(getSelectedObject());
    }

    public CdsObject getSelectedObject() {
        final ContentDirectoryEntry entry = mHistoryStack.peekFirst();
        if (entry == null) {
            return null;
        }
        return entry.getSelectedObject();
    }

    private String makePath() {
        final StringBuilder sb = new StringBuilder();
        for (final ContentDirectoryEntry directory : mHistoryStack) {
            if (sb.length() != 0 && directory.getParentTitle().length() != 0) {
                sb.append(DELIMITER);
            }
            sb.append(directory.getParentTitle());
        }
        return sb.toString();
    }

    @Override
    public void onUpdate(@NonNull final List<CdsObject> list, final boolean inProgress) {
        mExploreListener.onUpdate(list, inProgress);
    }
}
