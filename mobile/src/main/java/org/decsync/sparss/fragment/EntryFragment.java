/**
 * spaRSS
 * <p/>
 * Copyright (c) 2015-2016 Arnaud Renaud-Goud
 * Copyright (c) 2012-2015 Frederic Julian
 * <p/>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p/>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.decsync.sparss.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.LoaderManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.decsync.sparss.Constants;
import org.decsync.sparss.MainApplication;
import org.decsync.sparss.R;
import org.decsync.sparss.activity.BaseActivity;
import org.decsync.sparss.provider.FeedData;
import org.decsync.sparss.provider.FeedData.EntryColumns;
import org.decsync.sparss.provider.FeedData.FeedColumns;
import org.decsync.sparss.utils.DB;
import org.decsync.sparss.utils.PrefUtils;
import org.decsync.sparss.utils.UiUtils;
import org.decsync.sparss.view.EntryView;
import org.decsync.sparss.worker.FetcherWorker;

public class EntryFragment extends SwipeRefreshFragment implements BaseActivity.OnFullScreenListener, LoaderManager.LoaderCallbacks<Cursor>, EntryView.EntryViewManager {
    private static final String TAG = "EntryFragment";

    private static final String STATE_BASE_URI = "STATE_BASE_URI";
    private static final String STATE_CURRENT_PAGER_POS = "STATE_CURRENT_PAGER_POS";
    private static final String STATE_ENTRIES_IDS = "STATE_ENTRIES_IDS";
    private static final String STATE_INITIAL_ENTRY_ID = "STATE_INITIAL_ENTRY_ID";

    private int mTitlePos = -1, mDatePos, mMobilizedHtmlPos, mAbstractPos, mLinkPos, mIsFavoritePos, mIsReadPos, mEnclosurePos, mAuthorPos, mFeedNamePos, mFeedUrlPos, mFeedIconPos;

    private int mCurrentPagerPos = -1;
    private Uri mBaseUri;
    private long mInitialEntryId = -1;
    private long[] mEntriesIds;

    private boolean mFavorite, mPreferFullText = true;

    private ViewPager mEntryPager;
    private EntryPagerAdapter mEntryPagerAdapter;

    private View mCancelFullscreenBtn;
    private MenuItem mShowFullContentItem;    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        setHasOptionsMenu(true);

        mEntryPagerAdapter = new EntryPagerAdapter();

        super.onCreate(savedInstanceState);
    }

    @Override
    public View inflateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_entry, container, true);

        mCancelFullscreenBtn = rootView.findViewById(R.id.cancelFullscreenBtn);
        mCancelFullscreenBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setImmersiveFullScreen(false);
            }
        });

        mEntryPager = (ViewPager) rootView.findViewById(R.id.pager);
        //mEntryPager.setPageTransformer(true, new DepthPageTransformer());
        mEntryPager.setAdapter(mEntryPagerAdapter);

        if (savedInstanceState != null) {
            mBaseUri = savedInstanceState.getParcelable(STATE_BASE_URI);
            mEntriesIds = savedInstanceState.getLongArray(STATE_ENTRIES_IDS);
            mInitialEntryId = savedInstanceState.getLong(STATE_INITIAL_ENTRY_ID);
            mCurrentPagerPos = savedInstanceState.getInt(STATE_CURRENT_PAGER_POS);
            mEntryPager.getAdapter().notifyDataSetChanged();
            mEntryPager.setCurrentItem(mCurrentPagerPos);
        }

        mEntryPager.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i2) {
            }

            @Override
            public void onPageSelected(int i) {
                mCurrentPagerPos = i;
                mEntryPagerAdapter.onPause(); // pause all webviews
                mEntryPagerAdapter.onResume(); // resume the current webview

                refreshUI(mEntryPagerAdapter.getCursor(i));
            }

            @Override
            public void onPageScrollStateChanged(int i) {
            }
        });

        disableSwipe();

        return rootView;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putParcelable(STATE_BASE_URI, mBaseUri);
        outState.putLongArray(STATE_ENTRIES_IDS, mEntriesIds);
        outState.putLong(STATE_INITIAL_ENTRY_ID, mInitialEntryId);
        outState.putInt(STATE_CURRENT_PAGER_POS, mCurrentPagerPos);

        super.onSaveInstanceState(outState);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        ((BaseActivity) activity).setOnFullscreenListener(this);
    }

    @Override
    public void onDetach() {
        ((BaseActivity) getActivity()).setOnFullscreenListener(null);

        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        mEntryPagerAdapter.onResume();
        PrefUtils.registerOnPrefChangeListener(mPrefListener);

        if (((BaseActivity) getActivity()).isFullScreen()) {
            mCancelFullscreenBtn.setVisibility(View.VISIBLE);
        } else {
            mCancelFullscreenBtn.setVisibility(View.GONE);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mEntryPagerAdapter.onPause();
        PrefUtils.unregisterOnPrefChangeListener(mPrefListener);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.entry, menu);

        if (mFavorite) {
            MenuItem item = menu.findItem(R.id.menu_star);
            item.setTitle(R.string.menu_unstar).setIcon(R.drawable.ic_star);
        }
        mShowFullContentItem = menu.findItem(R.id.menu_switch_full_original);
        mShowFullContentItem.setChecked(mPreferFullText);

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mEntriesIds != null) {
            final Activity activity = getActivity();

            switch (item.getItemId()) {
                case R.id.menu_star: {
                    mFavorite = !mFavorite;

                    if (mFavorite) {
                        item.setTitle(R.string.menu_unstar).setIcon(R.drawable.ic_star);
                    } else {
                        item.setTitle(R.string.menu_star).setIcon(R.drawable.ic_star_border);
                    }

                    final Uri uri = ContentUris.withAppendedId(mBaseUri, mEntriesIds[mCurrentPagerPos]);
                    new Thread() {
                        @Override
                        public void run() {
                            ContentValues values = new ContentValues();
                            values.put(EntryColumns.IS_FAVORITE, mFavorite ? 1 : 0);
                            Context context = MainApplication.getContext();
                            DB.update(context, uri, values, null, null);

                            // Update the cursor
                            ContentResolver cr = context.getContentResolver();
                            Cursor updatedCursor = cr.query(uri, null, null, null, null);
                            updatedCursor.moveToFirst();
                            mEntryPagerAdapter.setUpdatedCursor(mCurrentPagerPos, updatedCursor);
                        }
                    }.start();
                    break;
                }
                case R.id.menu_share: {
                    Cursor cursor = mEntryPagerAdapter.getCursor(mCurrentPagerPos);
                    if (cursor != null) {
                        String link = cursor.getString(mLinkPos);
                        if (link != null) {
                            String title = cursor.getString(mTitlePos);
                            startActivity(Intent.createChooser(
                                    new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, link)
                                            .setType(Constants.MIMETYPE_TEXT_PLAIN), getString(R.string.menu_share)
                            ));
                        }
                    }
                    break;
                }
                case R.id.menu_full_screen: {
                    setImmersiveFullScreen(true);
                    break;
                }
                case R.id.menu_copy_clipboard: {
                    Cursor cursor = mEntryPagerAdapter.getCursor(mCurrentPagerPos);
                    String link = cursor.getString(mLinkPos);
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Copied Text", link);
                    clipboard.setPrimaryClip(clip);

                    Toast.makeText(activity, R.string.copied_clipboard, Toast.LENGTH_SHORT).show();
                    break;
                }
                case R.id.menu_mark_as_unread: {
                    final Uri uri = ContentUris.withAppendedId(mBaseUri, mEntriesIds[mCurrentPagerPos]);
                    new Thread() {
                        @Override
                        public void run() {
                            Context context = MainApplication.getContext();
                            DB.update(context, uri, FeedData.getUnreadContentValues(), null, null);
                        }
                    }.start();
                    activity.finish();
                    break;
                }
                case R.id.menu_open_in_browser: {
                    Cursor cursor = mEntryPagerAdapter.getCursor(mCurrentPagerPos);
                    if (cursor != null) {
                        String link = cursor.getString(mLinkPos);
                        if (link != null) {
                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                            startActivity(browserIntent);
                        }
                    }
                    break;
                }
                case R.id.menu_switch_full_original: {
                    if(mPreferFullText) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mPreferFullText = false;
                                Log.d(TAG, "run: manual call of displayEntry(), fullText=false");
                                mEntryPagerAdapter.displayEntry(mCurrentPagerPos, null, true);
                            }
                        });
                    }else {
                        Cursor cursor = mEntryPagerAdapter.getCursor(mCurrentPagerPos);
                        final boolean alreadyMobilized = !cursor.isNull(mMobilizedHtmlPos);

                        if (alreadyMobilized) {
                            Log.d(TAG, "onOptionsItemSelected: alreadyMobilized");
                            mPreferFullText = true;
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Log.d(TAG, "run: manual call of displayEntry(), fullText=true");
                                    mEntryPagerAdapter.displayEntry(mCurrentPagerPos, null, true);
                                }
                            });
                        } else if (!isRefreshing()) {
                            Log.d(TAG, "onOptionsItemSelected: about to load article...");
                            mPreferFullText = false;
                            ConnectivityManager connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
                            final NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

                            // since we have acquired the networkInfo, we use it for basic checks
                            if (networkInfo != null && networkInfo.getState() == NetworkInfo.State.CONNECTED) {
                                FetcherWorker.Companion.addEntriesToMobilize(Long.toString(mEntriesIds[mCurrentPagerPos]));

                                Data inputData = new Data.Builder()
                                        .putString(FetcherWorker.ACTION, FetcherWorker.ACTION_MOBILIZE_FEEDS)
                                        .build();
                                WorkRequest workRequest = new OneTimeWorkRequest.Builder(FetcherWorker.class)
                                        .setInputData(inputData)
                                        .build();
                                WorkManager.getInstance(activity).enqueue(workRequest);
                            } else {
                                activity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(activity, R.string.network_error, Toast.LENGTH_SHORT).show();
                                    }
                                });
                                Log.d(TAG, "onOptionsItemSelected: cannot load article. no internet connection.");
                            }
                        } else {
                            Log.d(TAG, "onOptionsItemSelected: refreshing already in progress");
                        }
                    }
                    mShowFullContentItem.setChecked(mPreferFullText);
                    break;
                }
                default:
                    break;
            }
        }

        return super.onOptionsItemSelected(item);
    }


    public void setData(Uri uri) {
        mCurrentPagerPos = -1;

        mBaseUri = FeedData.EntryColumns.PARENT_URI(uri.getPath());
        try {
            mInitialEntryId = Long.parseLong(uri.getLastPathSegment());
        } catch (Exception unused) {
            mInitialEntryId = -1;
        }

        if (mBaseUri != null) {
            Bundle b = getActivity().getIntent().getExtras();

            String whereClause = FeedData.shouldShowReadEntries(mBaseUri) ||
                    (b != null && b.getBoolean(Constants.INTENT_FROM_WIDGET, false)) ? null : EntryColumns.WHERE_UNREAD;
            String entriesOrder = PrefUtils.getBoolean(PrefUtils.DISPLAY_OLDEST_FIRST, false) ? Constants.DB_ASC : Constants.DB_DESC;

            // Load the entriesIds list. Should be in a loader... but I was too lazy to do so
            Cursor entriesCursor = MainApplication.getContext().getContentResolver().query(mBaseUri, EntryColumns.PROJECTION_ID,
                    whereClause, null, EntryColumns.DATE + entriesOrder);

            if (entriesCursor != null && entriesCursor.getCount() > 0) {
                mEntriesIds = new long[entriesCursor.getCount()];
                int i = 0;
                while (entriesCursor.moveToNext()) {
                    mEntriesIds[i] = entriesCursor.getLong(0);
                    if (mEntriesIds[i] == mInitialEntryId) {
                        mCurrentPagerPos = i; // To immediately display the good entry
                    }
                    i++;
                }

                entriesCursor.close();
            }
        } else {
            mEntriesIds = null;
        }

        mEntryPagerAdapter.notifyDataSetChanged();
        if (mCurrentPagerPos != -1) {
            mEntryPager.setCurrentItem(mCurrentPagerPos);
        }
    }

    private void refreshUI(Cursor entryCursor) {
        if (entryCursor != null) {
            Log.d(TAG, "refreshUI() called with: " + "entryCursor = [" + entryCursor + "]");
            String feedTitle = entryCursor.isNull(mFeedNamePos) ? entryCursor.getString(mFeedUrlPos) : entryCursor.getString(mFeedNamePos);
            BaseActivity activity = (BaseActivity) getActivity();
            activity.setTitle(feedTitle);

            byte[] iconBytes = entryCursor.getBlob(mFeedIconPos);
            Bitmap bitmap = UiUtils.getScaledBitmap(iconBytes, 24);
            if (bitmap != null) {
                activity.getSupportActionBar().setIcon(new BitmapDrawable(getResources(), bitmap));
            } else {
                activity.getSupportActionBar().setIcon(null);
            }

            mFavorite = entryCursor.getInt(mIsFavoritePos) == 1;
            activity.invalidateOptionsMenu();

            // Listen the mobilizing task
            if (FetcherWorker.Companion.hasMobilizationTask(mEntriesIds[mCurrentPagerPos])) {
                // If the service is not started, start it here to avoid an infinite loading
                if (!PrefUtils.getBoolean(PrefUtils.IS_REFRESHING, false)) {
                    Data inputData = new Data.Builder()
                            .putString(FetcherWorker.ACTION, FetcherWorker.ACTION_MOBILIZE_FEEDS)
                            .build();
                    WorkRequest workRequest = new OneTimeWorkRequest.Builder(FetcherWorker.class)
                            .setInputData(inputData)
                            .build();
                    WorkManager.getInstance(activity).enqueue(workRequest);
                }
            }

            // Mark the article as read
            if (entryCursor.getInt(mIsReadPos) != 1) {
                final Uri uri = ContentUris.withAppendedId(mBaseUri, mEntriesIds[mCurrentPagerPos]);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Context context = MainApplication.getContext();
                        DB.update(context, uri, FeedData.getReadContentValues(), null, null);

                        // Update the cursor
                        ContentResolver cr = context.getContentResolver();
                        Cursor updatedCursor = cr.query(uri, null, null, null, null);
                        updatedCursor.moveToFirst();
                        mEntryPagerAdapter.setUpdatedCursor(mCurrentPagerPos, updatedCursor);
                    }
                }).start();
            }
        }
    }

    private void showEnclosure(Uri uri, String enclosure, int position1, int position2) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, enclosure.substring(position1 + 3, position2)), 0);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_VIEW, uri), 0); // fallbackmode - let the browser handle this
            } catch (Throwable t) {
                Toast.makeText(getActivity(), t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setImmersiveFullScreen(boolean fullScreen) {
        BaseActivity activity = (BaseActivity) getActivity();
        activity.setImmersiveFullScreen(fullScreen);
    }

    @Override
    public void onClickEnclosure() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final String enclosure = mEntryPagerAdapter.getCursor(mCurrentPagerPos).getString(mEnclosurePos);

                final int position1 = enclosure.indexOf(Constants.ENCLOSURE_SEPARATOR);
                final int position2 = enclosure.indexOf(Constants.ENCLOSURE_SEPARATOR, position1 + 3);

                final Uri uri = Uri.parse(enclosure.substring(0, position1));
                final String filename = uri.getLastPathSegment();

                new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.open_enclosure)
                        .setMessage(getString(R.string.file) + ": " + filename)
                        .setPositiveButton(R.string.open_link, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                showEnclosure(uri, enclosure, position1, position2);
                            }
                        }).setNegativeButton(R.string.download_and_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            DownloadManager.Request r = new DownloadManager.Request(uri);
                            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                            r.allowScanningByMediaScanner();
                            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                            DownloadManager dm = (DownloadManager) MainApplication.getContext().getSystemService(Context.DOWNLOAD_SERVICE);
                            dm.enqueue(r);
                        } catch (Exception e) {
                            Toast.makeText(getActivity(), R.string.error, Toast.LENGTH_LONG).show();
                        }
                    }
                }).show();
            }
        });
    }

    @Override
    public void onStartVideoFullScreen() {
        BaseActivity activity = (BaseActivity) getActivity();
        activity.setNormalFullScreen(true);
    }

    @Override
    public void onEndVideoFullScreen() {
        BaseActivity activity = (BaseActivity) getActivity();
        activity.setNormalFullScreen(false);
    }

    @Override
    public FrameLayout getVideoLayout() {
        View layout = getView();
        return (layout == null ? null : (FrameLayout) layout.findViewById(R.id.videoLayout));
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        CursorLoader cursorLoader = new CursorLoader(getActivity(), EntryColumns.CONTENT_URI(mEntriesIds[id]), null, null, null, null);
        cursorLoader.setUpdateThrottle(1000);
        return cursorLoader;
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        if (mBaseUri != null && cursor != null) { // can be null if we do a setData(null) before
            cursor.moveToFirst();

            if (mTitlePos == -1) {
                mTitlePos = cursor.getColumnIndex(EntryColumns.TITLE);
                mDatePos = cursor.getColumnIndex(EntryColumns.DATE);
                mAbstractPos = cursor.getColumnIndex(EntryColumns.ABSTRACT);
                mMobilizedHtmlPos = cursor.getColumnIndex(EntryColumns.MOBILIZED_HTML);
                mLinkPos = cursor.getColumnIndex(EntryColumns.LINK);
                mIsFavoritePos = cursor.getColumnIndex(EntryColumns.IS_FAVORITE);
                mIsReadPos = cursor.getColumnIndex(EntryColumns.IS_READ);
                mEnclosurePos = cursor.getColumnIndex(EntryColumns.ENCLOSURE);
                mAuthorPos = cursor.getColumnIndex(EntryColumns.AUTHOR);
                mFeedNamePos = cursor.getColumnIndex(FeedColumns.NAME);
                mFeedUrlPos = cursor.getColumnIndex(FeedColumns.URL);
                mFeedIconPos = cursor.getColumnIndex(FeedColumns.ICON);
            }

            int position = loader.getId();
            if (position != -1) {
                mEntryPagerAdapter.displayEntry(position, cursor, false);
            }
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mEntryPagerAdapter.setUpdatedCursor(loader.getId(), null);
    }

    @Override
    public void onFullScreenEnabled(boolean isImmersive, boolean isImmersiveFallback) {
        if (!isImmersive && isImmersiveFallback) {
            mCancelFullscreenBtn.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onFullScreenDisabled() {
        mCancelFullscreenBtn.setVisibility(View.GONE);
    }

    @Override
    public void onRefresh() {
        // Nothing to do
    }

    private class EntryPagerAdapter extends PagerAdapter {

        private final SparseArray<EntryView> mEntryViews = new SparseArray<>();

        public EntryPagerAdapter() {
        }

        @Override
        public int getCount() {
            return mEntriesIds != null ? mEntriesIds.length : 0;
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            EntryView view = new EntryView(getActivity());
            mEntryViews.put(position, view);
            container.addView(view);
            view.setListener(EntryFragment.this);
            getLoaderManager().restartLoader(position, null, EntryFragment.this);
            return view;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            getLoaderManager().destroyLoader(position);
            container.removeView((View) object);
            mEntryViews.delete(position);
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        public void displayEntry(int pagerPos, Cursor newCursor, boolean forceUpdate) {
            Log.d(TAG, "displayEntry() called with: " + "pagerPos = [" + pagerPos + "], newCursor = [" + newCursor + "], forceUpdate = [" + forceUpdate + "]");
            EntryView view = mEntryViews.get(pagerPos);
            if (view != null) {
                if (newCursor == null) {
                    newCursor = (Cursor) view.getTag(); // get the old one
                }

                if (newCursor != null && newCursor.moveToFirst()) {
                    String contentText = newCursor.getString(mMobilizedHtmlPos);
                    if (contentText == null || (forceUpdate && !mPreferFullText)) {
                        contentText = newCursor.getString(mAbstractPos);
                        mPreferFullText = false;
                    } else {
                        mPreferFullText = true;
                    }
                    if (contentText == null) {
                        contentText = "";
                        mPreferFullText = false;
                    }

                    String author = newCursor.getString(mAuthorPos);
                    long timestamp = newCursor.getLong(mDatePos);
                    String link = newCursor.getString(mLinkPos);
                    String title = newCursor.getString(mTitlePos);
                    String enclosure = newCursor.getString(mEnclosurePos);

                    view.setHtml(mEntriesIds[pagerPos], title, link, contentText, enclosure, author, timestamp, mPreferFullText);
                    view.setTag(newCursor);

                    if (pagerPos == mCurrentPagerPos) {
                        refreshUI(newCursor);
                    }
                }
                if (mShowFullContentItem != null) {
                    mShowFullContentItem.setChecked(mPreferFullText);
                } else {
                    Log.e(TAG, "displayEntry: mShowFullContentItem == null" );
                }
            }
        }

        public Cursor getCursor(int pagerPos) {
            EntryView view = mEntryViews.get(pagerPos);
            if (view != null) {
                return (Cursor) view.getTag();
            }
            return null;
        }

        public void setUpdatedCursor(int pagerPos, Cursor newCursor) {
            EntryView view = mEntryViews.get(pagerPos);
            if (view != null) {
                Cursor previousUpdatedOne = (Cursor) view.getTag(R.id.updated_cursor);
                if (previousUpdatedOne != null) {
                    previousUpdatedOne.close();
                }
                view.setTag(newCursor);
                view.setTag(R.id.updated_cursor, newCursor);
            }
        }

        public void onResume() {
            if (mEntriesIds != null) {
                EntryView view = mEntryViews.get(mCurrentPagerPos);
                if (view != null) {
                    view.onResume();
                }
            }
        }

        public void onPause() {
            for (int i = 0; i < mEntryViews.size(); i++) {
                mEntryViews.valueAt(i).onPause();
            }
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (PrefUtils.IS_REFRESHING.equals(key)) {
                Log.d(TAG, "onSharedPreferenceChanged() called with: " + "sharedPreferences = [" + sharedPreferences + "], key = [" + key + "]");
                refreshSwipeProgress();
                if (PrefUtils.getBoolean(PrefUtils.IS_REFRESHING, false) == false) {
                    //if refreshing is done, reload
                    mEntryPagerAdapter.displayEntry(mCurrentPagerPos, null, true);
                }
            }
        }
    };
    private void refreshSwipeProgress() {
        if (PrefUtils.getBoolean(PrefUtils.IS_REFRESHING, false)) {
            showSwipeProgress();
        } else {
            hideSwipeProgress();
        }
    }
}

