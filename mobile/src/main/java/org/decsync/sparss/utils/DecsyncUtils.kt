/**
 * spaRSS DecSync
 * <p/>
 * Copyright (c) 2018 Aldo Gunsing
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

package org.decsync.sparss.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.decsync.library.*
import org.decsync.sparss.Constants
import org.decsync.sparss.R
import org.decsync.sparss.provider.FeedData
import org.decsync.sparss.provider.FeedDataContentProvider.addFeed
import org.decsync.sparss.utils.DB.feedUrlToFeedId
import org.decsync.sparss.worker.FetcherWorker
import java.io.File

val ownAppId = getAppId("spaRSS")
val defaultDecsyncDir = "${Environment.getExternalStorageDirectory()}/DecSync"
private const val TAG = "DecsyncUtils"
private const val CHANNEL_ERROR = "channel_error"
private const val ERROR_NOTIFICATION_ID = 1

class Extra(val context: Context)

@ExperimentalStdlibApi
class MyDecsyncObserver(
        val context: Context
) : DecsyncObserver() {
    override fun isDecsyncEnabled(): Boolean {
        return PrefUtils.getBoolean(PrefUtils.DECSYNC_ENABLED, false)
    }

    override fun setEntries(entries: List<Decsync.EntryWithPath>) {
        DecsyncUtils.getDecsync(context)?.setEntries(entries)
    }

    override fun executeStoredEntries(storedEntries: List<Decsync.StoredEntry>) {
        DecsyncUtils.getDecsync(context)?.executeStoredEntries(storedEntries, Extra(context))
    }
}

@ExperimentalStdlibApi
object DecsyncUtils {
    private var mDecsync: Decsync<Extra>? = null
    private var mDecsyncObserver: MyDecsyncObserver? = null

    private fun getNewDecsync(context: Context): Decsync<Extra> {
        val decsyncDir = getDecsyncDir(context)
        val decsync = Decsync<Extra>(decsyncDir, "rss", null, ownAppId)
        decsync.addListener(listOf("articles", "read")) { path, entry, extra ->
            readMarkListener(true, path, entry, extra)
        }
        decsync.addListener(listOf("articles", "marked")) { path, entry, extra ->
            readMarkListener(false, path, entry, extra)
        }
        decsync.addListener(listOf("feeds", "subscriptions"), ::subscriptionsListener)
        decsync.addListener(listOf("feeds", "names"), ::feedNamesListener)
        decsync.addListener(listOf("feeds", "categories"), ::categoriesListener)
        decsync.addListener(listOf("categories", "names"), ::categoryNamesListener)
        decsync.addListener(listOf("categories", "parents"), ::categoryParentsListener)
        return decsync
    }

    fun getDecsyncDir(context: Context): NativeFile {
        return if (PrefUtils.getBoolean(PrefUtils.DECSYNC_USE_SAF, false)) {
            val uri = DecsyncPrefUtils.getDecsyncDir(context) ?: throw Exception(context.getString(R.string.settings_decsync_dir_not_configured))
            checkUriPermissions(context, uri)
            nativeFileFromDirUri(context, uri)
        } else {
            nativeFileFromFile(File(PrefUtils.getString(PrefUtils.DECSYNC_FILE, defaultDecsyncDir)))
        }
    }

    fun getDecsync(context: Context): Decsync<Extra>? {
        if (mDecsync == null && PrefUtils.getBoolean(PrefUtils.DECSYNC_ENABLED, false)) {
            try {
                mDecsync = getNewDecsync(context)
            } catch (e: Exception) {
                Log.e(TAG, "", e)
                PrefUtils.putBoolean(PrefUtils.DECSYNC_ENABLED, false)

                if (Build.VERSION.SDK_INT >= 26 && Constants.NOTIF_MGR != null) {
                    val channel = NotificationChannel(
                            CHANNEL_ERROR,
                            context.getString(R.string.channel_error_name),
                            NotificationManager.IMPORTANCE_DEFAULT
                    )
                    Constants.NOTIF_MGR.createNotificationChannel(channel)
                }
                val notification = NotificationCompat.Builder(context, CHANNEL_ERROR)
                        .setSmallIcon(R.drawable.ic_statusbar_rss)
                        .setLargeIcon(
                                BitmapFactory.decodeResource(
                                        context.resources,
                                        R.mipmap.ic_launcher
                                )
                        )
                        .setContentTitle(context.getString(R.string.decsync_disabled))
                        .setContentText(e.localizedMessage)
                        .build()
                Constants.NOTIF_MGR?.notify(ERROR_NOTIFICATION_ID, notification)
            }
        }
        return mDecsync
    }

    fun getMyDecsyncObserver(context: Context): MyDecsyncObserver {
        return DecsyncUtils.mDecsyncObserver ?: run {
            MyDecsyncObserver(context).also {
                mDecsyncObserver = it
            }
        }
    }

    fun initSync(context: Context) {
        mDecsync = null
        val inputData = Data.Builder()
                .putString(FetcherWorker.ACTION, FetcherWorker.ACTION_REFRESH_FEEDS)
                .putBoolean(Constants.FROM_INIT_SYNC, true)
                .build()
        val workRequest: WorkRequest = OneTimeWorkRequest.Builder(FetcherWorker::class.java)
                .setInputData(inputData)
                .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    private fun readMarkListener(isReadEntry: Boolean, path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.d(TAG, "Execute " + (if (isReadEntry) "read" else "mark") + " entry $entry")
        val entryColumn = if (isReadEntry) FeedData.EntryColumns.IS_READ else FeedData.EntryColumns.IS_FAVORITE
        val guid = entry.key.jsonPrimitive.content
        val value = entry.value.jsonPrimitive.boolean
        val context = extra.context

        val values = ContentValues()
        if (value) {
            values.put(entryColumn, true)
        } else {
            values.putNull(entryColumn)
        }
        DB.update(context, FeedData.EntryColumns.ALL_ENTRIES_CONTENT_URI, values,
                FeedData.EntryColumns.GUID + "=?", arrayOf(guid), false)
    }

    private fun subscriptionsListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.d(TAG, "Execute subscribe entry $entry")
        val feedUrl = entry.key.jsonPrimitive.content
        val subscribed = entry.value.jsonPrimitive.boolean
        val context = extra.context
        val cr = context.contentResolver

        if (subscribed) {
            addFeed(cr, context, feedUrl, "", false, false)
        } else {
            val feedId = feedUrlToFeedId(feedUrl, cr)
            if (feedId == null) {
                Log.i(TAG, "Unknown feed $feedUrl")
                return
            }
            val groupId = getGroupId(feedId, cr)
            DB.delete(context, FeedData.FeedColumns.CONTENT_URI(feedId), null, null, false)
            if (groupId != null) {
                removeGroupIfEmpty(groupId, context)
            }
        }
    }

    private fun feedNamesListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.d(TAG, "Execute rename entry $entry")
        val feedUrl = entry.key.jsonPrimitive.content
        val name = entry.value.jsonPrimitive.content
        val context = extra.context
        val cr = context.contentResolver

        val feedId = feedUrlToFeedId(feedUrl, cr)
        if (feedId == null) {
            Log.i(TAG, "Unknown feed $feedUrl")
            return
        }
        val values = ContentValues()
        values.put(FeedData.FeedColumns.NAME, name)
        DB.update(context, FeedData.FeedColumns.CONTENT_URI(feedId), values, null, null, false)
    }

    private fun categoriesListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.d(TAG, "Execute move entry $entry")
        val feedUrl = entry.key.jsonPrimitive.content
        val category = entry.value.jsonPrimitive.contentOrNull
        val context = extra.context
        val cr = context.contentResolver

        val feedId = feedUrlToFeedId(feedUrl, cr)
        if (feedId == null) {
            Log.i(TAG, "Unknown feed $feedUrl")
            return
        }
        val oldGroupId = getGroupId(feedId, cr)
        val groupId = categoryToGroupId(category, context)
        val values = ContentValues()
        values.put(FeedData.FeedColumns.GROUP_ID, groupId)
        DB.update(context, FeedData.FeedColumns.CONTENT_URI(feedId), values, null, null, false)
        removeGroupIfEmpty(oldGroupId, context)
    }

    private fun categoryNamesListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.d(TAG, "Execute category rename entry $entry")
        val category = entry.key.jsonPrimitive.content
        val name = entry.value.jsonPrimitive.content
        val context = extra.context
        val cr = context.contentResolver

        val groupId = categoryToOptGroupId(category, cr)
        if (groupId == null) {
            Log.i(TAG, "Unknown category $category")
            return
        }
        val values = ContentValues()
        values.put(FeedData.FeedColumns.NAME, name)
        DB.update(context, FeedData.FeedColumns.CONTENT_URI(groupId), values, null, null, false)
    }

    private fun categoryParentsListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.i(TAG, "Nested folders are not supported in spaRSS")
    }

    private fun getGroupId(feedId: String, cr: ContentResolver): String? {
        var groupId: String? = null
        cr.query(FeedData.FeedColumns.CONTENT_URI(feedId), arrayOf(FeedData.FeedColumns.GROUP_ID),
                null, null, null)!!.use { cursor ->
            if (cursor.moveToFirst()) {
                groupId = cursor.getString(0)
            }
        }
        return groupId
    }

    private fun categoryToOptGroupId(category: String?, cr: ContentResolver): String? {
        if (category == null) {
            return null
        }

        return cr.query(FeedData.FeedColumns.GROUPS_CONTENT_URI, arrayOf(FeedData.FeedColumns._ID),
                FeedData.FeedColumns.URL + "=?", arrayOf(category), null)!!.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
    }

    private fun categoryToGroupId(category: String?, context: Context): String? {
        if (category == null) {
            return null
        }
        val cr = context.contentResolver

        val groupId = categoryToOptGroupId(category, cr)
        if (groupId != null) {
            return groupId
        }

        val values = ContentValues()
        values.put(FeedData.FeedColumns.IS_GROUP, 1)
        values.put(FeedData.FeedColumns.NAME, category)
        values.put(FeedData.FeedColumns.URL, category)
        val newGroupId = DB.insert(context, FeedData.FeedColumns.GROUPS_CONTENT_URI, values, false)?.lastPathSegment ?: return null
        val extra = Extra(context)
        getDecsync(context)?.executeStoredEntry(listOf("categories", "names"), JsonPrimitive(category), extra)
        return newGroupId
    }

    private fun removeGroupIfEmpty(groupId: String?, context: Context) {
        if (groupId == null) return
        val cr = context.contentResolver
        cr.query(FeedData.FeedColumns.CONTENT_URI, FeedData.FeedColumns.PROJECTION_GROUP_ID,
                FeedData.FeedColumns.GROUP_ID + "=?", arrayOf(groupId), null)!!.use { cursor ->
            if (!cursor.moveToFirst()) {
                DB.delete(context, FeedData.FeedColumns.GROUPS_CONTENT_URI(groupId), null, null, false)
            }
        }
    }
}
