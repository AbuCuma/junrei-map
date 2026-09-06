# anitabi 的 R8 规则。依赖库(Maps / CameraX / ML Kit / Coil / OkHttp)都自带 consumer rules,
# 所以这里只写应用自身必需的最小集合。

# org.json 用的是 Android 平台自带的 API(无需 keep)。

# 基于位置的数组解析不使用反射 —— data class 也不需要 keep。

# 削减 Kotlin 协程的调试元数据
-dontwarn kotlinx.coroutines.debug.**

# ONNX Runtime(ISNet-Anime 抠图):JNI 会按名字取 Java 类与字段,必须保留。
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
