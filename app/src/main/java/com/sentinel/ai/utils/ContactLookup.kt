package com.sentinel.ai.utils

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.CallLog

object ContactLookup {
    fun getContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null
        val hasPermission = PermissionUtils.hasReadContacts(context)
        if (!hasPermission) {
            // Fallback: try call log cached name if READ_CONTACTS is missing but READ_CALL_LOG is granted
            return getNameFromCallLog(context, phoneNumber)
        }

        val candidates = buildCandidates(phoneNumber)
        val projectionLookup = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        // 1) Try fast PhoneLookup for each candidate
        for (candidate in candidates.distinct()) {
            val uri: Uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(candidate)
            )
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, projectionLookup, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            } catch (_: Exception) {
                // ignore and try next variant
            } finally {
                cursor?.close()
            }
        }

        // 2) Fallback: iterate Phone table and compare normalized digits (handles OEM quirks)
        val projectionPhone = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
        )
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projectionPhone,
                null,
                null,
                null
            )
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(0)
                    val number = cursor.getString(1)
                    val normalized = cursor.getString(2)
                    if (matchesAnyCandidate(number, normalized, candidates)) {
                        return displayName
                    }
                }
            }
        } catch (_: Exception) {
            // fall through to null
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun getNameFromCallLog(context: Context, phoneNumber: String): String? {
        if (!PermissionUtils.hasCallLogPermission(context)) return null
        val normalizedTargets = buildCandidates(phoneNumber).map { normalize(it) }.toSet()
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME)
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )
            if (cursor == null) return null
            while (cursor.moveToNext()) {
                val num = cursor.getString(0)?.let { normalize(it) } ?: continue
                val cachedName = cursor.getString(1)
                if (cachedName.isNullOrBlank()) continue
                if (normalizedTargets.contains(num)) return cachedName
                if (num.startsWith("66") && normalizedTargets.contains("0" + num.removePrefix("66"))) return cachedName
                if (num.startsWith("0") && normalizedTargets.contains("66" + num.removePrefix("0"))) return cachedName
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun buildCandidates(phoneNumber: String): List<String> {
        val raw = phoneNumber.replace(Regex("[^0-9+]"), "")
        val normalizedRaw = normalize(raw)
        return buildList {
            add(normalizedRaw)
            // +66xxxx -> 0xxxx (Thai numbers)
            if (normalizedRaw.startsWith("66") && normalizedRaw.length in 10..12) add("0" + normalizedRaw.removePrefix("66"))
            // 0xxxx -> 66xxxx for contacts saved with country code
            if (normalizedRaw.startsWith("0") && normalizedRaw.length in 9..11) add("66" + normalizedRaw.removePrefix("0"))
            // +66xxxx (explicit E164)
            if (!normalizedRaw.startsWith("+") && normalizedRaw.startsWith("66")) add("+$normalizedRaw")
            if (!normalizedRaw.startsWith("+") && normalizedRaw.startsWith("0")) add("+66" + normalizedRaw.removePrefix("0"))
        }
    }

    private fun normalize(number: String?): String {
        if (number.isNullOrBlank()) return ""
        var d = number.replace(Regex("[^0-9+]"), "")
        if (d.startsWith("+")) d = d.removePrefix("+")
        return d
    }

    private fun matchesAnyCandidate(number: String?, normalized: String?, candidates: List<String>): Boolean {
        if (number.isNullOrBlank() && normalized.isNullOrBlank()) return false
        val normNumber = normalize(number)
        val normNormalized = normalize(normalized)
        val candidateSet = candidates.map { normalize(it) }.toSet()
        if (candidateSet.contains(normNumber)) return true
        if (candidateSet.contains(normNormalized)) return true
        // Try leading zero vs 66 swap on the fly
        return candidateSet.any { candidate ->
            val c = normalize(candidate)
            // 66xxxx vs 0xxxx
            if (c.startsWith("66") && normNumber == "0" + c.removePrefix("66")) return true
            if (c.startsWith("0") && normNumber == "66" + c.removePrefix("0")) return true
            if (c.startsWith("66") && normNormalized == "0" + c.removePrefix("66")) return true
            if (c.startsWith("0") && normNormalized == "66" + c.removePrefix("0")) return true
            false
        }
    }
}
