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
import cn.locator.app.loc.GeoConvert
import cn.locator.app.net.WsEngine
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 地图页：请求某设备位置后展示其实时坐标。
 * 默认高德瓦片（国内加载快，坐标系 GCJ-02，marker 需偏移转换）；
 * OSM 作为备选（无偏移，国内可能加载慢），右上角一键切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(deviceName: String, onBack: () -> Unit) {
    val loc by WsEngine.locResult.collectAsState()
    val err by WsEngine.error.collectAsState()
    val conn by WsEngine.connState.collectAsState()

    var useGaode by remember { mutableStateOf(true) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }

    val gaodeSource = remember {
        XYTileSource(
            "Gaode", 3, 18, 256, ".png",
            arrayOf(
                "https://wprd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scl=1&style=7&x={x}&y={y}&z={z}",
                "https://wprd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scl=1&style=7&x={x}&y={y}&z={z}",
                "https://wprd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scl=1&style=7&x={x}&y={y}&z={z}",
                "https://wprd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scl=1&style=7&x={x}&y={y}&z={z}",
            ),
        )
    }

    // 按当前瓦片源把 WGS-84 定位结果换算成屏幕坐标（高德=GCJ-02，OSM 原样）
    fun displayPoint(l: WsEngine.LocResult): GeoPoint {
        val (la, ln) = if (useGaode) GeoConvert.wgs84ToGcj02(l.lat, l.lng) else (l.lat to l.lng)
        return GeoPoint(la, ln)
    }

    fun applyLocation(l: WsEngine.LocResult) {
        val mv = mapView ?: return
        val point = displayPoint(l)
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(deviceName) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    TextButton(onClick = { useGaode = !useGaode }) {
                        Text(if (useGaode) "高德" else "OSM")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
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
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(gaodeSource)
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
        loc ?: return@LaunchedEffect
        applyLocation(loc!!)
    }

    // 切换瓦片源：重设 tileSource 并重算 marker 坐标偏移
    LaunchedEffect(useGaode) {
        val mv = mapView ?: return@LaunchedEffect
        mv.setTileSource(if (useGaode) gaodeSource else TileSourceFactory.MAPNIK)
        loc?.let { applyLocation(it) }
    }
}
