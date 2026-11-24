package io.github.hazymorning.amsel.ui.screens.setup

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.RetainObserver
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.HA_RETURN_URL
import io.github.hazymorning.amsel.api.isLanHost
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.WaitingState

enum class HomeAssistantPageState { Opening, Shown, Unreachable }

/**
 * JavaScript and DOM storage are on because Music Assistant's callback page needs them. The return URL
 * never loads: its `code` is the session token (docs/API-NOTES.md, "Sign in with Home Assistant").
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeAssistantSignIn(
    url: String,
    onCode: (String) -> Unit,
    onFailed: () -> Unit,
    onClose: () -> Unit,
    /** Whether plain http may leave the phone now: not on mobile data without a VPN. */
    plainHttp: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val retained = retain(url) { RetainedPage(context, url) }
    var page by retained.page
    val code by rememberUpdatedState(onCode)
    val plainAllowed by rememberUpdatedState(plainHttp)
    val failed by rememberUpdatedState(onFailed)
    val paper = MaterialTheme.colorScheme.surface.toArgb()
    HomeAssistantFrame(
        page = page,
        onClose = onClose,
        onRetry = {
            page = HomeAssistantPageState.Opening
            retained.web.loadUrl(url)
        },
        modifier = modifier,
    ) {
        AndroidView(
            factory = { context ->
                retained.attach(context).apply {
                    setBackgroundColor(paper)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = false
                    // Set on every attach, so the callbacks reach the current activity's composition.
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (!target.startsWith(HA_RETURN_URL)) return false
                            val returned = homeAssistantCode(target)
                            if (returned.isNullOrBlank()) failed() else code(returned)
                            return true
                        }

                        // Plain http only to a host that is local by its form (isLanHost), as the app's client
                        // allows: a hostile network's DNS can map any name to a private address.
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val home = request.url.host?.let(::isLanHost) == true && plainAllowed()
                            if (request.url.scheme != "http" || home) return null
                            if (request.isForMainFrame) view.post { page = HomeAssistantPageState.Unreachable }
                            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, null)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (page == HomeAssistantPageState.Opening) page = HomeAssistantPageState.Shown
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                            page = HomeAssistantPageState.Unreachable
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) page = HomeAssistantPageState.Unreachable
                        }
                    }
                }
            },
            update = { it.visibility = if (page == HomeAssistantPageState.Shown) View.VISIBLE else View.INVISIBLE },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Outlives activity recreation for as long as the step lasts. Between activities the WebView's context is
 * the application, so no destroyed activity leaks; when the step ends it is wiped and destroyed.
 */
private class RetainedPage(context: Context, url: String) : RetainObserver {
    private val host = MutableContextWrapper(context)
    val web = WebView(host).apply { loadUrl(url) }
    val page = mutableStateOf(HomeAssistantPageState.Opening)

    fun attach(context: Context): WebView {
        host.baseContext = context
        (web.parent as? ViewGroup)?.removeView(web)
        return web
    }

    override fun onExitedComposition() {
        host.baseContext = host.applicationContext
    }

    override fun onRetired() = end()

    override fun onUnused() = end()

    override fun onRetained() = Unit

    override fun onEnteredComposition() = Unit

    private fun end() {
        // Music Assistant's callback page leaves the session token in localStorage
        // (docs/API-NOTES.md, "Sign in with Home Assistant").
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        web.destroy()
    }
}

/** [web] stays composed under the waiting and error states, hidden, so it keeps loading and never flashes blank. */
@Composable
fun HomeAssistantFrame(
    page: HomeAssistantPageState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    web: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        PageTopBar(
            stringResource(R.string.setup_home_assistant),
            onBack = onClose,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            backIcon = R.drawable.ic_close,
            backLabel = stringResource(R.string.close),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ),
        ) {
            web()
            when (page) {
                HomeAssistantPageState.Opening -> WaitingState(
                    stringResource(R.string.setup_opening_home_assistant),
                    Modifier.fillMaxSize(),
                )

                HomeAssistantPageState.Unreachable -> EmptyState(
                    R.drawable.ic_offline,
                    stringResource(R.string.setup_error_home_assistant_page),
                    Modifier.fillMaxSize(),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                )

                HomeAssistantPageState.Shown -> Unit
            }
        }
    }
}

fun homeAssistantCode(url: String): String? =
    if (url.startsWith(HA_RETURN_URL)) url.toUri().getQueryParameter("code") else null
