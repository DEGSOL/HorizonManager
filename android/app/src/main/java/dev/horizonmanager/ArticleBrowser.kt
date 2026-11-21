package dev.horizonmanager

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

internal fun linkUri(value: String): Uri? {
    val url = value.trim()
    if (url.any(Char::isISOControl)) return null
    val uri = Uri.parse(url).normalizeScheme()
    return when (uri.scheme) {
        "http", "https" -> uri.takeIf { !it.host.isNullOrBlank() && it.userInfo == null }
        "mailto", "tel" -> uri.takeIf { !it.schemeSpecificPart.isNullOrBlank() }
        else -> null
    }
}

@Composable
internal fun HovqLinks(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var fallback by rememberSaveable { mutableStateOf<String?>(null) }
    val handler = remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val target = linkUri(uri) ?: return
                when (target.scheme) {
                    "mailto" -> context.tryOpen(Intent(Intent.ACTION_SENDTO, target))
                    "tel" -> context.tryOpen(Intent(Intent.ACTION_DIAL, target))
                    else -> if (!openCustomTab(context, target)) fallback = target.toString()
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (fallback != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            CompositionLocalProvider(LocalUriHandler provides handler, content = content)
        }
        fallback?.let { address -> WebBrowser(address) { fallback = null } }
    }
}

private fun openCustomTab(context: Context, uri: Uri): Boolean {
    val provider = CustomTabsClient.getPackageName(context, emptyList()) ?: return false
    val tab = CustomTabsIntent.Builder().setShowTitle(true)
        .setOpenInBrowserButtonState(CustomTabsIntent.OPEN_IN_BROWSER_STATE_ON)
        .setSendToExternalDefaultHandlerEnabled(false)
        .build()
    tab.intent.setPackage(provider)
    return try {
        tab.launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun Context.tryOpen(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, ui(R.string.browser_no_handler), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun WebBrowser(initial: String, close: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var current by rememberSaveable(initial) { mutableStateOf(initial) }
    var menu by remember { mutableStateOf(false) }
    val back = {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else close()
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).editorialInsets()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconAction(Icons.AutoMirrored.Filled.ArrowBack, ui(R.string.ui_back), onClick = back)
            Text(current, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
            Box {
                GlassIconAction(Icons.Default.MoreVert, ui(R.string.browser_actions)) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(ui(R.string.browser_open_external)) }, onClick = {
                        menu = false
                        linkUri(current)?.let { context.tryOpen(Intent(Intent.ACTION_VIEW, it).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    })
                }
            }
        }
        AndroidView(
            factory = { host ->
                WebView(host).apply {
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !isWeb(request.url.toString())

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (isWeb(url)) current = url
                        }
                    }
                    webView = this
                    loadUrl(current)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onReset = null,
            onRelease = { it.destroy() },
        )
    }
}
