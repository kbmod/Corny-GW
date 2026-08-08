package com.kbmod.cornygw

import android.app.Application
import android.content.Context
import com.kbmod.cornygw.data.HeadingStream
import com.kbmod.cornygw.data.LocationStream
import com.kbmod.cornygw.data.SettingsStore
import com.kbmod.cornygw.data.SurveyStore
import com.kbmod.cornygw.data.WifiScanner

/**
 * Hand-rolled service locator. The dependency graph is five objects deep; a DI
 * framework would be more ceremony than the whole app.
 */
class Graph(context: Context) {
    val wifiScanner = WifiScanner(context)
    val locationStream = LocationStream(context)
    val headingStream = HeadingStream(context)
    val surveyStore = SurveyStore(context)
    val settingsStore = SettingsStore(context)
}

class CornyGwApp : Application() {
    val graph: Graph by lazy { Graph(this) }
}

val Context.graph: Graph
    get() = (applicationContext as CornyGwApp).graph
