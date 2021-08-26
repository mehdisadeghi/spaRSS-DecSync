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

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlinx.serialization.json.JsonPrimitive
import org.decsync.library.Decsync
import org.decsync.library.DecsyncItem
import org.decsync.library.properties.Rss
import org.decsync.sparss.provider.FeedData
import org.decsync.sparss.utils.DecsyncUtils.getDecsync
import org.decsync.sparss.utils.DecsyncUtils.getMyDecsyncObserver
import java.util.*

@ExperimentalStdlibApi
object DB {
    @JvmOverloads
    @JvmStatic
    fun insert(context: Context, uri: Uri, values: ContentValues, updateDecsync: Boolean = true): Uri? {
        val cr = context.contentResolver
        val result = cr.insert(uri, values)
        if (PrefUtils.getBoolean(PrefUtils.DECSYNC_ENABLED, false)) {
            valuesToDecsyncItem(context, uri, values)?.let { decsyncItem ->
                getMyDecsyncObserver(context).applyDiff(insertions = listOf(decsyncItem), isFromDecsyncListener = !updateDecsync)
            }
        }
        return result
    }

    @JvmOverloads
    @JvmStatic
    fun update(context: Context, uri: Uri, values: ContentValues, selection: String?, selectionArgs: Array<String>?, updateDecsync: Boolean = true): Int {
        val cr = context.contentResolver
        if (PrefUtils.getBoolean(PrefUtils.DECSYNC_ENABLED, false)) {
            val changes = mutableListOf<Pair<DecsyncItem, DecsyncItem>>()
            cr.query(uri, null, selection, selectionArgs, null)!!.use { cursor ->
                cursor.moveToFirst()
                while (!cursor.isAfterLast) {
                    cursorToDecsyncItem(context, uri, cursor, null)?.let { oldItem ->
                        cursorToDecsyncItem(context, uri, cursor, values)?.let { newItem ->
                            changes.add(oldItem to newItem)
                        }
                    }
                    cursor.moveToNext()
                }
            }
            getMyDecsyncObserver(context).applyDiff(changes = changes, isFromDecsyncListener = !updateDecsync)
        }
        return cr.update(uri, values, selection, selectionArgs)
    }

    @JvmOverloads
    @JvmStatic
    fun delete(context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?, updateDecsync: Boolean = true): Int {
        val cr = context.contentResolver
        if (PrefUtils.getBoolean(PrefUtils.DECSYNC_ENABLED, false)) {
            val deletions = mutableListOf<DecsyncItem>()
            cr.query(uri, null, selection, selectionArgs, null)!!.use { cursor ->
                cursor.moveToFirst()
                while (!cursor.isAfterLast) {
                    cursorToDecsyncItem(context, uri, cursor, null)?.let { decsyncItem ->
                        deletions.add(decsyncItem)
                    }
                    cursor.moveToNext()
                }
            }
            getMyDecsyncObserver(context).applyDiff(deletions = deletions, isFromDecsyncListener = !updateDecsync)
        }
        return cr.delete(uri, selection, selectionArgs)
    }

    private fun valuesToDecsyncItem(context: Context, uri: Uri, values: ContentValues): DecsyncItem? {
        val cr = context.contentResolver
        return when (cr.getType(uri)) {
            "vnd.android.cursor.item/vnd.spaRSS.entry", "vnd.android.cursor.dir/vnd.spaRSS.entry" -> {
                val guid = values.getAsString(FeedData.EntryColumns.GUID)
                val read = values.getAsBoolean(FeedData.EntryColumns.IS_READ) ?: false
                val marked = values.getAsBoolean(FeedData.EntryColumns.IS_FAVORITE) ?: false
                val date = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                date.timeInMillis = values.getAsLong(FeedData.EntryColumns.DATE)
                val year = date[Calendar.YEAR]
                val month = date[Calendar.MONTH] + 1
                val day = date[Calendar.DAY_OF_MONTH]
                Rss.Article(guid, read, marked, year, month, day)
            }
            "vnd.android.cursor.item/vnd.spaRSS.feed", "vnd.android.cursor.dir/vnd.spaRSS.feed" -> {
                val isGroup = values.getAsBoolean(FeedData.FeedColumns.IS_GROUP)
                val url = values.getAsString(FeedData.FeedColumns.URL)
                val name = values.getAsString(FeedData.FeedColumns.NAME)
                val groupId = values.getAsString(FeedData.FeedColumns.GROUP_ID)
                if (isGroup == true) {
                    Rss.Category(url, name, null) {
                        // We do not support nested categories
                        // Only changes are detected, so always giving the default value of null is fine
                        throw Exception("Cannot get catID of unsupported nested categories. This should never happen.")
                    }
                } else {
                    Rss.Feed(url, name, groupId) {
                        groupToCategory(groupId, context)
                    }
                }
            }
            else -> null
        }
    }

    private fun cursorToDecsyncItem(context: Context, uri: Uri, cursor: Cursor, newValues: ContentValues?): DecsyncItem? {
        val cr = context.contentResolver
        return when (cr.getType(uri)) {
            "vnd.android.cursor.item/vnd.spaRSS.entry", "vnd.android.cursor.dir/vnd.spaRSS.entry" -> {
                val oldGuid = cursor.getString(cursor.getColumnIndex(FeedData.EntryColumns.GUID))
                val oldRead = cursor.getInt(cursor.getColumnIndex(FeedData.EntryColumns.IS_READ)) == 1
                val oldMarked = cursor.getInt(cursor.getColumnIndex(FeedData.EntryColumns.IS_FAVORITE)) == 1
                val oldTime = cursor.getLong(cursor.getColumnIndex(FeedData.EntryColumns.DATE))
                val guid = if (newValues?.containsKey(FeedData.EntryColumns.GUID) == true) newValues.getAsString(FeedData.EntryColumns.GUID) else oldGuid
                val read = if (newValues?.containsKey(FeedData.EntryColumns.IS_READ) == true) newValues.getAsBoolean(FeedData.EntryColumns.IS_READ) else oldRead
                val marked = if (newValues?.containsKey(FeedData.EntryColumns.IS_FAVORITE) == true) newValues.getAsBoolean(FeedData.EntryColumns.IS_FAVORITE) else oldMarked
                val time = if (newValues?.containsKey(FeedData.EntryColumns.DATE) == true) newValues.getAsLong(FeedData.EntryColumns.DATE) else oldTime
                val date = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                date.timeInMillis = time
                val year = date[Calendar.YEAR]
                val month = date[Calendar.MONTH] + 1
                val day = date[Calendar.DAY_OF_MONTH]
                Rss.Article(guid, read, marked, year, month, day)
            }
            "vnd.android.cursor.item/vnd.spaRSS.feed", "vnd.android.cursor.dir/vnd.spaRSS.feed" -> {
                val oldUrl = cursor.getString(cursor.getColumnIndex(FeedData.FeedColumns.URL))
                val oldName = cursor.getString(cursor.getColumnIndex(FeedData.FeedColumns.NAME))
                val oldGroupId = cursor.getString(cursor.getColumnIndex(FeedData.FeedColumns.GROUP_ID))
                val isGroup = cursor.getInt(cursor.getColumnIndex(FeedData.FeedColumns.IS_GROUP)) == 1
                val url = if (newValues?.containsKey(FeedData.FeedColumns.URL) == true) newValues.getAsString(FeedData.FeedColumns.URL) else oldUrl
                val name = if (newValues?.containsKey(FeedData.FeedColumns.NAME) == true) newValues.getAsString(FeedData.FeedColumns.NAME) else oldName
                val groupId = if (newValues?.containsKey(FeedData.FeedColumns.GROUP_ID) == true) newValues.getAsString(FeedData.FeedColumns.GROUP_ID) else oldGroupId
                if (isGroup) {
                    Rss.Category(url, name, null) {
                        // We do not support nested categories
                        // Only changes are detected, so always giving the default value of null is fine
                        throw Exception("Cannot get catID of unsupported nested categories. This should never happen.")
                    }
                } else {
                    Rss.Feed(url, name, groupId) {
                        groupToCategory(groupId, context)
                    }
                }
            }
            else -> null
        }
    }

    private fun groupToCategory(groupId: String?, context: Context): String? {
        if (groupId == null) {
            return null
        }
        val cr = context.contentResolver
        cr.query(FeedData.FeedColumns.GROUPS_CONTENT_URI(groupId),
                arrayOf(FeedData.FeedColumns.URL, FeedData.FeedColumns.NAME),
                null, null, null)!!.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val categoryOld = cursor.getString(0)
            if (categoryOld != null) return categoryOld
            val name = cursor.getString(1)

            val categoryNew = UUID.randomUUID().toString()
            val values = ContentValues()
            values.put(FeedData.FeedColumns.URL, categoryNew)
            cr.update(FeedData.FeedColumns.CONTENT_URI(groupId), values, null, null)
            getDecsync(context)?.setEntry(listOf("categories", "names"), JsonPrimitive(categoryNew), JsonPrimitive(name))
            return categoryNew
        }
    }

    fun feedUrlToFeedId(feedUrl: String?, cr: ContentResolver): String? {
        if (feedUrl == null || feedUrl.isEmpty()) return null
        var feedId: String? = null
        cr.query(FeedData.FeedColumns.CONTENT_URI, FeedData.FeedColumns.PROJECTION_ID,
                FeedData.FeedColumns.URL + "=?", arrayOf(feedUrl), null)!!.use { cursor ->
            if (cursor.moveToFirst()) {
                feedId = cursor.getLong(0).toString()
            }
        }
        return feedId
    }
}
