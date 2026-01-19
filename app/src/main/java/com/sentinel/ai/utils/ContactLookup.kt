package com.sentinel.ai.utils

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract

object ContactLookup {
    fun getContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null
        val hasPermission = PermissionUtils.hasReadContacts(context)
        if (!hasPermission) return null

        // Normalize: remove dashes, spaces, etc. to ensure we match raw numbers
        val normalized = phoneNumber.replace(Regex("[^0-9+]"), "")
        val uri: Uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalized)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME
        )
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0)
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}
