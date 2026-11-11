package com.HdHub4u

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FourKHDHubPlugin : Plugin() {
    override fun load(context: Context) {
        // Register 4KHDHub provider
        registerMainAPI(FourKHDHubProvider())

        // Register extractors (shared with HDHub4u — already defined in same package)
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixApp())
        registerExtractorAPI(GdFlix1())
        registerExtractorAPI(GdFlix2())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(FastDlServer())
        registerExtractorAPI(PlayDrive())
    }
}