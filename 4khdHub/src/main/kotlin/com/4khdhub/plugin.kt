package com.fourkhdhub


import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ToonPlayPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the provider — yeh line Cloudstream ko batati hai
        // ki ToonPlayProvider available hai
        registerMainAPI(ToonPlayProvider())
        
        // Agar future mein custom extractor add karein (jaise AbyssPlayerExtractor),
        // to yahan register karein:
        // registerExtractorAPI(AbyssPlayerExtractor())
    }
}