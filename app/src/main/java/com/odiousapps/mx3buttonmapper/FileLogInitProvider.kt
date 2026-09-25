package com.odiousapps.mx3buttonmapper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Does nothing but call AppLog.init() from onCreate(). Declared in the
 * manifest purely so the platform instantiates it: ContentProviders are
 * created before any other manifest component in the same process --
 * including ButtonMapperService, whose AccessibilityService auto-connects
 * on every boot -- which makes this the earliest possible hook to point
 * AppLog at a real, writable log file before anything else in this
 * process gets a chance to log a single line. Never queried, so every
 * CRUD method below is an unreachable no-op.
 */
class FileLogInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { AppLog.init(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
