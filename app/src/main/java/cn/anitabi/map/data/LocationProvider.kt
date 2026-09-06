package cn.anitabi.map.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import cn.anitabi.map.data.model.LatLon
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * 位置信息的供给源（iOS LocationProvider 的移植）。
 *
 * 与 iOS 版一样**有意用粗精度**：用途只有「距我 x km」显示、附近重算、搜索排序，
 * 不记录轨迹。BALANCED_POWER 以 Wi-Fi/基站定位为主，不常抓 GPS，对发热和电池友好。
 * 地图上的蓝点由地图 SDK 侧的 my-location layer 绘制（与这里的值无关）。
 */
class LocationProvider(private val context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    var location: LatLon? by mutableStateOf(null)
        private set

    /** 权限是否被拒绝（UI 据此给出去系统设置的引导）。 */
    var isDenied: Boolean by mutableStateOf(false)

    val isAuthorized: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /** 移动不足 50m 不重算附近（相当于 iOS distanceFilter=50 的节流）。 */
    private var lastNearbyRecompute: LatLon? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val last = result.lastLocation ?: return
            location = LatLon(last.latitude, last.longitude)
        }
    }

    /** 以已取得权限为前提开始订阅（调用方走完权限流程后再调用）。 */
    @SuppressLint("MissingPermission")
    fun startUpdates() {
        if (!isAuthorized) return
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            30_000L,
        ).setMinUpdateDistanceMeters(50f).build()
        client.requestLocationUpdates(request, callback, context.mainLooper)
        // 启动后的第一发用 last known 填充（改善冷启动的体感）
        client.lastLocation.addOnSuccessListener { last ->
            if (location == null && last != null) {
                location = LatLon(last.latitude, last.longitude)
            }
        }
    }

    fun stopUpdates() {
        client.removeLocationUpdates(callback)
    }

    /** 移动不足 50m 不重算附近。 */
    fun shouldRecomputeNearby(at: LatLon): Boolean {
        val last = lastNearbyRecompute ?: return true
        return AnitabiStore.distanceMeters(last, at) >= 50.0
    }

    fun markNearbyRecomputed(at: LatLon) {
        lastNearbyRecompute = at
    }
}
