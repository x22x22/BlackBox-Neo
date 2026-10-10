package top.niunaijun.blackboxa.view.fake

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityMapPickerBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast


/**
 * 虚拟定位的「地图选点」界面。
 *
 * 由 osmdroid(OSM) 改为腾讯地图 JavaScript API GL，原因：
 *  1) OSM 官方瓦片在国内加载困难，界面长期空白，无法选点；
 *  2) 需要关键字搜索（TMap.service.Search#searchRegion）；
 *  3) 腾讯地图使用 GCJ-02，而 Android 的 Location 以及沙箱下发的坐标是 WGS-84。
 *     这里统一换算：传给地图前 WGS-84 -> GCJ-02，回传落库前 GCJ-02 -> WGS-84。
 *
 * 实测背景：若把 GCJ-02 坐标直接写进虚拟位置，会被系统按 WGS-84 再转一次
 * （长沙约 650m 偏移），企业微信打卡会因此判定「不在打卡范围内」。
 */
class FollowMyLocationOverlay : AppCompatActivity() {

    companion object {
        const val TAG: String = "FollowMyLocationOverlay"

        /**
         * 腾讯地图 Key / Secret Key 由用户在「设置 - 腾讯地图」中填写，
         * 通过 SharedPreferences 读取，**不在代码或资源中内置任何 key**。
         * 未填写时地图选点会提示并退出。
         */
        const val PREF_TMAP_KEY = "tmap_key"
        const val PREF_TMAP_SK = "tmap_sk"

        /** 搜索优先限定的城市。 */
        private const val SEARCH_CITY = "长沙市"

        private const val PI = Math.PI
        private const val A = 6378245.0
        private const val EE = 0.00669342162296594323
    }

    private val binding: ActivityMapPickerBinding by inflate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        // debug 构建下开启 WebView 远程调试，便于排查页面内交互问题
        try {
            if (0 != (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)) {
                android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            }
        } catch (ignored: Throwable) {
        }

        cleanLegacyOsmdroid()

        val location: BLocation? = intent.getParcelableExtra("location")
        // 存储值是 WGS-84；缺省用杭州市中心。
        val startLat = location?.latitude ?: 30.2736
        val startLng = location?.longitude ?: 120.1563

        setupWebView(startLat, startLng)
    }

    /**
     * 清理早期 osmdroid(OSM) 版本留下的痕迹。
     *
     * 地图选点已改为腾讯地图，历史版本在默认 SharedPreferences 里写过一批
     * "osmdroid.*" 配置项，并在 files/osmdroid 下留有瓦片缓存；这里在进入
     * 选点页时做一次幂等清理（只删 osmdroid.* 键，不影响腾讯地图 key 等配置）。
     */
    private fun cleanLegacyOsmdroid() {
        try {
            val sp = PreferenceManager.getDefaultSharedPreferences(this)
            val stale = sp.all.keys.filter { it.startsWith("osmdroid.") }
            if (stale.isNotEmpty()) {
                val editor = sp.edit()
                stale.forEach { editor.remove(it) }
                editor.apply()
            }
            val tileDir = java.io.File(filesDir, "osmdroid")
            if (tileDir.exists()) {
                tileDir.deleteRecursively()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun prefString(key: String): String =
        PreferenceManager.getDefaultSharedPreferences(this)
            .getString(key, "")?.trim().orEmpty()

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wgsLat: Double, wgsLng: Double) {
        val key = prefString(PREF_TMAP_KEY)
        if (key.isEmpty()) {
            toast(getString(R.string.tmap_not_configured))
            finish()
            return
        }
        val sk = prefString(PREF_TMAP_SK)
        val gcj = wgs84ToGcj02(wgsLat, wgsLng)

        val settings = binding.map.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        binding.map.webViewClient = WebViewClient()
        binding.map.addJavascriptInterface(Bridge(), "AndroidBridge")

        val html = try {
            assets.open("tmap_picker.html").bufferedReader().use { it.readText() }
                .replace("__KEY__", key)
                .replace("__CITY__", SEARCH_CITY)
                .replace("__SK__", sk)
                .replace("__LAT__", gcj[0].toString())
                .replace("__LNG__", gcj[1].toString())
        } catch (e: Exception) {
            toast("地图资源加载失败: " + e.message)
            return
        }
        binding.map.loadDataWithBaseURL("https://map.qq.com/", html, "text/html", "UTF-8", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun pick(gcjLat: Double, gcjLng: Double) {
            // 地图给的是 GCJ-02，换算回 WGS-84 再落库；否则沙箱下发时会被
            // 系统按 WGS-84 -> GCJ-02 再转一次，产生约 650m 偏移。
            val wgs = gcj02ToWgs84(gcjLat, gcjLng)
            runOnUiThread { finishWithResult(wgs[0], wgs[1]) }
        }
    }

    private fun finishWithResult(lat: Double, lng: Double) {
        intent.putExtra("latitude", lat)
        intent.putExtra("longitude", lng)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    // ---------------- 坐标系换算 ----------------

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun wgs84ToGcj02(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = dLat * 180.0 / (A * (1 - EE) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (A / sqrtMagic * Math.cos(radLat) * PI)
        return doubleArrayOf(lat + dLat, lng + dLng)
    }

    private fun gcj02ToWgs84(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var wLat = lat
        var wLng = lng
        repeat(10) {
            val g = wgs84ToGcj02(wLat, wLng)
            wLat += lat - g[0]
            wLng += lng - g[1]
        }
        return doubleArrayOf(wLat, wLng)
    }
}
