package cn.anitabi.map.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

/**
 * Coil 的共享 ImageLoader（等价于 iOS RemoteImageCache 48MB + 淡入 0.18s 的设置）。
 * OkHttpClient 与 AppGraph 共享。
 */
class AnitabiApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val graph = AppGraph.get(this)
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.okHttpClient }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(48L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()
    }
}
