package cn.locator.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.locator.app.net.WsEngine
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 地图页：请求某设备位置后展示其实时坐标（OSM 瓦片，免 key）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(deviceName: String, onBack: () -> Unit) {
    val loc by WsEngine.locResult.collectAsState()
    val err by WsEngine.error.collectAsState()
    val conn by WsEngine.connState.collectAsState()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(deviceName) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            // 状态条
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when {
                    loc != null -> {
                        val l = loc!!
                        Text(
                            "纬度 %.5f  经度 %.5f".format(l.lat, l.lng) +
                                (l.accuracy?.let { "  ±%.0fm".format(it) } ?: ""),
                            fontSize = 13.sp,
                        )
                        Text(
                            "更新于 ${timeFmt.format(Date(l.ts * 1000))}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    err != null -> Text(err!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    conn != WsEngine.ConnState.Online -> Text(
                        "连接中…", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        "等待 $deviceName 上报位置…", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(
                        contentPadding = PaddingValues(0.dp),
                        onClick = { WsEngine.clearLocation() },
                    ) { Text("清空", fontSize = 13.sp) }
                    Spacer(Modifier.width(16.dp))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(15.0)
                            mapView = this
                        }
                    },
                )
                if (loc == null) {
                    Surface(
                        color = Color(0xCCFFFFFF),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            if (err != null) err!! else "正在请求对方位置…",
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }

    // 收到新位置：放 marker 并居中
    LaunchedEffect(loc) {
        val l = loc ?: return@LaunchedEffect
        val mv = mapView ?: return@LaunchedEffect
        val point = GeoPoint(l.lat, l.lng)
        val m = marker ?: Marker(mv).also { marker = it }
        mv.overlays.remove(m)
        m.position = point
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.title = deviceName
        mv.overlays.add(m)
        mv.controller.animateTo(point)
        mv.controller.setZoom(16.0)
        mv.invalidate()
    }
}
