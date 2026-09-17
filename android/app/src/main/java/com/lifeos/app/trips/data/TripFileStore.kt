package com.lifeos.app.trips.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import javax.inject.Inject
import javax.inject.Singleton

data class StoredTripFile(val uri: String, val displayName: String)

@Singleton
class TripFileStore @Inject constructor(@ApplicationContext private val context: Context) {
    fun copyIntoTrip(tripId: String, source: Uri): StoredTripFile {
        val displayName = context.contentResolver.query(
            source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "attachment"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val directory = File(context.filesDir, "trips/$tripId/attachments").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}-$safeName")
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "The selected file could not be opened." }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return StoredTripFile(Uri.fromFile(target).toString(), displayName)
    }

    fun requestBody(uri: String): RequestBody {
        val file = File(requireNotNull(Uri.parse(uri).path) { "Attachment path is missing." })
        val type = context.contentResolver.getType(Uri.fromFile(file))?.toMediaTypeOrNull()
        return file.asRequestBody(type)
    }
}
