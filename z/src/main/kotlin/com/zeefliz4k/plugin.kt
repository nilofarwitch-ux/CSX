package com.zeefliz4k

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ZeeFliz4KPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ZeeFliz4K())

        // Cloud & Direct Hosts
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixApp())
        registerExtractorAPI(GdFlix1())
        registerExtractorAPI(GdFlix2())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(FastDlServer())
        registerExtractorAPI(Hblinks())

        // Stream Players
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(Hubstream())
        registerExtractorAPI(Vidaraa())
        registerExtractorAPI(PlayDrive())
    }
}
