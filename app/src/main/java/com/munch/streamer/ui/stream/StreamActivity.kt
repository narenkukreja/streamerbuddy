package com.munch.streamer.ui.stream

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.munch.streamer.R
import com.munch.streamer.data.model.StreamItem
import com.munch.streamer.data.model.StreamSource
import com.munch.streamer.databinding.ActivityStreamBinding
import com.munch.streamer.di.ServiceLocator
import kotlinx.coroutines.launch

class StreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamBinding

    private val viewModel: StreamViewModel by viewModels {
        StreamViewModelFactory(ServiceLocator.repository)
    }
    private val sourceLanguageKey: (StreamSource) -> String = { "${it.source}:${it.id}" }

    private var availableSources: List<StreamSource> = emptyList()
    private var currentSource: StreamSource? = null
    private var embedUrls: List<String> = emptyList()
    private var currentEmbedIndex: Int = 0
    private var currentEmbedHost: String? = null
    private var restoredEmbedIndex: Int = 0
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var isFullScreenVideo: Boolean = false
    private var lastFullScreenAt: Long = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val matchTitle = intent.getStringExtra(EXTRA_MATCH_TITLE).orEmpty()
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
        val savedSourceKey = savedInstanceState?.getString(STATE_SOURCE_KEY)
        val savedSourceId = savedInstanceState?.getString(STATE_SOURCE_ID)
        restoredEmbedIndex = savedInstanceState?.getInt(STATE_EMBED_INDEX, 0) ?: 0
        val initialSource = if (!sourceKey.isNullOrBlank() && !sourceId.isNullOrBlank()) {
            StreamSource(sourceKey, sourceId)
        } else if (!savedSourceKey.isNullOrBlank() && !savedSourceId.isNullOrBlank()) {
            StreamSource(savedSourceKey, savedSourceId)
        } else {
            null
        }
        availableSources = (intent.getSerializableExtra(EXTRA_SOURCES) as? ArrayList<StreamSource>)
            ?.toList()
            .orEmpty()
        Log.d(TAG, "Incoming sources: initial=$initialSource, list=${availableSources.joinToString()}")
        if (initialSource != null && availableSources.none { it.source == initialSource.source && it.id == initialSource.id }) {
            availableSources = listOf(initialSource) + availableSources
        }

        binding.streamToolbar.title = matchTitle.ifBlank { getString(R.string.stream_activity_title) }
        binding.streamToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.selectSourceButton.setOnClickListener { promptSourceSelection() }
        binding.pipButton.setOnClickListener { requestPip() }
        binding.pipButton.isEnabled = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        binding.streamWebView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.streamLoading.isVisible = newProgress < 100
                }
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    binding.fullscreenContainer.removeAllViews()
                    if (view != null) {
                        binding.fullscreenContainer.addView(
                            view,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                    binding.fullscreenContainer.isVisible = true
                    binding.streamWebView.isVisible = false
                    binding.controlBar.isVisible = false
                    binding.streamToolbar.isVisible = false
                    hideSystemBars()
                    isFullScreenVideo = true
                    lastFullScreenAt = SystemClock.elapsedRealtime()
                }

                override fun onHideCustomView() {
                    if (customView == null) return
                    binding.fullscreenContainer.removeAllViews()
                    binding.fullscreenContainer.isVisible = false
                    binding.streamWebView.isVisible = true
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    isFullScreenVideo = false
                    applyOrientationUi()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val host = runCatching { request?.url?.host }.getOrNull()
                    return if (currentEmbedHost != null && host != null && host != currentEmbedHost) {
                        Log.w(TAG, "Blocking navigation to non-embed host=$host while on $currentEmbedHost")
                        true
                    } else {
                        false
                    }
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Log.d(TAG, "WebView page started: $url")
                    binding.streamLoading.isVisible = true
                    super.onPageStarted(view, url, favicon)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "WebView page finished: $url")
                    binding.streamLoading.isVisible = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val isMainFrame = request?.isForMainFrame ?: true
                    val url = request?.url?.toString()
                    Log.e(TAG, "WebView error on $url mainFrame=$isMainFrame code=${error?.errorCode} desc=${error?.description}")
                    if (isMainFrame) {
                        val host = try { request?.url?.host } catch (e: Exception) { null }
                        if (host != null && currentEmbedHost != null && host != currentEmbedHost) {
                            Log.w(TAG, "Ignoring main-frame error from non-embed host=$host, reloading $currentEmbedHost")
                            reloadCurrentEmbed()
                        } else {
                            tryNextEmbed()
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderState(state)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sourceLanguages.collect { updateSourceLanguages(it) }
            }
        }

        if (availableSources.isEmpty()) {
            showError(getString(R.string.no_stream_available))
        } else if (availableSources.size == 1 || initialSource != null) {
            selectSource(initialSource ?: availableSources.first(), initialIndex = restoredEmbedIndex)
        } else {
            promptSourceSelection()
        }

        if (availableSources.isNotEmpty()) {
            viewModel.prefetchLanguages(availableSources)
        }

        applyOrientationUi()
    }

    private fun renderState(state: StreamUiState) {
        binding.streamLoading.isVisible = state.isLoading
        binding.streamError.isVisible = state.errorMessage != null
        binding.streamError.text = state.errorMessage

        embedUrls = state.streams.mapNotNull { it.embedUrl?.takeIf { url -> url.isNotBlank() } }
        if (!state.isLoading && state.streams.isNotEmpty()) {
            updateCurrentSourceLanguage(state.streams)
        }
        if (embedUrls.isNotEmpty()) {
            val target = embedUrls.getOrNull(currentEmbedIndex) ?: embedUrls.first()
            if (target != binding.streamWebView.url) {
                Log.d(TAG, "Loading embed[$currentEmbedIndex/${embedUrls.size}] = $target")
                currentEmbedHost = runCatching { java.net.URI(target).host }.getOrNull()
                binding.streamWebView.loadUrl(target)
            }
            binding.pipButton.isEnabled = true
        } else if (!state.isLoading && state.errorMessage == null) {
            showError(getString(R.string.no_stream_available))
        }
    }

    private fun promptSourceSelection() {
        if (availableSources.isEmpty()) {
            showError(getString(R.string.no_stream_available))
            return
        }
        val labels = availableSources.map { formatSourceLabel(it) }.toTypedArray()
        Log.d(TAG, "Prompting source picker for: ${labels.joinToString()}")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_source)
            .setItems(labels) { dialog, which ->
                dialog.dismiss()
                selectSource(availableSources[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectSource(source: StreamSource, initialIndex: Int = 0) {
        currentSource = source
        currentEmbedIndex = initialIndex
        val label = formatSourceLabel(source)
        binding.selectSourceButton.contentDescription =
            getString(R.string.change_source) + " $label"
        binding.streamToolbar.subtitle = label
        Log.d(TAG, "Selected source=${source.source}, id=${source.id}")
        viewModel.loadStreams(source)
    }

    private fun formatSourceName(raw: String?): String = when (raw?.lowercase()) {
        "alpha" -> "Alpha"
        "bravo" -> "Bravo"
        "charlie" -> "Charlie"
        "delta" -> "Delta"
        "echo" -> "Echo"
        "foxtrot" -> "Foxtrot"
        "golf" -> "Golf"
        "hotel" -> "Hotel"
        "intel" -> "Intel"
        else -> raw?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } ?: "Unknown"
    }

    private fun formatSourceLabel(source: StreamSource): String {
        val base = formatSourceName(source.source)
        val language = source.language?.takeIf { it.isNotBlank() }
        return if (language.isNullOrEmpty()) base else "$base ($language)"
    }

    private fun updateCurrentSourceLanguage(streams: List<StreamItem>) {
        val languageLabel = streams
            .mapNotNull { it.language?.takeIf { lang -> lang.isNotBlank() } }
            .distinct()
            .joinToString(", ")
            .ifBlank { null } ?: return

        val activeSource = currentSource ?: return
        val updated = activeSource.copy(language = languageLabel)
        currentSource = updated
        availableSources = availableSources.map {
            if (it.source == activeSource.source && it.id == activeSource.id) updated else it
        }
        val label = formatSourceLabel(updated)
        binding.selectSourceButton.contentDescription =
            getString(R.string.change_source) + " $label"
        binding.streamToolbar.subtitle = label
    }

    private fun updateSourceLanguages(languageMap: Map<String, String>) {
        if (languageMap.isEmpty()) return
        var changed = false
        availableSources = availableSources.map { source ->
            val newLang = languageMap[sourceLanguageKey(source)]
            if (!newLang.isNullOrBlank() && source.language != newLang) {
                changed = true
                source.copy(language = newLang)
            } else {
                source
            }
        }
        if (changed) {
            currentSource?.let { current ->
                val updatedCurrent = availableSources.firstOrNull {
                    it.source == current.source && it.id == current.id
                }
                if (updatedCurrent != null) {
                    currentSource = updatedCurrent
                    val label = formatSourceLabel(updatedCurrent)
                    binding.selectSourceButton.contentDescription =
                        getString(R.string.change_source) + " $label"
                    binding.streamToolbar.subtitle = label
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.streamError.isVisible = true
        binding.streamError.text = message
        binding.streamLoading.isVisible = false
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun applyOrientationUi() {
        if (isInPictureInPictureMode || isFullScreenVideo) return
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.streamToolbar.isVisible = !isLandscape
        binding.controlBar.isVisible = !isLandscape

        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (isLandscape) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun tryNextEmbed() {
        val nextIndex = currentEmbedIndex + 1
        if (nextIndex < embedUrls.size) {
            currentEmbedIndex = nextIndex
            val target = embedUrls[nextIndex]
            Log.d(TAG, "Trying next embed [$nextIndex/${embedUrls.size}]: $target")
            currentEmbedHost = runCatching { java.net.URI(target).host }.getOrNull()
            binding.streamWebView.loadUrl(target)
        } else {
            showError(getString(R.string.no_stream_available))
        }
    }

    private fun reloadCurrentEmbed() {
        val target = embedUrls.getOrNull(currentEmbedIndex)
        if (target != null) {
            Log.d(TAG, "Reloading current embed [$currentEmbedIndex]: $target")
            binding.streamWebView.loadUrl(target)
        } else if (embedUrls.isNotEmpty()) {
            currentEmbedIndex = 0
            currentEmbedHost = runCatching { java.net.URI(embedUrls.first()).host }.getOrNull()
            binding.streamWebView.loadUrl(embedUrls.first())
        } else {
            showError(getString(R.string.no_stream_available))
        }
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun enterPipIfPossible(manual: Boolean, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (manual) Toast.makeText(this, R.string.pip_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        if (embedUrls.isEmpty()) {
            if (manual) showError(getString(R.string.no_stream_available))
            return
        }
        if (!manual && !force && !isFullScreenVideo) {
            Log.d(TAG, "Skipping auto PiP because not fullscreen video")
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        Log.d(TAG, "Entering PiP mode (manual=$manual) with aspect 16:9")
        enterPictureInPictureMode(params)
    }

    private fun requestPip() {
        if (!isFullScreenVideo) {
            Toast.makeText(this, R.string.pip_fullscreen_hint, Toast.LENGTH_SHORT).show()
            return
        }
        lastFullScreenAt = SystemClock.elapsedRealtime()
        val invoked = tryRequestHtmlVideoPip(
            fallbackToActivity = false,
            showErrorToast = false,
            onFailure = { showBrowserFallback() }
        )
        if (!invoked) {
            showBrowserFallback()
        }
    }

    private fun tryRequestHtmlVideoPip(
        fallbackToActivity: Boolean,
        showErrorToast: Boolean,
        onFailure: (() -> Unit)? = null
    ): Boolean {
        return try {
            binding.streamWebView.evaluateJavascript(
                """
                (function() {
                    try {
                        const video = document.querySelector('video');
                        if (!video) return 'no-video';
                        if (video.disablePictureInPicture) return 'disabled';
                        if (!document.pictureInPictureEnabled) return 'not-enabled';
                        return video.requestPictureInPicture().then(() => 'ok').catch(e => 'err:' + (e && e.message ? e.message : 'fail'));
                    } catch (e) {
                        return 'exception';
                    }
                })();
                """.trimIndent()
            ) { raw ->
                val result = raw?.trim('"')
                Log.d(TAG, "HTML PiP attempt result=$result")
                val isError = result == null || (
                    result.startsWith("err") ||
                        result == "no-video" ||
                        result == "not-enabled" ||
                        result == "disabled" ||
                        result == "exception"
                    )
                if (isError) {
                    if (fallbackToActivity) {
                        enterPipIfPossible(manual = true, force = true)
                    } else if (showErrorToast) {
                        Toast.makeText(this, R.string.pip_not_supported, Toast.LENGTH_SHORT).show()
                    }
                    onFailure?.invoke()
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "HTML PiP request failed", e)
            if (fallbackToActivity) {
                enterPipIfPossible(manual = true, force = true)
            } else if (showErrorToast) {
                Toast.makeText(this, R.string.pip_not_supported, Toast.LENGTH_SHORT).show()
            }
            onFailure?.invoke()
            false
        }
    }

    private fun showBrowserFallback() {
        val target = embedUrls.getOrNull(currentEmbedIndex) ?: binding.streamWebView.url
        if (target.isNullOrBlank()) {
            Toast.makeText(this, R.string.no_stream_available, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pip_open_browser)
            .setMessage(R.string.pip_open_browser_desc)
            .setPositiveButton(R.string.open_in_browser) { dialog, _ ->
                dialog.dismiss()
                openInCustomTab(target)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openInCustomTab(url: String) {
        try {
            val intent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            intent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            Log.w(TAG, "CustomTab launch failed, falling back to VIEW intent", e)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (inner: Exception) {
                Log.e(TAG, "No handler for VIEW intent", inner)
                Toast.makeText(this, R.string.pip_not_supported, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val recentlyFullscreen = SystemClock.elapsedRealtime() - lastFullScreenAt < 5000
        if (!isFullScreenVideo && !recentlyFullscreen) return

        val invoked = tryRequestHtmlVideoPip(
            fallbackToActivity = true,
            showErrorToast = false,
            onFailure = null
        )
        if (!invoked) {
            enterPipIfPossible(manual = false, force = true)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            binding.streamToolbar.isVisible = false
            binding.controlBar.isVisible = false
        } else {
            applyOrientationUi()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationUi()
    }

    override fun onDestroy() {
        binding.streamWebView.apply {
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isFullScreenVideo) {
            (binding.streamWebView.webChromeClient as? WebChromeClient)?.onHideCustomView()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SOURCE_KEY, currentSource?.source)
        outState.putString(STATE_SOURCE_ID, currentSource?.id)
        outState.putInt(STATE_EMBED_INDEX, currentEmbedIndex)
    }

    override fun onResume() {
        super.onResume()
        applyOrientationUi()
    }

    companion object {
        const val EXTRA_MATCH_TITLE = "match_title"
        const val EXTRA_SOURCE_KEY = "source_key"
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_SOURCES = "sources"
        private const val TAG = "StreamActivity"
        private const val STATE_SOURCE_KEY = "state_source_key"
        private const val STATE_SOURCE_ID = "state_source_id"
        private const val STATE_EMBED_INDEX = "state_embed_index"
    }
}
