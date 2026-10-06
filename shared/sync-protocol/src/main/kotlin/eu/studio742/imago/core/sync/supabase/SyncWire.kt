package eu.studio742.imago.core.sync.supabase

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import eu.studio742.imago.core.sync.IncomingChange
import eu.studio742.imago.core.sync.OutgoingChange
import eu.studio742.imago.core.sync.PullPage
import eu.studio742.imago.core.sync.PushResult
import eu.studio742.imago.core.sync.RemoteDevice

/** The JSON shape of the `sync_*` functions (backend/supabase/migrations), in one place. */
internal object SyncWire {
    fun pushParameters(entity: String, changes: List<OutgoingChange>) = buildJsonObject {
        put("p_entity", entity)
        put("p_changes", buildJsonArray { changes.forEach { add(change(it)) } })
    }

    fun pullParameters(entity: String, cursor: Long, limit: Int) = buildJsonObject {
        put("p_entity", entity)
        put("p_cursor", cursor)
        put("p_limit", limit)
    }

    fun conflictParameters(
        entity: String,
        key: String,
        revision: Long,
        payload: kotlinx.serialization.json.JsonObject,
        editedAt: String,
        deviceId: String?,
    ) = buildJsonObject {
        put("p_entity", entity)
        put("p_key", key)
        put("p_revision", revision)
        put("p_payload", payload)
        put("p_edited_at", editedAt)
        put("p_device", deviceId?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun change(change: OutgoingChange) = buildJsonObject {
        put("key", change.key)
        put("baseRevision", change.baseRevision?.let(::JsonPrimitive) ?: JsonNull)
        put("payload", change.payload)
        put("editedAt", change.editedAt)
        put("deviceId", change.deviceId)
        put("deleted", change.deleted)
        change.hints?.let { put("hints", it) }
    }

    fun pushResults(body: JsonElement): List<PushResult> = body.jsonArray.map { element ->
        val result = element.jsonObject
        val key = result.string("key").orEmpty()
        when (val status = result.string("status")) {
            "applied" -> PushResult.Applied(key, result.getValue("revision").jsonPrimitive.long, result.getValue("seq").jsonPrimitive.long)
            "conflict" -> PushResult.Conflict(key, incoming(result.getValue("remote").jsonObject))
            "rejected" -> PushResult.Rejected(key, result.string("reason") ?: "unknown")
            else -> error("Unknown backend result: $status")
        }
    }

    fun pullPage(body: JsonElement): PullPage {
        val page = body.jsonObject
        return PullPage(
            rows = page.getValue("rows").jsonArray.map { incoming(it.jsonObject) },
            cursor = page.getValue("cursor").jsonPrimitive.long,
        )
    }

    fun incoming(row: JsonObject) = IncomingChange(
        key = row.string("key").orEmpty(),
        revision = row.getValue("revision").jsonPrimitive.long,
        seq = row.getValue("seq").jsonPrimitive.long,
        payload = row.getValue("payload").jsonObject,
        editedAt = row.string("edited_at").orEmpty(),
        editedByDevice = row.string("edited_by_device"),
        deletedAt = row.string("deleted_at"),
        hints = row["hints"] as? JsonObject,
    )

    fun devices(body: JsonElement): List<RemoteDevice> = (body as JsonArray).map { element ->
        val row = element.jsonObject
        RemoteDevice(
            id = row.string("id").orEmpty(),
            name = row.string("name").orEmpty(),
            platform = row.string("platform").orEmpty(),
            createdAt = row.string("created_at").orEmpty(),
            lastSeenAt = row.string("last_seen_at").orEmpty(),
        )
    }

    /** Functions that return a `uuid` answer with a JSON string. */
    fun id(body: JsonElement): String = body.jsonPrimitive.content

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
