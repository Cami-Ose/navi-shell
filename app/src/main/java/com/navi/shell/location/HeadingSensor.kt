package com.navi.shell.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手机现在朝哪边（方位角，正北 0，顺时针 0~360）。
 *
 * 为什么需要它：地图上那个箭头原来只看 GPS 航向（`AMapNaviLocation.getBearing()`），
 * 而 GPS 航向**只有在动的时候才准** —— 站着不动、或者还没开始导航，它压根不更新，
 * 你把手机转一圈箭头纹丝不动。实测就是这么发现的。
 *
 * 这里读的是方向传感器（旋转矢量），**站着不动也跟手**。
 */
class HeadingSensor(context: Context) {

    private val sm = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _azimuth = MutableStateFlow(0f)
    val azimuth: StateFlow<Float> = _azimuth.asStateFlow()

    /** 这台机器到底有没有方向传感器（模拟器多半没有，得让调用方知道）。 */
    val available: Boolean get() = sensor != null

    private val listener = object : SensorEventListener {
        private val r = FloatArray(9)
        private val orient = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            runCatching {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                SensorManager.getOrientation(r, orient)
                val deg = Math.toDegrees(orient[0].toDouble()).toFloat()
                _azimuth.value = (deg + 360f) % 360f
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        sensor?.let { sm?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm?.unregisterListener(listener)
    }
}
