
@file:Suppress(
    "DEPRECATION",
    "DEPRECATION_ERROR",
    "UNUSED_PARAMETER"
)

package com.hindi

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

import com.lagradost.cloudstream3.CommonActivity

object DesiExtensionPopup {

    private const val TELEGRAM_URL =
        "https://t.me/+yug8sqPegCQ1NWRl"

    private const val GITHUB_URL =
        "https://github.com/Faisal0786/Desi"

    private const val DISCORD_URL =
        "https://discord.gg/G8F2fGEvR"

    private const val ICON_URL =
        "https://raw.githubusercontent.com/Faisal0786/Desi/main/Icons/images%20(1).jpeg"

    private const val POPUP_PREFS =
        "desi_extension_popup_prefs"

    private const val TELEGRAM_JOINED_KEY =
        "telegram_joined"

    private const val LAST_POPUP_SHOWN_KEY =
        "last_popup_shown_at"

    private const val TWENTY_FOUR_HOURS_MS =
        24L * 60L * 60L * 1000L

    private var currentDialog: Dialog? = null
    private var currentWebView: WebView? = null

    private val mainHandler =
        Handler(Looper.getMainLooper())

    fun show() {
        mainHandler.post {
            val activity = CommonActivity.activity ?: return@post

            if (currentDialog?.isShowing == true) return@post

            // Existing popup display behavior preserved.
             if (!canShowPopup(activity)) return@post

            val firstTimePopup = !hasTelegramJoinClick(activity)

            val dialog = Dialog(activity)
            currentDialog = dialog

            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)

            val webView = WebView(activity)
            currentWebView = webView

            configureWebView(
                webView = webView,
                dialog = dialog
            )

            val root = FrameLayout(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            val metrics = activity.resources.displayMetrics

            // Keep safe margins around the popup.
            val maxWidth = metrics.widthPixels - dp(activity, 24f)
            val maxHeight = metrics.heightPixels - dp(activity, 64f)

            // Slightly wider and taller than the previous popup.
            val popupWidth = dp(activity, 400f)
                .coerceAtMost(maxWidth.coerceAtLeast(1))

            val popupHeight = dp(activity, 680f)
                .coerceAtMost(maxHeight.coerceAtLeast(1))

            root.addView(
                webView,
                FrameLayout.LayoutParams(
                    popupWidth,
                    popupHeight
                ).apply {
                    gravity = Gravity.CENTER
                }
            )

            dialog.setContentView(root)

            try {
                dialog.show()
                markPopupShown(activity)
            } catch (_: Throwable) {
                currentWebView = null
                currentDialog = null
                return@post
            }

            configureDialogWindow(
                dialog = dialog,
                width = popupWidth,
                height = popupHeight
            )

            val finalHtml = HTML
                .replace("__ICON_URL__", ICON_URL)
                .replace("__TELEGRAM_URL__", TELEGRAM_URL)
                .replace("__GITHUB_URL__", GITHUB_URL)
                .replace("__DISCORD_URL__", DISCORD_URL)
                .replace(
                    "__FIRST_TIME_POPUP__",
                    firstTimePopup.toString()
                )

            webView.loadDataWithBaseURL(
                "https://desi-extension.local/",
                finalHtml,
                "text/html",
                "UTF-8",
                null
            )

            dialog.setOnDismissListener {
                try {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface("AndroidPopup")
                    webView.clearHistory()
                    webView.removeAllViews()
                    webView.destroy()
                } catch (_: Throwable) {
                }

                currentWebView = null
                currentDialog = null
            }
        }
    }

    private fun configureDialogWindow(
        dialog: Dialog,
        width: Int,
        height: Int
    ) {
        val window = dialog.window ?: return

        window.setBackgroundDrawable(
            ColorDrawable(Color.TRANSPARENT)
        )

        try {
            window.setElevation(0f)
            window.decorView.elevation = 0f
            window.decorView.background =
                ColorDrawable(Color.TRANSPARENT)
        } catch (_: Throwable) {
        }

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        )

        val params = window.attributes
        params.width = width
        params.height = height
        params.gravity = Gravity.CENTER
        params.dimAmount = 0.48f
        window.attributes = params

        try {
            window.setDimAmount(0.48f)
        } catch (_: Throwable) {
        }

        window.decorView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun configureWebView(
        webView: WebView,
        dialog: Dialog
    ) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(
            WebView.LAYER_TYPE_HARDWARE,
            null
        )

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            useWideViewPort = false
            loadWithOverviewMode = false
            javaScriptCanOpenWindowsAutomatically = false
        }

        webView.addJavascriptInterface(
            AndroidPopupBridge(
                context = webView.context,
                dialog = dialog
            ),
            "AndroidPopup"
        )

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return openExternalUrl(
                    view,
                    request.url.toString()
                )
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
            ): Boolean {
                return openExternalUrl(view, url)
            }
        }
    }

    private fun openExternalUrl(
        view: WebView,
        url: String
    ): Boolean {
        if (
            !url.startsWith("https://") &&
            !url.startsWith("http://")
        ) {
            return false
        }

        return try {
            if (isTelegramUrl(url)) {
                markTelegramJoined(view.context)
            }

            view.context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                )
            )

            true
        } catch (_: Throwable) {
            try {
                Toast.makeText(
                    view.context,
                    "Unable to open link",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (_: Throwable) {
            }

            true
        }
    }

    private fun isTelegramUrl(url: String): Boolean {
        return url.trim().trimEnd('/').equals(
            TELEGRAM_URL.trimEnd('/'),
            ignoreCase = true
        )
    }

    private fun hasTelegramJoinClick(
        context: Context
    ): Boolean {
        return context.getSharedPreferences(
            POPUP_PREFS,
            Context.MODE_PRIVATE
        ).getBoolean(
            TELEGRAM_JOINED_KEY,
            false
        )
    }

    private fun markTelegramJoined(
        context: Context
    ) {
        context.getSharedPreferences(
            POPUP_PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(TELEGRAM_JOINED_KEY, true)
            .apply()
    }

    private fun canShowPopup(
        context: Context
    ): Boolean {
        val lastShown = context.getSharedPreferences(
            POPUP_PREFS,
            Context.MODE_PRIVATE
        ).getLong(
            LAST_POPUP_SHOWN_KEY,
            0L
        )

        if (lastShown <= 0L) return true

        return System.currentTimeMillis() - lastShown >=
            TWENTY_FOUR_HOURS_MS
    }

    private fun markPopupShown(
        context: Context
    ) {
        context.getSharedPreferences(
            POPUP_PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putLong(
                LAST_POPUP_SHOWN_KEY,
                System.currentTimeMillis()
            )
            .apply()
    }

    private fun dp(
        context: Context,
        value: Float
    ): Int {
        return (
            value *
                context.resources.displayMetrics.density
            ).toInt()
    }

    private class AndroidPopupBridge(
        private val context: Context,
        private val dialog: Dialog
    ) {

        @JavascriptInterface
        fun telegramClicked() {
            markTelegramJoined(context)
        }

        @JavascriptInterface
        fun close() {
            Handler(Looper.getMainLooper()).post {
                try {
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private val HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">

<meta
    name="viewport"
    content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
>

<meta name="theme-color" content="#00000000">

<title>Desi Repo Community</title>

<style>
* {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
}

:root {
    --blue: #56c7f2;
    --muted: #9aa4b8;
    --line: #2a3142;
    --purple: #5146e5;
}

html,
body {
    width: 100%;
    height: 100%;
    margin: 0;
    padding: 0;
    overflow: hidden;
    background: transparent !important;
    font-family: Inter, "Segoe UI", Roboto, Arial, sans-serif;
    color: #f6f8fc;
    -webkit-text-size-adjust: 100%;
}

body {
    display: flex;
    align-items: center;
    justify-content: center;
}

.overlay {
    position: fixed;
    inset: 0;
    z-index: 10;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    padding: 0;
    overflow: hidden;
    background: transparent !important;
    backdrop-filter: none !important;
    -webkit-backdrop-filter: none !important;
}

.popup {
    position: relative;
    width: 100%;
    height: auto;
    max-width: 100%;
    max-height: 100%;
    flex-shrink: 1;
    overflow: hidden;

    padding: 15px 15px 12px;

    border: 1px solid #303746;
    border-radius: 28px;

    background:
        radial-gradient(
            ellipse at 50% 0,
            rgba(32,105,155,.12),
            transparent 35%
        ),
        linear-gradient(
            155deg,
            #151a27 0%,
            #111620 75%,
            #10141d 100%
        );

    box-shadow: inset 0 1px 0 #ffffff08;

    animation:
        popupIn .38s cubic-bezier(.2,.8,.2,1);
}

@keyframes popupIn {
    from {
        opacity: 0;
        transform: translateY(15px) scale(.975);
    }

    to {
        opacity: 1;
        transform: translateY(0) scale(1);
    }
}

.topline {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 10px;
}

.repo-badge {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    min-height: 35px;
    max-width: calc(100% - 112px);
    padding: 7px 10px;

    border: 1px solid #454168;
    border-radius: 11px;

    background:
        linear-gradient(
            110deg,
            #211f38,
            #19253a
        );

    color: #e2e4ff;
    font-size: 12px;
    font-weight: 750;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    text-decoration: none;
}

.repo-badge svg {
    width: 16px;
    height: 16px;
    flex-shrink: 0;
}

.repo-label {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
}

.month-badge {
    flex-shrink: 0;
    padding: 8px 9px;
    border-radius: 9px;
    background: #1a2030;
    color: #c5ccda;
    font-size: 11px;
    white-space: nowrap;
}

.repo-mark {
    position: relative;
    isolation: isolate;
    overflow: visible;

    width: 56px;
    height: 56px;

    display: grid;
    place-items: center;

    margin: 0 auto 10px;

    border: 0;
    border-radius: 50%;
    background: transparent;

    box-shadow: 0 0 24px #7267f43b;
}

.repo-icon {
    position: relative;
    z-index: 2;

    width: 56px;
    height: 56px;

    display: block;
    object-fit: cover;

    border-radius: 50%;
}

.orbit-dots {
    position: absolute;
    inset: 0;
    z-index: 1;
    pointer-events: none;
}

.orbit-path {
    position: absolute;
    top: 50%;
    left: 50%;
    width: 0;
    height: 0;

    animation:
        orbitRotate var(--duration) linear infinite;

    animation-delay: var(--delay);
    animation-direction: var(--direction);
}

.orbit-dot {
    position: absolute;

    left: var(--radius);
    top: 0;

    width: var(--size);
    height: var(--size);

    border-radius: 50%;

    background: var(--tricolour, #ff9933);
    opacity: var(--opacity);

    transform: translate(-50%, -50%);

    box-shadow:
        0 0 5px 1px var(--dot-glow, #ff993388),
        0 0 12px var(--dot-glow, #ff993366);

    animation:
        dotGlow var(--glow-duration) ease-in-out infinite alternate;

    animation-delay: var(--glow-delay);
}

@keyframes orbitRotate {
    from {
        transform: rotate(0);
    }

    to {
        transform: rotate(360deg);
    }
}

@keyframes dotGlow {
    from {
        opacity: .25;
        scale: .7;
    }

    to {
        opacity: 1;
        scale: 1.2;
    }
}

.heading {
    color: #f6f8fc;
    text-align: center;

    font-size: 21px;
    line-height: 1.18;
    font-weight: 850;
    letter-spacing: -.6px;

    text-shadow: 0 1px 12px #0005;
}

.heading .blue {
    color: #56c7f2;
}

.description {
    max-width: 480px;
    margin: 7px auto 10px;

    text-align: center;
    color: #aeb8c9;

    font-size: 12px;
    line-height: 1.4;
}

.badges {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin: 0 0 9px;
}

.trust-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;

    padding: 6px 7px;

    border: 1px solid #2c3445;
    border-radius: 11px;

    background: #1a2030;
    color: #a9cfff;

    font-size: 11px;
    font-weight: 750;
}

.trust-badge.bug {
    color: #d0c8ff;
    border-color: #494269;

    background:
        linear-gradient(
            120deg,
            #211d35,
            #1a2030
        );
}

.action {
    width: 100%;
    min-height: 42px;

    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;

    padding: 11px 12px;

    border-radius: 14px;
    text-align: center;
    text-decoration: none;

    font-size: 14px;
    font-weight: 800;

    cursor: pointer;

    transition:
        transform .18s,
        filter .18s;

    border: 1px solid transparent;

    color: #f7fbff;
    background: transparent;

    -webkit-tap-highlight-color: transparent;
}

.action:active {
    transform: scale(.985);
}

.action:hover {
    filter: brightness(1.1);
}

.brand-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
}

.brand-icon svg {
    width: 18px;
    height: 18px;
    display: block;
}

.action-telegram {
    min-height: 44px;

    border: 1.5px solid #3c9fc4;
    border-radius: 35px;

    color: #f7fbff;

    background:
        linear-gradient(
            110deg,
            #12202b,
            #121622 55%,
            #17152b
        );

    box-shadow: 0 0 22px #29a9e812;
}

.action-telegram svg {
    color: #6bd3f8;
}

.action-discord {
    border: 1px solid #6268f5;
    color: #fff;

    background:
        linear-gradient(
            110deg,
            #5865f2,
            #454bd1
        );

    box-shadow: 0 8px 24px #5865f226;
}

.community-actions {
    display: grid;
    gap: 8px;
    margin-top: 10px;
}

.action-later {
    min-height: 40px;
    margin-top: 7px;

    border: 1px solid #3b4353;
    color: #f4f6fa;

    background: #121722;
}

.channel-link {
    margin: 8px auto 0;

    color: #8993a6;
    text-align: center;

    font-size: 11px;
    line-height: 1.45;
}

.footer {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 7px;

    margin-top: 9px;
    padding-top: 8px;

    border-top: 1px solid #262d3b;

    color: #7d8799;
    font-size: 10px;
    line-height: 1.4;
    text-align: center;
}

.footer svg {
    width: 14px;
    height: 14px;
    flex-shrink: 0;
}

/* Compact level 1 */

.popup.fit-1 {
    padding: 12px 12px 9px;
    border-radius: 25px;
}

.fit-1 .topline {
    margin-bottom: 7px;
}

.fit-1 .repo-badge {
    min-height: 31px;
    padding: 6px 8px;
    font-size: 11px;
}

.fit-1 .month-badge {
    padding: 6px 7px;
    font-size: 10px;
}

.fit-1 .repo-mark {
    width: 48px;
    height: 48px;
    margin-bottom: 7px;
}

.fit-1 .repo-icon {
    width: 48px;
    height: 48px;
}

.fit-1 .heading {
    font-size: 19px;
    line-height: 1.15;
}

.fit-1 .description {
    font-size: 11px;
    line-height: 1.32;
    margin: 6px auto 7px;
}

.fit-1 .badges {
    gap: 5px;
    margin-bottom: 7px;
}

.fit-1 .trust-badge {
    padding: 5px 6px;
    font-size: 10px;
}

.fit-1 .community-actions {
    gap: 6px;
    margin-top: 7px;
}

.fit-1 .action {
    min-height: 36px;
    padding: 8px 9px;
    font-size: 12px;
}

.fit-1 .action-telegram {
    min-height: 38px;
}

.fit-1 .action-later {
    min-height: 35px;
    margin-top: 5px;
}

.fit-1 .channel-link {
    margin-top: 5px;
    font-size: 10px;
}

.fit-1 .footer {
    margin-top: 6px;
    padding-top: 6px;
    font-size: 9px;
}

/* Compact level 2 */

.popup.fit-2 {
    padding: 9px 9px 7px;
    border-radius: 22px;
}

.fit-2 .topline {
    margin-bottom: 5px;
    gap: 5px;
}

.fit-2 .repo-badge {
    min-height: 28px;
    padding: 5px 6px;
    font-size: 10px;
}

.fit-2 .repo-badge svg {
    width: 14px;
    height: 14px;
}

.fit-2 .month-badge {
    padding: 5px 6px;
    font-size: 9px;
}

.fit-2 .repo-mark {
    width: 40px;
    height: 40px;
    margin-bottom: 5px;
}

.fit-2 .repo-icon {
    width: 40px;
    height: 40px;
}

.fit-2 .heading {
    font-size: 17px;
    line-height: 1.12;
    letter-spacing: -.4px;
}

.fit-2 .description {
    font-size: 10px;
    line-height: 1.25;
    margin: 4px auto 5px;
}

.fit-2 .badges {
    gap: 4px;
    margin-bottom: 5px;
}

.fit-2 .trust-badge {
    padding: 4px 5px;
    font-size: 9px;
    border-radius: 8px;
}

.fit-2 .community-actions {
    gap: 5px;
    margin-top: 5px;
}

.fit-2 .action {
    min-height: 31px;
    padding: 6px 7px;
    font-size: 11px;
    border-radius: 11px;
}

.fit-2 .brand-icon svg {
    width: 15px;
    height: 15px;
}

.fit-2 .action-telegram {
    min-height: 33px;
}

.fit-2 .action-later {
    min-height: 30px;
    margin-top: 4px;
}

.fit-2 .channel-link {
    margin-top: 4px;
    font-size: 9px;
    line-height: 1.25;
}

.fit-2 .footer {
    margin-top: 4px;
    padding-top: 4px;
    font-size: 8px;
    line-height: 1.2;
}

.fit-2 .footer svg {
    width: 11px;
    height: 11px;
}

/* Compact level 3 */

.popup.fit-3 {
    padding: 7px 7px 5px;
    border-radius: 19px;
}

.fit-3 .topline {
    margin-bottom: 3px;
}

.fit-3 .repo-badge {
    min-height: 25px;
    padding: 4px 5px;
    font-size: 9px;
}

.fit-3 .month-badge {
    padding: 4px 5px;
    font-size: 8px;
}

.fit-3 .repo-mark {
    width: 34px;
    height: 34px;
    margin-bottom: 3px;
}

.fit-3 .repo-icon {
    width: 34px;
    height: 34px;
}

.fit-3 .heading {
    font-size: 15px;
    line-height: 1.1;
}

.fit-3 .description {
    font-size: 9px;
    line-height: 1.18;
    margin: 3px auto 4px;
}

.fit-3 .badges {
    gap: 3px;
    margin-bottom: 4px;
}

.fit-3 .trust-badge {
    font-size: 8px;
    padding: 3px 4px;
    border-radius: 7px;
}

.fit-3 .community-actions {
    gap: 4px;
    margin-top: 4px;
}

.fit-3 .action {
    min-height: 27px;
    padding: 5px;
    font-size: 10px;
    border-radius: 9px;
}

.fit-3 .brand-icon svg {
    width: 13px;
    height: 13px;
}

.fit-3 .action-telegram {
    min-height: 29px;
}

.fit-3 .action-later {
    min-height: 26px;
    margin-top: 3px;
}

.fit-3 .channel-link {
    font-size: 8px;
    line-height: 1.15;
    margin-top: 3px;
}

.fit-3 .footer {
    font-size: 7px;
    line-height: 1.15;
    margin-top: 3px;
    padding-top: 3px;
    gap: 4px;
}

.fit-3 .footer svg {
    width: 10px;
    height: 10px;
}

/* Very narrow phones */

@media (max-width: 350px) {
    .popup {
        padding: 12px 10px 9px;
        border-radius: 23px;
    }

    .topline {
        margin-bottom: 8px;
    }

    .repo-mark {
        width: 50px;
        height: 50px;
        margin-bottom: 8px;
    }

    .repo-icon {
        width: 50px;
        height: 50px;
    }

    .heading {
        font-size: 19px;
    }

    .description {
        font-size: 11px;
        margin: 6px auto 8px;
    }

    .trust-badge {
        font-size: 10px;
        padding: 5px 6px;
    }

    .action {
        font-size: 12px;
        min-height: 39px;
    }

    .action-telegram {
        min-height: 41px;
    }

    .footer {
        font-size: 9px;
    }
}

@media (prefers-reduced-motion: reduce) {
    *,
    *::before,
    *::after {
        animation-duration: .01ms !important;
        transition-duration: .01ms !important;
    }
}
</style>
</head>

<body>

<div class="overlay" id="communityOverlay">

    <section
        class="popup"
        role="dialog"
        aria-modal="true"
        aria-labelledby="popupTitle"
    >

        <div class="topline">

            <a
                class="repo-badge"
                id="githubRepoButton"
                href="__GITHUB_URL__"
                aria-label="Open Desi Repo on GitHub"
            >
                <span class="brand-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="currentColor">
                        <path d="M12 .9C5.65.9.5 6.05.5 12.4c0 5.08 3.29 9.39 7.86 10.91.58.1.79-.25.79-.56v-2.12c-3.2.7-3.88-1.36-3.88-1.36-.53-1.33-1.28-1.69-1.28-1.69-1.05-.72.08-.71.08-.71 1.16.08 1.77 1.2 1.77 1.2 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.23-1.28-5.23-5.68 0-1.26.45-2.29 1.19-3.1-.12-.29-.52-1.47.11-3.06 0 0 .97-.31 3.17 1.18a11.05 11.05 0 0 1 5.77 0c2.2-1.49 3.17-1.18 3.17-1.18.63 1.59.23 2.77.11 3.06.74.81 1.19 1.84 1.19 3.1 0 4.42-2.69 5.38-5.25 5.67.41.35.78 1.05.78 2.12v3.16c0 .31.21.67.8.56a11.51 11.51 0 0 0 7.84-10.91C23.5 6.05 18.35.9 12 .9Z"/>
                    </svg>
                </span>

                <span class="repo-label">
                    Desi Repo · GitHub ↗
                </span>
            </a>

            <div class="month-badge" id="monthBadge">
                October 2026
            </div>

        </div>

        <div class="repo-mark" aria-label="Desi Repo logo">

            <div
                class="orbit-dots"
                id="orbitDots"
                aria-hidden="true"
            ></div>

            <img
                class="repo-icon"
                id="repoIcon"
                src="__ICON_URL__"
                alt="Desi Repo logo"
            >

        </div>

        <h1 class="heading" id="popupTitle">
            Need Help with a Plugin?<br>
            <span class="blue">Join Desi Repo.</span>
        </h1>

        <p class="description">
            Report broken sources, share bug reports, ask for plugin fixes,
            or request a new extension. Join the Desi Repo community to
            get help, exchange ideas, and connect with other users.
        </p>

        <div class="badges">

            <div class="trust-badge bug">
                🐞 Bugs &amp; Broken Sources
            </div>

            <div class="trust-badge">
                🧩 Plugin Requests &amp; Help
            </div>

        </div>

        <div class="community-actions">

            <a
                class="action action-telegram"
                id="telegramButton"
                href="__TELEGRAM_URL__"
            >
                <span class="brand-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="currentColor">
                        <path d="M21.7 4.2 18.5 19c-.24 1.05-.86 1.3-1.74.81l-4.8-3.54-2.32 2.23c-.26.26-.48.48-.98.48l.35-4.9 8.93-8.07c.39-.35-.08-.54-.6-.19L6.3 12.8l-4.72-1.48c-1.03-.33-1.05-1.04.22-1.54L20.25 2.6c.85-.31 1.6.2 1.45 1.6Z"/>
                    </svg>
                </span>

                Join Telegram Group
            </a>

            <a
                class="action action-discord"
                id="discordButton"
                href="__DISCORD_URL__"
            >
                <span class="brand-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="currentColor">
                        <path d="M19.7 5.1A18.2 18.2 0 0 0 15.2 3.7l-.6 1.2a16.7 16.7 0 0 0-5.2 0l-.6-1.2a18.2 18.2 0 0 0-4.5 1.4C1.5 9.3.7 13.4 1.1 17.5a18 18 0 0 0 5.5 2.8l1.2-1.9-1.9-.9.5-.4c3.7 1.7 7.7 1.7 11.3 0l.5.4-1.9.9 1.2 1.9a18 18 0 0 0 5.5-2.8c.5-4.8-.8-8.9-3.3-12.4ZM8.7 14.9c-1.1 0-2-1-2-2.2s.9-2.2 2-2.2 2 1 2 2.2-.9 2.2-2 2.2Zm6.6 0c-1.1 0-2-1-2-2.2s.9-2.2 2-2.2 2 1 2 2.2-.9 2.2-2 2.2Z"/>
                    </svg>
                </span>

                Join Discord Server
            </a>

        </div>

        <button
            class="action action-later"
            id="dismissPopup"
            type="button"
        >
            Maybe Later
        </button>

        <p class="channel-link">
            Ask for help · Report bugs · Request plugins · Share feedback
        </p>

        <footer class="footer">

            <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
            >
                <rect x="4" y="10" width="16" height="11" rx="2"/>
                <path d="M8 10V7a4 4 0 0 1 8 0v3"/>
            </svg>

            <span>
                A community for plugin help, bug reports, feature requests, and discussion.
            </span>

        </footer>

    </section>

</div>

<script>
(() => {
    const CONFIG = {
        telegramUrl: "__TELEGRAM_URL__",
        githubUrl: "__GITHUB_URL__",
        discordUrl: "__DISCORD_URL__"
    };

    const overlay =
        document.getElementById("communityOverlay");

    const popup =
        document.querySelector(".popup");

    const dismissButton =
        document.getElementById("dismissPopup");

    const orbitContainer =
        document.getElementById("orbitDots");

    const repoIcon =
        document.getElementById("repoIcon");

    /*
     * Automatically fit content into the available popup height.
     * No scrolling is enabled.
     */
    function fitPopup() {
        if (!popup) return;

        popup.classList.remove(
            "fit-1",
            "fit-2",
            "fit-3"
        );

        const levels = [
            "fit-1",
            "fit-2",
            "fit-3"
        ];

        for (const level of levels) {
            if (
                popup.scrollHeight <=
                popup.clientHeight + 1
            ) {
                break;
            }

            popup.classList.add(level);
        }
    }

    function scheduleFit() {
        requestAnimationFrame(() => {
            fitPopup();

            requestAnimationFrame(() => {
                fitPopup();
            });
        });
    }

    scheduleFit();

    window.addEventListener(
        "resize",
        scheduleFit
    );

    window.addEventListener(
        "orientationchange",
        scheduleFit
    );

    if (repoIcon) {
        repoIcon.addEventListener(
            "load",
            scheduleFit
        );

        repoIcon.addEventListener(
            "error",
            scheduleFit
        );
    }

    if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(
            scheduleFit
        ).catch(() => {});
    }

    function closePopup() {
        try {
            if (
                window.AndroidPopup &&
                typeof window.AndroidPopup.close === "function"
            ) {
                window.AndroidPopup.close();
                return;
            }
        } catch (e) {}

        if (overlay) {
            overlay.style.display = "none";
        }
    }

    dismissButton.addEventListener(
        "click",
        closePopup
    );

    document.getElementById(
        "monthBadge"
    ).textContent = new Intl.DateTimeFormat(
        "en",
        {
            month: "long",
            year: "numeric"
        }
    ).format(new Date());

    document.getElementById(
        "telegramButton"
    ).href = CONFIG.telegramUrl;

    document.getElementById(
        "githubRepoButton"
    ).href = CONFIG.githubUrl;

    const discordButton =
        document.getElementById("discordButton");

    if (
        CONFIG.discordUrl &&
        /^https?:\/\//i.test(CONFIG.discordUrl)
    ) {
        discordButton.href =
            CONFIG.discordUrl;
    } else {
        discordButton.href = "#discord";

        discordButton.addEventListener(
            "click",
            event => {
                event.preventDefault();

                alert(
                    "Add your Discord invite URL in DISCORD_URL in DesiExtensionPopup.kt first."
                );
            }
        );
    }

    document.getElementById(
        "telegramButton"
    ).addEventListener("click", () => {
        try {
            if (
                window.AndroidPopup &&
                typeof window.AndroidPopup.telegramClicked === "function"
            ) {
                window.AndroidPopup.telegramClicked();
            }
        } catch (e) {}
    });

    if (orbitContainer) {
        const dotCount = 12;

        const random = (min, max) =>
            Math.random() * (max - min) + min;

        const colours = [
            "#FF9933",
            "#FFFFFF",
            "#138808"
        ];

        for (let i = 0; i < dotCount; i++) {
            const path =
                document.createElement("span");

            const dot =
                document.createElement("span");

            path.className = "orbit-path";
            dot.className = "orbit-dot";

            path.style.setProperty(
                "--duration",
                random(14, 28) + "s"
            );

            path.style.setProperty(
                "--delay",
                -random(0, 28) + "s"
            );

            path.style.setProperty(
                "--direction",
                Math.random() > 0.5
                    ? "normal"
                    : "reverse"
            );

            dot.style.setProperty(
                "--radius",
                random(37, 49) + "px"
            );

            dot.style.setProperty(
                "--size",
                random(2, 5) + "px"
            );

            dot.style.setProperty(
                "--opacity",
                random(0.35, 0.95)
            );

            const colour =
                colours[i % colours.length];

            dot.style.setProperty(
                "--tricolour",
                colour
            );

            dot.style.setProperty(
                "--dot-glow",
                colour + "CC"
            );

            dot.style.setProperty(
                "--glow-duration",
                random(1.5, 5) + "s"
            );

            dot.style.setProperty(
                "--glow-delay",
                -random(0, 5) + "s"
            );

            path.appendChild(dot);
            orbitContainer.appendChild(path);
        }
    }

    // Refit after the orbit animation elements are created.
    scheduleFit();

})();
</script>

</body>
</html>
    """.trimIndent()
}
