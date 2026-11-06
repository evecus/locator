package cn.locator.app

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration

class LocatorApp : Application() {
    companion object {
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        // osmdroid：设置 UA 与缓存路径，避免瓦片请求被拒
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = getExternalFilesDir(null) ?: cacheDir
            osmdroidTileCache = getExternalFilesDir("tiles") ?: cacheDir
        }
    }
}
