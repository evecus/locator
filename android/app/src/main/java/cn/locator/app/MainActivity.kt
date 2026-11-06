package cn.locator.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import cn.locator.app.net.Prefs
import cn.locator.app.net.WsEngine
import cn.locator.app.service.WsService
import cn.locator.app.ui.DeviceListScreen
import cn.locator.app.ui.MapScreen
import cn.locator.app.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNeededPermissions()

        setContent {
            MaterialTheme {
                Surface { AppRoot() }
            }
        }
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val need = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }
}

@Composable
private fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var stored by remember { mutableStateOf(Prefs.load(ctx)) }
    var screen by remember { mutableStateOf<Screen>(Screen.Devices) }

    // 已有登录态：拉起前台服务维持连接
    LaunchedEffect(stored) {
        val s = stored ?: return@LaunchedEffect
        WsService.start(ctx, s.server, s.token)
    }

    when {
        stored == null -> SetupScreen(
            onDone = { stored = Prefs.load(ctx) },
        )

        else -> when (val s = screen) {
            is Screen.Devices -> DeviceListScreen(
                myId = stored!!.userId,
                myName = stored!!.username,
                onDeviceClick = { d ->
                    WsEngine.requestLocation(d.id)
                    screen = Screen.Map(d.name)
                },
                onLogout = {
                    Prefs.clear(ctx)
                    WsEngine.disconnect()
                    stored = null
                    screen = Screen.Devices
                },
            )

            is Screen.Map -> MapScreen(
                deviceName = s.deviceName,
                onBack = { screen = Screen.Devices },
            )
        }
    }
}

private sealed interface Screen {
    data object Devices : Screen
    data class Map(val deviceName: String) : Screen
}
