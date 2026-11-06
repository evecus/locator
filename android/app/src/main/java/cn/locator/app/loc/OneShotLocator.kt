package cn.locator.app.loc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 按需单次定位：只在收到 location_request 时调用一次。
 *
 * 策略：对所有可用定位源（GPS / 网络 / fused）**并发**发起单次请求，
 * 谁先出结果用谁——避免室内等 GPS 出不来把总超时吃光的问题；
 * 全部失败再回退最近已知位置。
 */
object OneShotLocator {

    data class Fix(val lat: Double, val lng: Double, val accuracy: Double?, val ts: Long)

    private const val TIMEOUT_MS = 12_000L  // 须小于服务端 15s 的请求超时

    suspend fun get(context: Context, timeoutMs: Long = TIMEOUT_MS): Result<Fix> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(IllegalStateException("未授予定位权限"))
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Result.failure(IllegalStateException("无定位服务"))

        val providers = listOf(
            LocationManager.GPS_PROVIDER,      // "gps"
            LocationManager.NETWORK_PROVIDER,  // "network"
            "fused",                           // API 31+ 融合定位（老系统上不存在则被吞掉）
        ).filter { isProviderEnabledSafe(lm, it) }

        if (providers.isEmpty()) {
            // 定位总开关都没开，直接回最近已知位置
            lastKnown(lm, emptyList())?.let { return Result.success(it) }
            return Result.failure(IllegalStateException("定位服务未开启（请在系统设置打开定位）"))
        }

        val done = AtomicBoolean(false)
        val deferred = CompletableDeferred<Fix>()
        val signal = CancellationSignal()
        val executor = Dispatchers.Main.asExecutor()

        for (p in providers) {
            try {
                LocationManagerCompat.getCurrentLocation(lm, p, signal, executor) { loc: Location? ->
                    if (loc != null && done.compareAndSet(false, true)) {
                        deferred.complete(loc.toFix())
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
                // provider 不存在（如低版本的 fused）
            }
        }

        val fix = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: lastKnown(lm, providers).also { signal.cancel() }
            ?: return Result.failure(IllegalStateException("定位超时，请确认GPS/网络定位可用"))
        return Result.success(fix)
    }

    private fun isProviderEnabledSafe(lm: LocationManager, p: String) = runCatching {
        lm.isProviderEnabled(p)
    }.getOrDefault(false)

    /** 最近已知位置兜底：取所有来源里时间最新的一个 */
    private fun lastKnown(lm: LocationManager, providers: List<String>): Fix? =
        providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }?.toFix()

    private fun Location.toFix() = Fix(latitude, longitude, accuracy.takeIf { it > 0 }?.toDouble(), time / 1000)
}
