package com.nexttodo.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class EventStore(private val context: Context) :
    SQLiteOpenHelper(context, "events.db", null, 2) {

    private val attachments = File(context.filesDir, "attachments")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE events (
                id TEXT PRIMARY KEY NOT NULL,
                completed INTEGER NOT NULL,
                details TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2)
        db.execSQL("ALTER TABLE events ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
    }

    fun readAll(): List<Event> = readEvents()

    fun findEvent(id: String): Event? = readEvents("id = ?", arrayOf(id)).firstOrNull()

    private fun readEvents(selection: String? = null, args: Array<String>? = null): List<Event> = readableDatabase.query(
        "events", arrayOf("id", "completed", "details"),
        selection, args, null, null, "sort_order ASC, rowid DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val json = JSONObject(cursor.getString(2))
                val fields = json.getJSONArray("fields")
                add(
                    Event(
                        id = cursor.getString(0),
                        completed = cursor.getInt(1) != 0,
                        type = EventType.valueOf(json.getString("type")),
                        title = json.getString("title"),
                        description = json.getString("description"),
                        time = json.getString("time"),
                        reminderEnabled = json.optBoolean("reminderEnabled"),
                        endTime = json.optString("endTime"),
                        endDay = json.optString("endDay").takeIf(String::isNotBlank)?.let(LocalDate::parse),
                        repetition = EventRepeat.entries.find { it.name == json.optString("repetition") } ?: EventRepeat.NONE,
                        repeatDays = json.optJSONArray("repeatDays")?.let { days -> (0 until days.length()).map { days.getInt(it) }.toSet() }.orEmpty(),
                        repeatInterval = json.optInt("repeatInterval", 2).coerceIn(1, 365),
                        repeatWorkDays = json.optInt("repeatWorkDays", 1).coerceIn(1, 365),
                        completedDates = json.optJSONArray("completedDates")?.let { days -> (0 until days.length()).map { LocalDate.parse(days.getString(it)) }.toSet() }.orEmpty(),
                        pinned = json.optBoolean("pinned"),
                        day = json.optString("day").takeIf(String::isNotBlank)?.let(LocalDate::parse),
                        parentId = json.optString("parentId").takeIf { it.isNotBlank() && it != "null" },
                        followsParentDate = json.optBoolean("followsParentDate"),
                        tags = json.optJSONArray("tags")?.let { tags ->
                            (0 until tags.length()).mapNotNull { index ->
                                val tag = tags.optJSONObject(index) ?: return@mapNotNull null
                                val kind = EventTagKind.entries.find { it.name == tag.optString("kind") } ?: return@mapNotNull null
                                EventTag(kind, tag.optString("name", kind.label),
                                    if (tag.isNull("color")) null else tag.optInt("color"),
                                    if (tag.isNull("textColor")) null else tag.optInt("textColor"))
                            }.normalizedTags()
                        }.orEmpty(),
                        originalImage = readAttachment(json.optJSONObject("originalImage")),
                        fields = List(fields.length()) { index ->
                            val field = fields.getJSONObject(index)
                            EventField(
                                name = field.getString("name"),
                                type = FieldType.valueOf(field.getString("type")),
                                value = field.getString("value"),
                                attachment = readAttachment(field.optJSONObject("attachment")),
                                presetFor = field.optString("presetFor").takeIf { it.isNotEmpty() }
                                    ?.let(EventType::valueOf),
                                id = field.getString("id"),
                            )
                        },
                    )
                )
            }
        }
    }

    fun save(event: Event, previous: Event?): Event = change(listOf(event to previous)).single()

    // Related events commit together, including attachment cleanup after a successful commit.
    fun change(updates: List<Pair<Event, Event?>>, deleted: List<Event> = emptyList(), order: List<String>? = null): List<Event> {
        val newFiles = mutableListOf<File>()
        val db = writableDatabase
        val saved = try {
            val prepared = updates.map { (event, _) ->
                event.copy(
                    originalImage = copyAttachment(event.originalImage, newFiles),
                    fields = event.fields.map { it.copy(attachment = copyAttachment(it.attachment, newFiles)) },
                )
            }
            db.beginTransaction()
            try {
                prepared.zip(updates).forEach { (savedEvent, original) ->
                    val values = ContentValues().apply {
                        put("completed", savedEvent.completed)
                        put("details", detailsJson(savedEvent).toString())
                    }
                    if (original.second == null) {
                        db.execSQL(
                            """
                            INSERT INTO events (id, completed, details, sort_order)
                            VALUES (?, ?, ?, (SELECT COALESCE(MIN(sort_order), 0) - 1 FROM events))
                            """.trimIndent(),
                            arrayOf(savedEvent.id, if (savedEvent.completed) 1 else 0, values.getAsString("details")),
                        )
                    } else {
                        check(db.update("events", values, "id = ?", arrayOf(savedEvent.id)) == 1)
                    }
                }
                deleted.forEach { check(db.delete("events", "id = ?", arrayOf(it.id)) == 1) }
                val hierarchy = readAll().associateBy { it.id }
                hierarchy.values.forEach { event -> event.parentId?.let { parentId ->
                    require(parentId != event.id && hierarchy[parentId]?.parentId == null && hierarchy.containsKey(parentId)) {
                        "子事件需要关联一个有效的主事件"
                    }
                } }
                if (order != null) saveOrder(order)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            prepared
        } catch (e: Exception) {
            newFiles.forEach { it.delete() }
            throw e
        }
        val retained = saved.flatMap(::attachmentIds).toSet()
        val oldFiles = (updates.mapNotNull { it.second } + deleted).flatMap(::attachmentIds).toSet()
        (oldFiles - retained).forEach { File(attachments, it).delete() }
        return saved
    }

    fun setCompleted(id: String, completed: Boolean) {
        val values = ContentValues().apply { put("completed", completed) }
        check(writableDatabase.update("events", values, "id = ?", arrayOf(id)) == 1)
    }

    fun delete(event: Event) {
        change(emptyList(), listOf(event))
    }

    fun saveOrder(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("UPDATE events SET sort_order = ? WHERE id = ?").use { update ->
                ids.forEachIndexed { index, id ->
                    update.bindLong(1, index.toLong())
                    update.bindString(2, id)
                    check(update.executeUpdateDelete() == 1)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun copyAttachment(source: Attachment?, newFiles: MutableList<File>): Attachment? {
        if (source == null || source.localId != null) return source
        check(attachments.isDirectory || attachments.mkdirs())
        val uri = Uri.parse(source.uri)
        val extension = source.name.substringAfterLast('.', "")
            .takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
        val id = UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: "")
        val file = File(attachments, id)
        newFiles.add(file)
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("附件无法读取")
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return Attachment(fileUri(id), source.name, id)
    }

    private fun fileUri(id: String) =
        FileProvider.getUriForFile(context, "${context.packageName}.attachments", File(attachments, id))
            .toString()

    private fun readAttachment(json: JSONObject?): Attachment? = json?.let {
        val id = it.getString("id")
        Attachment(fileUri(id), it.getString("name"), id)
    }

    private fun attachmentIds(event: Event?): Set<String> = buildSet {
        event?.originalImage?.localId?.let(::add)
        event?.fields?.forEach { it.attachment?.localId?.let(::add) }
    }

    private fun attachmentJson(attachment: Attachment?) = attachment?.let {
        JSONObject().put("id", it.localId).put("name", it.name)
    }

    private fun detailsJson(event: Event) = JSONObject()
        .put("type", event.type.name)
        .put("title", event.title)
        .put("description", event.description)
        .put("time", event.time)
        .put("reminderEnabled", event.reminderEnabled)
        .put("endTime", event.endTime).put("endDay", event.endDay?.toString())
        .put("repetition", event.repetition.name).put("repeatDays", JSONArray(event.repeatDays.toList()))
        .put("repeatInterval", event.repeatInterval).put("pinned", event.pinned)
        .put("repeatWorkDays", event.repeatWorkDays)
        .put("completedDates", JSONArray(event.completedDates.map(LocalDate::toString)))
        .put("day", event.day?.toString())
        .put("parentId", event.parentId).put("followsParentDate", event.followsParentDate)
        .put("tags", JSONArray(event.tags.normalizedTags().map { tag ->
            JSONObject().put("kind", tag.kind.name).put("name", tag.name).put("color", tag.color).put("textColor", tag.textColor)
        }))
        .put("originalImage", attachmentJson(event.originalImage))
        .put("fields", JSONArray(event.fields.map { field ->
            JSONObject()
                .put("name", field.name)
                .put("type", field.type.name)
                .put("value", field.value)
                .put("attachment", attachmentJson(field.attachment))
                .put("presetFor", field.presetFor?.name)
                .put("id", field.id)
        }))
}

internal fun selectedAttachment(context: Context, uri: Uri, fallbackName: String): Attachment {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    return Attachment(uri.toString(), name?.takeIf { it.isNotBlank() } ?: fallbackName)
}
