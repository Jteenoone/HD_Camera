package com.example.hd_camera.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.os.ConfigurationCompat
import com.example.hd_camera.R
import com.example.hd_camera.data.CaptureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class MediaItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateTakenMillis: Long,
    val isVideo: Boolean,
    val durationMillis: Long
) {
    val isRaw: Boolean get() = mimeType.endsWith("dng", ignoreCase = true)
}

/**
 * One day's worth of the grid. [label] is the heading on its own — the Gallery search
 * matches against it — and [title] is the heading with its counts, both already in the
 * user's language.
 */
data class MediaSection(val label: String, val title: String, val items: List<MediaItem>)

/** The chips across the top of the Gallery screen. */
enum class MediaFilter { ALL, PHOTOS, VIDEO, RAW }

/** Reads what the app has written into DCIM/HDCamera. */
object MediaRepository {

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DURATION
    )

    suspend fun load(context: Context, filter: MediaFilter): List<MediaSection> =
        withContext(Dispatchers.IO) { groupByDay(context, query(context, filter)) }

    suspend fun latest(context: Context): MediaItem? =
        withContext(Dispatchers.IO) { query(context, MediaFilter.ALL).firstOrNull() }

    private fun query(context: Context, filter: MediaFilter): List<MediaItem> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<String>()

        conditions += "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + " = ? OR " +
            MediaStore.Files.FileColumns.MEDIA_TYPE + " = ?)"
        arguments += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
        arguments += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            conditions += MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?"
            arguments += CaptureSettings.RELATIVE_PATH + "%"
        } else {
            @Suppress("DEPRECATION")
            conditions += MediaStore.Files.FileColumns.DATA + " LIKE ?"
            arguments += "%/" + CaptureSettings.RELATIVE_PATH + "/%"
        }

        when (filter) {
            MediaFilter.ALL -> Unit
            MediaFilter.PHOTOS -> {
                conditions += MediaStore.Files.FileColumns.MEDIA_TYPE + " = ?"
                arguments += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
            }
            MediaFilter.VIDEO -> {
                conditions += MediaStore.Files.FileColumns.MEDIA_TYPE + " = ?"
                arguments += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            }
            MediaFilter.RAW -> {
                conditions += MediaStore.Files.FileColumns.MIME_TYPE + " = ?"
                arguments += "image/x-adobe-dng"
            }
        }

        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection,
            projection,
            conditions.joinToString(" AND "),
            arguments.toTypedArray(),
            MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val dateColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val durationColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val isVideo = cursor.getInt(typeColumn) ==
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                items += MediaItem(
                    uri = collection.buildUpon().appendPath(id.toString()).build(),
                    displayName = cursor.getString(nameColumn).orEmpty(),
                    mimeType = cursor.getString(mimeColumn).orEmpty(),
                    // DATE_MODIFIED is in seconds.
                    dateTakenMillis = cursor.getLong(dateColumn) * 1000L,
                    isVideo = isVideo,
                    durationMillis = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
                )
            }
        }
        return items
    }

    /** "TODAY · 8 PHOTOS" and friends, exactly as the design groups the grid. */
    private fun groupByDay(context: Context, items: List<MediaItem>): List<MediaSection> {
        if (items.isEmpty()) return emptyList()
        // The heading follows the device language, so the date format follows it too.
        val locale = ConfigurationCompat.getLocales(context.resources.configuration)
            .get(0) ?: Locale.getDefault()
        val dayFormat = SimpleDateFormat("MMM d, yyyy", locale)
        val today = startOfDay(System.currentTimeMillis())
        val yesterday = today - DAY_MILLIS

        return items
            .groupBy { startOfDay(it.dateTakenMillis) }
            .toSortedMap(compareByDescending { it })
            .map { (day, dayItems) ->
                val label = when (day) {
                    today -> context.getString(R.string.gallery_section_today)
                    yesterday -> context.getString(R.string.gallery_section_yesterday)
                    else -> dayFormat.format(Date(day)).uppercase(locale)
                }
                MediaSection(label, sectionTitle(context, label, dayItems), dayItems)
            }
    }

    /** e.g. "TODAY · 8 PHOTOS · 2 VIDEOS", with the counts pluralised by the locale. */
    fun sectionTitle(context: Context, label: String, items: List<MediaItem>): String {
        val photos = items.count { !it.isVideo }
        val videos = items.size - photos
        val counts = buildList {
            if (photos > 0) {
                add(context.resources.getQuantityString(R.plurals.photo_count, photos, photos))
            }
            if (videos > 0) {
                add(context.resources.getQuantityString(R.plurals.video_count, videos, videos))
            }
        }
        if (counts.isEmpty()) return label
        val separator = context.getString(R.string.gallery_section_separator)
        return context.getString(
            R.string.gallery_section_title,
            label,
            counts.joinToString(separator)
        )
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
}
