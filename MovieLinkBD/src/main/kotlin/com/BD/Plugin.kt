package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MovieLinkBDPlugin : Plugin() {
    override fun load(context: Context) {
        // Apne naye MovieLinkBD provider ko yahan register karein
        registerMainAPI(MovieLinkBD())
        
    }
}
