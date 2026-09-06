package cn.anitabi.map.support

import java.io.File

/**
 * 原子落盘:先写唯一命名的临时文件,再 `rename` 到目标名。
 *
 * 两点都是必须的:
 * - **rename 是原子的**,所以中途被杀不会留下半截文件被下次当成完好的缓存读走;
 * - **临时名必须唯一**,否则两次重叠的写入会往同一个临时文件里交织,rename 之后
 *   反而把拼接出来的垃圾扶正(数据层曾经因为固定的 `<name>.tmp` 出过这个问题)。
 *
 * rename 失败时**不做**就地覆写兜底 —— 那正是本函数要避免的非原子写,一旦中途被杀
 * 会毁掉原本完好的文件。放弃这一次写入的代价只是下次重来。
 *
 * 本文件刻意保持纯 JVM(不引入 `android.*`),因为 `data/` 层要能在 JVM 单测里跑。
 *
 * @return 是否写入成功。调用方多数只需忽略失败(下次重试),但不该把失败当成成功。
 */
fun File.writeAtomically(write: (File) -> Unit): Boolean = runCatching {
    parentFile?.mkdirs()
    val tmp = File.createTempFile(name, ".tmp", parentFile)
    try {
        write(tmp)
        tmp.renameTo(this).also { if (!it) tmp.delete() }
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
}.getOrDefault(false)
