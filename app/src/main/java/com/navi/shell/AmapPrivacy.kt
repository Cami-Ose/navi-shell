package com.navi.shell

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.NaviSetting

/**
 * 高德的隐私合规口子。
 *
 * 高德强制要求：App 必须在用户点过「同意隐私政策」之后才能初始化 SDK，
 * 否则 Key 会直接报错、地图出不来。所以任何高德调用之前先过这里。
 */
object AmapPrivacy {

    @Volatile
    private var applied = false

    /** 用户同意隐私政策后调一次，重复调用无副作用。必须在任何高德 SDK 调用之前。 */
    fun apply(context: Context) {
        if (applied) return
        val app = context.applicationContext
        // 三个 SDK 各有一份合规开关，少一个就有一个模块不干活
        MapsInitializer.updatePrivacyShow(app, true, true)
        MapsInitializer.updatePrivacyAgree(app, true)
        NaviSetting.updatePrivacyShow(app, true, true)
        NaviSetting.updatePrivacyAgree(app, true)
        AMapLocationClient.updatePrivacyShow(app, true, true)
        AMapLocationClient.updatePrivacyAgree(app, true)
        applied = true
    }
}
