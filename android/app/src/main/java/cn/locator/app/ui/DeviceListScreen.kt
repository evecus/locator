package cn.locator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.locator.app.net.WsEngine
import cn.locator.app.service.WsService

/**
 * 设备列表页：全部注册设备 + 在线状态，点击在线设备发起定位请求
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    myId: Long,
    myName: String,
    onDeviceClick: (WsEngine.Device) -> Unit,
    onLogout: () -> Unit,
) {
    val ctx = LocalContext.current
    val devices by WsEngine.devices.collectAsState()
    val conn by WsEngine.connState.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设备列表") },
                actions = {
                    TextButton(onClick = {
                        WsService.stop(ctx)
                        onLogout()
                    }) { Text("退出") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            // 连接状态条
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(conn)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (conn) {
                        WsEngine.ConnState.Online -> "已连接 · $myName"
                        WsEngine.ConnState.Connecting -> "连接中…"
                        WsEngine.ConnState.Offline -> "已断开，重连中…"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            if (devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无设备\n邀请更多设备注册后可见",
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(devices, key = { it.id }) { d ->
                        val isSelf = d.id == myId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = d.online && !isSelf) {
                                    if (!isSelf) onDeviceClick(d)
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(if (d.online) WsEngine.ConnState.Online else WsEngine.ConnState.Offline)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                                Text(
                                    when {
                                        isSelf -> "本机"
                                        d.online -> "在线 · 点击请求位置"
                                        else -> "离线"
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (d.online && !isSelf) {
                                Text(
                                    "请求定位",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(state: WsEngine.ConnState) {
    val color = when (state) {
        WsEngine.ConnState.Online -> Color(0xFF2E7D32)
        WsEngine.ConnState.Connecting -> Color(0xFFF9A825)
        WsEngine.ConnState.Offline -> Color(0xFF9E9E9E)
    }
    Box(
        Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}
