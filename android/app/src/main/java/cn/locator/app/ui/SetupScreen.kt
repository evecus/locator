package cn.locator.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.locator.app.net.Api
import cn.locator.app.net.Prefs
import kotlinx.coroutines.launch

/**
 * 注册 / 登录页：服务端地址 + 用户名 + 密码 + 邀请码（仅注册）
 * 登录成功后回调 onDone()
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf<String?>(null) }

    // 进页面先申请定位 / 通知权限
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        val need = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filter {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("位置共享", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("服务端地址 (如 loc.example.com:8443)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
            if (isRegister) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = invite, onValueChange = { invite = it },
                    label = { Text("邀请码") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = isRegister,
                    onClick = { isRegister = true },
                    label = { Text("注册") },
                )
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = !isRegister,
                    onClick = { isRegister = false },
                    label = { Text("登录") },
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    errMsg = null
                    scope.launch {
                        val result = if (isRegister) {
                            Api.register(server, username, password, invite)
                        } else {
                            Api.login(server, username, password)
                        }
                        busy = false
                        result.fold(
                            onSuccess = { resp ->
                                Prefs.save(ctx, server, resp)
                                // 服务由 AppRoot 的 LaunchedEffect(stored) 统一拉起，避免重复建连
                                onDone()
                            },
                            onFailure = { errMsg = it.message ?: "请求失败" },
                        )
                    }
                },
            ) {
                Text(if (busy) "请稍候…" else if (isRegister) "注册并登录" else "登录")
            }

            errMsg?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
