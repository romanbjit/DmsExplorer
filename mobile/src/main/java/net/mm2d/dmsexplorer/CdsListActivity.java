/*
 * Copyright (c) 2017 大前良介 (OHMAE Ryosuke)
 *
 * This software is released under the MIT License.
 * http://opensource.org/licenses/MIT
 */

package net.mm2d.dmsexplorer;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.transition.Slide;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import net.mm2d.android.upnp.cds.BrowseResult;
import net.mm2d.android.upnp.cds.CdsObject;
import net.mm2d.android.upnp.cds.MediaServer;
import net.mm2d.android.view.DividerItemDecoration;
import net.mm2d.dmsexplorer.adapter.CdsListAdapter;
import net.mm2d.dmsexplorer.util.ToolbarThemeUtils;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.ExecutionException;

/**
 * MediaServerのContentDirectoryを表示、操作するActivity。
 *
 * @author <a href="mailto:ryo@mm2d.net">大前良介(OHMAE Ryosuke)</a>
 */
public class CdsListActivity extends AppCompatActivity
        implements BrowseResult.StatusListener {
    private static final String KEY_HISTORY = "KEY_HISTORY";
    private static final String KEY_SELECTED = "KEY_SELECTED";
    private static final String KEY_POSITION = "KEY_POSITION";
    private boolean mTwoPane;
    private final DataHolder mDataHolder = DataHolder.getInstance();
    private Handler mHandler;
    private MediaServer mServer;
    private BrowseResult mBrowseResult;
    private final LinkedList<History> mHistories = new LinkedList<>();
    private String mSubtitle;
    private CdsObject mSelectedObject;
    private CdsDetailFragment mCdsDetailFragment;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private RecyclerView mRecyclerView;
    private CdsListAdapter mCdsListAdapter;

    /**
     * インスタンスを作成する。
     *
     * <p>Bundleへの値の設定と読み出しをこのクラス内で完結させる。
     *
     * @param context コンテキスト
     * @param udn     MediaServerのUDN
     * @return インスタンス
     */
    @NonNull
    public static Intent makeIntent(@NonNull Context context, @NonNull String udn) {
        final Intent intent = new Intent(context, CdsListActivity.class);
        intent.putExtra(Const.EXTRA_SERVER_UDN, udn);
        return intent;
    }

    private static class History implements Parcelable {
        private final int mPosition;
        private final String mId;
        private final String mTitle;

        public History(int position, @NonNull String id, @NonNull String title) {
            mPosition = position;
            mId = id;
            mTitle = title;
        }

        public int getPosition() {
            return mPosition;
        }

        public String getId() {
            return mId;
        }

        public String getTitle() {
            return mTitle;
        }

        private History(Parcel in) {
            mPosition = in.readInt();
            mId = in.readString();
            mTitle = in.readString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mPosition);
            dest.writeString(mId);
            dest.writeString(mTitle);
        }

        public static final Creator<History> CREATOR = new Creator<History>() {
            @Override
            public History createFromParcel(Parcel in) {
                return new History(in);
            }

            @Override
            public History[] newArray(int size) {
                return new History[size];
            }
        };
    }

    private void onCdsItemClick(final View v, int position, CdsObject object) {
        if (object.isContainer()) {
            browse(position, object.getObjectId(), object.getTitle(), true);
            return;
        }
        if (mTwoPane) {
            if (mSelectedObject != null && mSelectedObject.equals(object)) {
                return;
            }
            mCdsDetailFragment = CdsDetailFragment.newInstance(mServer.getUdn(), object);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mCdsDetailFragment.setEnterTransition(new Slide(Gravity.START));
            }
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.cdsDetailContainer, mCdsDetailFragment)
                    .commit();
        } else {
            final Intent intent = CdsDetailActivity.makeIntent(v.getContext(), mServer.getUdn(), object);
            startActivity(intent, ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight()).toBundle());
        }
        mSelectedObject = object;
        mCdsListAdapter.setSelection(position);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler();
        final String udn = getIntent().getStringExtra(Const.EXTRA_SERVER_UDN);
        mServer = mDataHolder.getMsControlPoint().getDevice(udn);
        if (mServer == null) {
            finish();
            return;
        }
        final String name = mServer.getFriendlyName();
        setContentView(R.layout.cds_list_activity);
        final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        final ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setTitle(name);

        ToolbarThemeUtils.setCdsListTheme(this, mServer, toolbar);

        mSwipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipeRefresh);
        mSwipeRefreshLayout.setColorSchemeResources(
                R.color.progress1, R.color.progress2, R.color.progress3, R.color.progress4);
        mSwipeRefreshLayout.setOnRefreshListener(() -> {
            mDataHolder.popCache();
            reload();
        });
        mCdsListAdapter = new CdsListAdapter(this);
        mCdsListAdapter.setOnItemClickListener(this::onCdsItemClick);
        mRecyclerView = (RecyclerView) findViewById(R.id.cdsList);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(mCdsListAdapter);
        mRecyclerView.addItemDecoration(new DividerItemDecoration(this));

        if (findViewById(R.id.cdsDetailContainer) != null) {
            mTwoPane = true;
        }
        if (savedInstanceState == null) {
            browse(0, "0", "", true);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        final History[] histories = (History[]) savedInstanceState.getParcelableArray(KEY_HISTORY);
        Collections.addAll(mHistories, histories);
        final History history = mHistories.peekLast();
        if (history.getId().equals(mDataHolder.getCurrentContainer())) {
            prepareViewState();
            updateListView(mDataHolder.getCurrentList(), true);
        } else {
            browse(history.getPosition(), history.getId(), history.getTitle(), false);
        }
        mSelectedObject = savedInstanceState.getParcelable(KEY_SELECTED);
        final int position = savedInstanceState.getInt(KEY_POSITION, -1);
        mCdsListAdapter.setSelection(position);
        if (position >= 0) {
            mRecyclerView.scrollToPosition(position);
        }
        restoreDetailFragment();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        removeDetailFragment();
        super.onSaveInstanceState(outState);
        outState.putParcelableArray(KEY_HISTORY, mHistories.toArray(new History[mHistories.size()]));
        outState.putInt(KEY_POSITION, mCdsListAdapter.getSelection());
        if (mSelectedObject != null) {
            outState.putParcelable(KEY_SELECTED, mSelectedObject);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBrowseResult != null) {
            mBrowseResult.cancel(true);
        }
        if (isFinishing()) {
            mDataHolder.clearCache();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        restoreDetailFragment();
    }

    private void restoreDetailFragment() {
        if (mTwoPane && mSelectedObject != null && mCdsDetailFragment == null) {
            mCdsDetailFragment = CdsDetailFragment.newInstance(mServer.getUdn(), mSelectedObject);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.cdsDetailContainer, mCdsDetailFragment)
                    .commit();
        }
    }

    @Override
    public void onBackPressed() {
        if (mHistories.size() <= 1) {
            super.onBackPressed();
        } else {
            if (mBrowseResult != null) {
                mBrowseResult.cancel(true);
            }
            mHandler.removeCallbacks(mUpdateListView);
            final History history = mHistories.removeLast();
            final int position = history.getPosition();
            if (!mDataHolder.getCurrentContainer().equals(mHistories.peekLast().getId())) {
                mDataHolder.popCache();
            }
            prepareViewState();
            updateListView(mDataHolder.getCurrentList(), true);
            mCdsListAdapter.setSelection(position);
            final LinearLayoutManager llm = (LinearLayoutManager) mRecyclerView.getLayoutManager();
            llm.scrollToPositionWithOffset(position, mRecyclerView.getHeight() / 3);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        switch (id) {
            case R.id.action_settings:
                startActivity(SettingsActivity.makeIntent(this));
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void browse(int position, String objectId, String title, boolean push) {
        if (mBrowseResult != null) {
            mBrowseResult.cancel(true);
        }
        if (push) {
            mHistories.addLast(new History(position, objectId, title));
        }
        prepareViewState();
        reload();
    }

    private void removeDetailFragment() {
        if (!mTwoPane || mCdsDetailFragment == null) {
            return;
        }
        getSupportFragmentManager()
                .beginTransaction()
                .remove(mCdsDetailFragment)
                .commit();
        mCdsDetailFragment = null;
    }

    private void prepareViewState() {
        removeDetailFragment();
        mCdsListAdapter.clear();
        mCdsListAdapter.clearSelection();
        mCdsListAdapter.notifyDataSetChanged();
        mSelectedObject = null;
        final StringBuilder sb = new StringBuilder();
        for (final ListIterator<History> i = mHistories.listIterator(mHistories.size());
             i.hasPrevious(); ) {
            final History history = i.previous();
            if (sb.length() != 0 && !history.getId().equals("0")) {
                sb.append(" < ");
            }
            sb.append(history.getTitle());
        }
        mSubtitle = sb.toString();
        final ActionBar actionBar = getSupportActionBar();
        actionBar.setSubtitle(mSubtitle);
    }

    private void reload() {
        mSwipeRefreshLayout.setRefreshing(true);
        mCdsListAdapter.clear();
        mCdsListAdapter.notifyDataSetChanged();
        final History history = mHistories.peekLast();
        mBrowseResult = mServer.browse(history.getId());
        mBrowseResult.setStatusListener(this);
    }

    @Override
    public void onCompletion(@NonNull BrowseResult result) {
        if (mBrowseResult.isCancelled()) {
            return;
        }
        final History history = mHistories.peekLast();
        try {
            final List<CdsObject> list = result.get();
            mDataHolder.pushCache(history.getId(), list);
            updateListViewAsync(list, true);
        } catch (InterruptedException | ExecutionException ignored) {
            // 完了状態のためこれらExceptionが発生することはない
        }
    }

    @Override
    public void onProgressUpdate(@NonNull BrowseResult result) {
        updateListViewAsync(result.getProgress(), false);
    }

    private void updateListViewAsync(final List<CdsObject> result, final boolean completion) {
        mHandler.removeCallbacks(mUpdateListView);
        mUpdateListView.set(result, completion);
        mHandler.post(mUpdateListView);
    }

    private final UpdateListView mUpdateListView = new UpdateListView();

    private class UpdateListView implements Runnable {
        private List<CdsObject> mResult;
        private boolean mCompletion;

        public void set(List<CdsObject> result, boolean completion) {
            mResult = result;
            mCompletion = completion;
        }

        @Override
        public void run() {
            updateListView(mResult, mCompletion);
        }
    }

    private void updateListView(List<CdsObject> result, boolean completion) {
        final int beforeCount = mCdsListAdapter.getItemCount();
        mCdsListAdapter.clear();
        if (result != null) {
            mCdsListAdapter.addAll(result);
        }
        if (completion) {
            mSwipeRefreshLayout.setRefreshing(false);
        }
        final int count = mCdsListAdapter.getItemCount();
        final ActionBar actionBar = getSupportActionBar();
        assert actionBar != null;
        actionBar.setSubtitle("[" + count + "] " + mSubtitle);
        mCdsListAdapter.notifyItemRangeInserted(beforeCount, count - beforeCount);
    }
}
