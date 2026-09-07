# anitabi 的 R8 规则。依赖库(Maps / CameraX / ML Kit / Coil / OkHttp)都自带 consumer rules,
# 所以这里只写应用自身必需的最小集合。

# org.json 用的是 Android 平台自带的 API(无需 keep)。

# 基于位置的数组解析不使用反射 —— data class 也不需要 keep。

# 削减 Kotlin 协程的调试元数据
-dontwarn kotlinx.coroutines.debug.**

# ONNX Runtime(ISNet-Anime 抠图):JNI 会按名字取 Java 类与字段,必须保留。
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ML Kit(主体分割 16.0.0-beta1 + common):它们自带的 consumer 规则挡不住 R8 的类合并/剪枝,
# 全程序优化后组件注册表会缺件 —— release 里 `SubjectSegmentation.getClient()` 同步抛 NPE
#(内部 `subject.internal.zzc.zza` 为 null),debug 正常。整包保留,体积影响约百 KB 级。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.firebase.components.** { *; }
