package com.navi.shell.location

import android.content.Context
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption

/**
 * 问一次「车现在在哪」。
 * 只做一次性定位，导航过程中的连续定位由 AMapNavi 自己管（onLocationChange）。
 */
class Locator(context: Context) {

    private val client = AMapLocationClient(context.applicationContext)

    fun once(onResult: (AMapLocation?) -> Unit) {
        val option = AMapLocationClientOption()
            .setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
            .setOnceLocation(true)
            .setOnceLocationLatest(true)
            .setGpsFirst(true)
            .setNeedAddress(true)
        client.setLocationOption(option)
        client.setLocationListener { loc ->
            client.stopLocation()
            onResult(loc)
        }
        client.startLocation()
    }

    fun destroy() {
        client.stopLocation()
        client.onDestroy()
    }
}
