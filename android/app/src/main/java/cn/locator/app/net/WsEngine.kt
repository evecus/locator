package cn.locator.app.net

import android.util.Log
import cn.locator.app.LocatorApp
import cn.locator.app.loc.OneShotLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * WS 引擎：单例，持有长连接与全部实时状态。
 * 生命周期由前台服务管理；UI 只读 StateFlow。
 */
object WsEngine {
    private const val TAG = "WsEngine"

    sealed interface ConnState {
        data object Connecting : ConnState
        data object Online : ConnState
        data object Offline : ConnState
    }

    data class Device(val id: Long, val name: String, val online: Boolean)
    data class LocResult(
        val lat: Double,
        val lng: Double,
        val accuracy: Double?,
        val ts: Long,
    )

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS) // 协议层心跳，保持 NAT/检测断线
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var server = ""
    private var token = ""
    @Volatile private var wantConnected = false
    private var ws: WebSocket? = null
    private var backoffMs = 2000L
    private var connectedKey: String? = null

    val connState = MutableStateFlow<ConnState>(ConnState.Offline)
    val devices = MutableStateFlow<List<Device>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    private val _locResult = MutableStateFlow<LocResult?>(null)
    val locResult: StateFlow<LocResult?> = _locResult

    fun connect(serverAddr: String, tokenStr: String) {
        val key = "$serverAddr|$tokenStr"
        // 同一凭据且已有活动连接/正在连接时，避免重复建连（注册后会触发两次 start）
        if (wantConnected && key == connectedKey && ws != null) return
        server = serverAddr
        token = tokenStr
        wantConnected = true
        connectedKey = key
        ws?.cancel() // 干掉旧 socket，防止新旧两条监听打架
        open()
    }

    fun disconnect() {
        wantConnected = false
        connectedKey = null
        ws?.close(1000, "bye")
        ws = null
        connState.value = ConnState.Offline
    }

    fun requestLocation(targetId: Long) {
        _locResult.value = null
        error.value = null
        send(RequestLocationMsg(target_id = targetId))
    }

    fun clearLocation() {
        _locResult.value = null
        error.value = null
    }

    fun sendReport(
        requestId: String,
        lat: Double,
        lng: Double,
        accuracy: Double?,
        ts: Long,
    ) {
        send(LocationReportMsg(request_id = requestId, lat = lat, lng = lng, accuracy = accuracy, ts = ts))
    }

    fun sendError(requestId: String, reason: String) {
        send(LocationErrorMsgOut(request_id = requestId, reason = reason))
    }

    private fun send(msg: ClientMsg) {
        val text = wsJson.encodeToString(ClientMsg.serializer(), msg)
        ws?.send(text)
    }

    private fun open() {
        if (!wantConnected) return
        connState.value = ConnState.Connecting
        val url = wsUrlOf(server, token)
        Log.i(TAG, "connecting $url")
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, listener)
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            open()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "ws open")
            backoffMs = 2000L
            connState.value = ConnState.Online
            error.value = null
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerMsg(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            Log.w(TAG, "ws failure code=$code: ${t.message}")
            // 只有当前活动 socket 的事件才驱动状态；被 cancel 的旧 socket 忽略
            if (webSocket !== ws) return
            connState.value = ConnState.Offline
            if (code == 401) {
                // 登录失效：停止无意义重连，提示重新登录
                wantConnected = false
                error.value = "登录已失效，请退出后重新登录"
                return
            }
            error.value = "连接失败: ${t.message ?: ("HTTP $code")}"
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            connState.value = ConnState.Offline
            scheduleReconnect()
        }
    }

    private fun handleServerMsg(text: String) {
        val msg = runCatching { wsJson.decodeFromString(ServerMsg.serializer(), text) }
            .getOrElse {
                Log.w(TAG, "bad msg: $text")
                return
            }
        when (msg) {
            is AuthOk -> Log.i(TAG, "auth ok, device_id=${msg.device_id}")
            is DeviceListMsg -> devices.value = msg.devices.map { Device(it.id, it.name, it.online) }
            is PongMsg -> {}
            is LocationRequestMsg -> {
                // 被请求：现场取一次定位并上报
                scope.launch {
                    val result = OneShotLocator.get(LocatorApp.appContext)
                    result.fold(
                        onSuccess = { loc ->
                            sendReport(msg.request_id, loc.lat, loc.lng, loc.accuracy, loc.ts)
                        },
                        onFailure = { e ->
                            sendError(msg.request_id, e.message ?: "定位失败")
                        },
                    )
                }
            }
            is LocationResultMsg -> {
                _locResult.value = LocResult(msg.lat, msg.lng, msg.accuracy, msg.ts)
            }
            is LocationErrorMsg -> error.value = msg.reason
        }
    }
}
