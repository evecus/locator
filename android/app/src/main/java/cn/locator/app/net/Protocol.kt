package cn.locator.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 与服务端对齐的 WS 协议（JSON 信封，type 字段区分）。
 * Rust 侧 serde: #[serde(tag = "type", rename_all = "snake_case")]
 */

// ---------- 服务端 -> 客户端 ----------

@Serializable
sealed interface ServerMsg

@Serializable
@SerialName("auth_ok")
data class AuthOk(val device_id: Long, val name: String) : ServerMsg

@Serializable
data class DeviceDto(val id: Long, val name: String, val online: Boolean)

@Serializable
@SerialName("device_list")
data class DeviceListMsg(val devices: List<DeviceDto>) : ServerMsg

@Serializable
@SerialName("pong")
data class PongMsg(val unused: Int? = null) : ServerMsg

@Serializable
@SerialName("location_request")
data class LocationRequestMsg(
    val request_id: String,
    val from_id: Long,
    val from_name: String,
) : ServerMsg

@Serializable
@SerialName("location_result")
data class LocationResultMsg(
    val request_id: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val ts: Long,
) : ServerMsg

@Serializable
@SerialName("location_error")
data class LocationErrorMsg(val request_id: String, val reason: String) : ServerMsg

// ---------- 客户端 -> 服务端 ----------

@Serializable
sealed interface ClientMsg

@Serializable
@SerialName("ping")
object PingMsg : ClientMsg

@Serializable
@SerialName("request_location")
data class RequestLocationMsg(val target_id: Long) : ClientMsg

@Serializable
@SerialName("location_report")
data class LocationReportMsg(
    val request_id: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val ts: Long,
) : ClientMsg

@Serializable
@SerialName("location_error")
data class LocationErrorMsgOut(val request_id: String, val reason: String) : ClientMsg

val wsJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}
