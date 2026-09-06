package cn.anitabi.map.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 指南針検証用の使い捨てハーネス：UiAutomation で二本指回転ジェスチャを注入して
 * 地図を回し、ホスト側が screencap で自前指南針の出現を確認する（adb input は多点非対応）。
 */
@RunWith(AndroidJUnit4::class)
/**
 * 注意:这是真机基准/调试**工具**,不是测试 —— 无断言,需要手工在设备上预置文件,
 * `connectedCheck` 跑过它不代表任何验证(README「测试说明」)。
 */
// 标 @Ignore 让 connectedCheck 恢复意义 —— 它此前"通过"并不证明任何事。
// 要跑:./gradlew :app:connectedDebugAndroidTest \
//   -Pandroid.testInstrumentationRunnerArguments.class=…RotateGestureHarness
@Ignore("手动工具,不是测试:无断言,两指旋转手势注入后只是 sleep,靠 host 侧 screencap 观察指南针。")
class RotateGestureHarness {
    @Test
    fun rotateMapThenHold() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("anitabi://map?c=139.7671,35.6812&z=14"))
            .setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<android.app.Activity>(intent)
        SystemClock.sleep(5000)
        var activity: android.app.Activity? = null
        var target: android.view.View? = null
        scenario.onActivity { act ->
            activity = act
            // MapView（GMS の内部 View）を探して直接 dispatch する
            fun find(v: android.view.View): android.view.View? {
                if (v.javaClass.name.contains("maps")) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            target = find(act.window.decorView)
        }
        android.util.Log.i("RotateTest", "target=" + (target?.javaClass?.name ?: "decor"))

        val dm = ctx.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels * 0.35f
        val r = dm.widthPixels * 0.28f
        val steps = 80
        val down = SystemClock.uptimeMillis()

        fun props(id: Int) = MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        fun coords(x: Float, y: Float) = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        fun pointAt(angleDeg: Double, sign: Int): Pair<Float, Float> {
            val a = Math.toRadians(angleDeg)
            return (cx + sign * r * cos(a).toFloat()) to (cy + sign * r * sin(a).toFloat())
        }
        fun inject(action: Int, angle: Double, time: Long) {
            val (x1, y1) = pointAt(angle, 1)
            val (x2, y2) = pointAt(angle, -1)
            val ev = MotionEvent.obtain(
                down, time, action, 2,
                arrayOf(props(0), props(1)), arrayOf(coords(x1, y1), coords(x2, y2)),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            instr.runOnMainSync { (target ?: activity!!.window.decorView).dispatchTouchEvent(ev) }
            ev.recycle()
        }

        // 一本目 DOWN → 二本目 POINTER_DOWN → 回転 MOVE ×40 → UP
        val (sx, sy) = pointAt(0.0, 1)
        val first = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, sx, sy, 0)
        first.source = InputDevice.SOURCE_TOUCHSCREEN
        instr.runOnMainSync { (target ?: activity!!.window.decorView).dispatchTouchEvent(first) }; first.recycle()
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0.0, SystemClock.uptimeMillis())
        for (i in 1..steps) {
            SystemClock.sleep(25)
            inject(MotionEvent.ACTION_MOVE, i * 120.0 / steps, SystemClock.uptimeMillis())
        }
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 120.0, SystemClock.uptimeMillis())
        val (ex, ey) = pointAt(120.0, 1)
        val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, ex, ey, 0)
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        instr.runOnMainSync { (target ?: activity!!.window.decorView).dispatchTouchEvent(up) }; up.recycle()

        // ホストの screencap を待つ
        SystemClock.sleep(8000)
    }
}
