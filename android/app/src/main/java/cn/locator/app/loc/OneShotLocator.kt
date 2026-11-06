package cn.locator.app.loc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 按需单次定位：只在收到 location_request 时调用一次。
 * 兼容 API 21+：内部走 LocationManagerCompat.getCurrentLocation。
 */
object OneShotLocator {

    data class Fix(val lat: Double, val lng: Double, val accuracy: Double?, val ts: Long)

    suspend fun get(context: Context, timeoutMs: Long = 12_000): Result<Fix> {
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
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).filter { isProviderEnabledSafe(lm, it) }

        // 依次尝试各 provider，拿到的第一个 fix 就用（suspendCancellable 保证只 resume 一次）
        val fix = withTimeoutOrNull(timeoutMs) {
            for (provider in providers) {
                val f = singleFix(lm, provider)
                if (f != null) return@withTimeoutOrNull f
            }
            // 兜底：最近一次已知位置
            lastKnown(lm, providers)
        }
        return fix?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("定位超时"))
    }

    private fun isProviderEnabledSafe(lm: LocationManager, p: String) = runCatching {
        lm.isProviderEnabled(p)
    }.getOrDefault(false)

    private suspend fun singleFix(lm: LocationManager, provider: String): Fix? {
        if (!hasPermission(lm, provider)) return null
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    lm, provider, signal, Dispatchers.Main.asExecutor(),
                ) { loc: Location? ->
                    if (cont.isActive) {
                        cont.resume(loc?.toFix())
                    }
                }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun hasPermission(lm: LocationManager, provider: String): Boolean {
        // 部分 ROM 对不可用 provider 直接抛异常，这里统一吞掉
        return runCatching { lm.getProvider(provider) != null }.getOrDefault(false)
    }

    private fun lastKnown(lm: LocationManager, providers: List<String>): Fix? =
        providers.firstNotNullOfOrNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.toFix()
        }

    private fun Location.toFix() = Fix(latitude, longitude, accuracy.takeIf { it > 0 }?.toDouble(), time / 1000)
}
