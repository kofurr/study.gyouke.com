package com.gyouke.study

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.gyouke.study.ui.theme.SuperWebViewTheme

private const val WEBSITE = "https://study.gyouke.com"

/**
 * Main activity of the application.
 * Hosts the Jetpack Compose UI and manages the WebView.
 */
class MainActivity : ComponentActivity() {

    // 保存网页发起的权限请求，等用户授权后回调
    private var pendingWebPermissionRequest: PermissionRequest? = null

    // 同时申请麦克风和相机权限
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.entries
            .filter { it.value }
            .map { it.key }
            .flatMap { androidPerm ->
                when (androidPerm) {
                    android.Manifest.permission.RECORD_AUDIO ->
                        listOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    android.Manifest.permission.CAMERA ->
                        listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    else -> emptyList()
                }
            }
            .toTypedArray()

        pendingWebPermissionRequest?.let { req ->
            if (granted.isNotEmpty()) req.grant(granted) else req.deny()
        }
        pendingWebPermissionRequest = null
    }

    /**
     * Called when the activity is first created.
     * Sets up the splash screen and the main content view.
     */
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            SuperWebViewTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .imePadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onWebPermissionRequest = { request ->
                            pendingWebPermissionRequest = request
                            val androidPerms = mutableListOf<String>()
                            if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                                androidPerms.add(android.Manifest.permission.RECORD_AUDIO)
                            }
                            if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                                androidPerms.add(android.Manifest.permission.CAMERA)
                            }
                            if (androidPerms.isNotEmpty()) {
                                requestPermissions.launch(androidPerms.toTypedArray())
                            } else {
                                request.deny()
                            }
                        }
                    )
                }
            }
        }
    }

}

/**
 * Composable function that defines the main screen layout.
 * Includes a WebView, a progress indicator, and handles full-screen video playback.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun MainScreen(onWebPermissionRequest: (PermissionRequest) -> Unit) {
    var progress by rememberSaveable { mutableIntStateOf(0) }
    var fullScreenView: View? by remember { mutableStateOf(null) }

    Box(
        Modifier.fillMaxSize()
    ) {
        WebViewWithRefresher(
            modifier = Modifier.fillMaxSize(),
            updateProgress = { currentProgress -> progress = currentProgress },
            onViewReceived = { fullScreenView = it },
            onWebPermissionRequest = onWebPermissionRequest,
        )
        ProgressIndicator(progress)
    }

    // Shows the full-screen view (e.g., for videos) when fullScreenView is not null
    AnimatedVisibility(fullScreenView != null) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context -> fullScreenView!! })
    }

}

/**
 * Displays a linear progress indicator.
 * The indicator is visible only when loading progress is between 1% and 99%.
 *
 * @param progress The current loading progress (0-100).
 */
@Composable
private fun ProgressIndicator(progress: Int) {
    AnimatedVisibility(
        modifier = Modifier.fillMaxWidth(), visible = progress in 1..99
    ) {
        LinearProgressIndicator(progress = { progress.toFloat() / 100 })
    }
}

/**
 * Wraps a WebView with a SwipeRefreshLayout.
 * It handles WebView configuration, client settings, and back navigation.
 *
 * @param modifier
 * @param updateProgress Callback to update the loading progress.
 * @param onViewReceived Callback invoked when a custom view (e.g., for full-screen video) is shown or hidden.
 * @param onWebPermissionRequest Callback invoked when the webpage requests camera/mic permission.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewWithRefresher(
    modifier: Modifier = Modifier,
    updateProgress: (Int) -> Unit,
    onViewReceived: (View?) -> Unit,
    onWebPermissionRequest: (PermissionRequest) -> Unit,
) {
    var webView: WebView? = null
    val webViewId = View.generateViewId()
    var isBackEnabled by rememberSaveable { mutableStateOf(false) }
    val primaryColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    val secondaryColorArgb = MaterialTheme.colorScheme.secondary.toArgb()
    val tertiaryColorArgb = MaterialTheme.colorScheme.tertiary.toArgb()

    BackHandler(enabled = isBackEnabled) {
        webView?.goBack()
    }

    AndroidView(modifier = modifier.fillMaxSize(), factory = { context ->
        val swipeRefreshLayout = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(primaryColorArgb, secondaryColorArgb, tertiaryColorArgb)
            setOnRefreshListener { webView?.reload() }
        }

        webView = WebView(context).apply {
            id = webViewId
            setLayerType(View.LAYER_TYPE_HARDWARE, null) // 硬件加速，修复画板触摸坐标偏移
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            webViewClient = object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    if (request?.url.toString().startsWith(WEBSITE)) return false
                    try {
                        Intent(Intent.ACTION_VIEW, request?.url).apply {
                            context.startActivity(this, null)
                        }
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) e.printStackTrace()
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isBackEnabled = view?.canGoBack() == true
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    swipeRefreshLayout.isRefreshing = false
                }
            }

            webChromeClient = object : WebChromeClient() {

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    updateProgress(newProgress)
                    if (newProgress == 100) swipeRefreshLayout.isRefreshing = false
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    onViewReceived(view)
                    super.onShowCustomView(view, callback)
                }

                override fun onHideCustomView() {
                    onViewReceived(null)
                    super.onHideCustomView()
                }

                // 网页请求麦克风或摄像头时，转交给 Activity 处理系统权限弹窗
                override fun onPermissionRequest(request: PermissionRequest) {
                    onWebPermissionRequest(request)
                }
            }

            with(settings) {
                domStorageEnabled = true
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false  // 允许自动播放音频（语音对话需要）
                setSupportZoom(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isAlgorithmicDarkeningAllowed = true
                }
            }
            loadUrl(WEBSITE)
        }

        swipeRefreshLayout.addView(webView)
        swipeRefreshLayout
    }, update = { swipeRefreshLayout ->
        webView = swipeRefreshLayout.findViewById(webViewId)
    })
}
