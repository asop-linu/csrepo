package com.indo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SontolFilmDubIndoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SontolFilmDubIndo())
    }
}
