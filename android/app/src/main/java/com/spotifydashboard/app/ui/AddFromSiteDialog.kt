package com.spotifydashboard.app.ui

import android.content.Context
import android.webkit.ValueCallback
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray

/**
 * In-app browser used by "Add from site" (card 270). Loads any URL, extracts
 * open.spotify.com/album links from the page (and the current URL), and lets
 * the owner pick which albums to add to the dashboard library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFromSiteDialog(
    adding: Boolean,
    albumLabels: Map<String, String> = emptyMap(),
    onPrefetch: (List<String>) -> Unit = {},
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit,
    initialUrl: String? = null
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("add_from_site_prefs", Context.MODE_PRIVATE)
    }
    var urlText by remember {
        mutableStateOf(initialUrl ?: prefs.getString("last_url", null) ?: "https://open.spotify.com")
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var autoTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(webView, initialUrl) {
        val wv = webView
        if (wv != null && initialUrl != null && !autoTriggered) {
            autoTriggered = true
            val url = if (initialUrl.startsWith("http://") || initialUrl.startsWith("https://")) initialUrl else "https://$initialUrl"
            webView?.loadUrl(url)
        }
    }
    var pageError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf<String?>(null) }
    val detected = remember { mutableStateListOf<String>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(detected.toSet()) {
        if (detected.isNotEmpty()) onPrefetch(detected.toList())
    }

    // Safety net: never leave the Scan button stuck on "Scanning…".
    LaunchedEffect(scanning) {
        if (scanning) {
            kotlinx.coroutines.delay(180_000)
            scanning = false
        }
    }

    fun normalize(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    // Adds newly seen IDs without disturbing boxes the user already ticked/unticked.
    fun mergeLinks(ids: List<String>) {
        ids.distinct().forEach { id ->
            if (id !in detected) {
                detected.add(id)
                selected[id] = true
            }
        }
    }

    fun parseIds(value: String?): List<String> {
        val ids = mutableListOf<String>()
        if (value == null || value == "null") return ids
        try {
            val arr = JSONArray(value)
            for (i in 0 until arr.length()) ids.add(arr.getString(i))
        } catch (_: Exception) {
            try {
                val arr = JSONArray(value.trim().trim('"'))
                for (i in 0 until arr.length()) ids.add(arr.getString(i))
            } catch (_: Exception) {
            }
        }
        return ids
    }

    fun extractLinks(view: WebView) {
        // Match open.spotify.com/album/ID, spotify:album:ID, and embedded URIs.
        // Sites such as rateyourmusic.com only render their Spotify links once
        // the user scrolls, so the scanner re-runs on scroll (debounced) and on
        // DOM mutations instead of forcing the page to scroll on its own.
        val js = """
            (function(){
                var out=[], seen={};
                function add(u){
                    if(!u) return;
                    var m=String(u).match(/open\.spotify\.com\/(?:embed\/)?album\/([A-Za-z0-9]{22})/);
                    if(!m) m=String(u).match(/spotify:album:([A-Za-z0-9]{22})/);
                    if(!m) m=String(u).match(/[?&]uri=spotify%3Aalbum%3A([A-Za-z0-9]{22})/);
                    if(m && !seen[m[1]]){ seen[m[1]]=true; out.push(m[1]); }
                }
                function scan(deep){
                    out=[]; seen={};
                    if(location.href) add(location.href);
                    try{
                        document.querySelectorAll('a[href]').forEach(function(a){ add(a.href); });
                        document.querySelectorAll('[data-uri]').forEach(function(e){ add(e.getAttribute('data-uri')); });
                        document.querySelectorAll('[data-spotify-uri]').forEach(function(e){ add(e.getAttribute('data-spotify-uri')); });
                        document.querySelectorAll('iframe[src]').forEach(function(f){ add(f.src); });
                    }catch(e){}
                    if(deep){
                        // Raw HTML fallback for links hidden in scripts/attributes.
                        try{
                            var html = document.documentElement.outerHTML || '';
                            var re = /open\.spotify\.com\/(?:embed\/)?album\/([A-Za-z0-9]{22})/g;
                            var mm;
                            while((mm = re.exec(html)) !== null){ if(!seen[mm[1]]){ seen[mm[1]]=true; out.push(mm[1]); } }
                        }catch(e){}
                    }
                    return out;
                }
                function push(deep){
                    var r = scan(deep);
                    try{
                        console.log('scan deep=' + deep + ' found=' + r.length +
                            ' anchors=' + document.querySelectorAll('a[href]').length +
                            ' href=' + location.href);
                    }catch(e){}
                    try{
                        if(r.length > 0 && window.Android) window.Android.onLinksFound(JSON.stringify(r));
                    }catch(e){}
                    return r;
                }
                // Install the scroll/mutation hooks once per page load. The
                // explicit Scan button still performs an immediate deep scan.
                if(!window.__spotifyScannerInstalled){
                    window.__spotifyScannerInstalled = true;
                    var timer = null;
                    function schedule(){
                        if(timer) clearTimeout(timer);
                        timer = setTimeout(function(){ push(false); }, 600);
                    }
                    // Capture-phase listeners also catch scrolls of inner
                    // containers (sites with body{overflow:hidden}).
                    try{ window.addEventListener('scroll', schedule, {passive:true, capture:true}); }catch(e){}
                    try{ document.addEventListener('scroll', schedule, {passive:true, capture:true}); }catch(e){}
                    try{
                        // attributeFilter matters: rateyourmusic.com sets the
                        // href on existing anchors when media links load.
                        var obs = new MutationObserver(schedule);
                        obs.observe(document.documentElement, {
                            childList:true, subtree:true,
                            attributes:true, attributeFilter:['href','src','data-uri','data-spotify-uri']
                        });
                    }catch(e){}
                    // Safety net: cheap light scan, so lazily rendered links
                    // are picked up even if no scroll/mutation event fires.
                    try{ setInterval(function(){ push(false); }, 1500); }catch(e){}
                    setTimeout(function(){ push(true); }, 1500);
                }
                window.__spotifyPush = push;
                return push(true);
            })()
        """.trimIndent()
        view.evaluateJavascript(js, ValueCallback { value ->
            val ids = parseIds(value)
            if (ids.isNotEmpty()) mergeLinks(ids)
        })
    }

    /**
     * "Scan" also walks the whole page: it steps the scrolling container down
     * viewport by viewport so lazy content (rateyourmusic.com media links)
     * materialises, collecting links at every step, then returns to the
     * starting position. No-op if a sweep is already running.
     */
    fun sweepPage(view: WebView) {
        val js = """
            (function(){
                if(window.__spotifySweeping) return 'busy';
                var push = window.__spotifyPush;
                if(typeof push !== 'function') return 'no-scanner';
                window.__spotifySweeping = true;
                var cands = [document.scrollingElement, document.documentElement, document.body];
                try{
                    var all = document.querySelectorAll('div,main,section,article');
                    for(var i=0;i<all.length;i++) cands.push(all[i]);
                }catch(e){}
                var el = null, best = 40;
                for(var j=0;j<cands.length;j++){
                    var e = cands[j];
                    if(!e) continue;
                    try{
                        var d = e.scrollHeight - e.clientHeight;
                        if(e.clientHeight >= 200 && d > best){ best = d; el = e; }
                    }catch(e2){}
                }
                function notify(kind, found, step){
                    try{ if(window.Android && window.Android[kind]) window.Android[kind](found, step); }catch(e){}
                }
                function finish(reason){
                    window.__spotifySweeping = false;
                    try{ if(el) el.scrollTop = startTop; }catch(e){}
                    var r = push(true);
                    notify('onSweepDone', r.length, steps);
                }
                var startTop = el ? el.scrollTop : 0;
                var steps = 0, lastFound = 0, unchanged = 0;
                var step = Math.max(300, Math.round((window.innerHeight || 800) * 0.8));
                function tick(){
                    if(!el){ finish('no-scroll'); return; }
                    var max = el.scrollHeight - el.clientHeight;
                    if(el.scrollTop >= max - 4 || steps >= 200){ finish('end'); return; }
                    steps++;
                    var before = el.scrollHeight;
                    el.scrollTop = Math.min(max, el.scrollTop + step);
                    setTimeout(function(){
                        var r = push(false);
                        if(r.length > lastFound){ lastFound = r.length; unchanged = 0; } else { unchanged++; }
                        notify('onSweepProgress', r.length, steps);
                        // Stop early if the page is fully grown and idle for a while.
                        if(unchanged >= 8 && el.scrollHeight === before){ finish('idle'); return; }
                        tick();
                    }, 320);
                }
                notify('onSweepProgress', 0, 0);
                tick();
                return 'started';
            })()
        """.trimIndent()
        view.evaluateJavascript(js, ValueCallback { value ->
            if (value == null || !value.contains("started")) {
                scanning = false
                scanProgress = "The page is not ready for scanning yet — wait for it to finish loading, then tap Scan."
            }
        })
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Add from site") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("https://open.spotify.com/...") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val url = normalize(urlText)
                        prefs.edit().putString("last_url", url).apply()
                        webView?.loadUrl(url)
                    }) {
                        Text("Go")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(
                        onClick = {
                            val wv = webView ?: return@Button
                            scanning = true
                            scanProgress = "Scanning page…"
                            sweepPage(wv)
                        },
                        enabled = !scanning
                    ) {
                        Text(if (scanning) "Scanning…" else "Scan")
                    }
                }

                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            // Some sites (rateyourmusic.com) treat the default
                            // WebView UA as a bot; use a plain Chrome mobile UA.
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            addJavascriptInterface(object : Any() {
                                @JavascriptInterface
                                fun onLinksFound(json: String) {
                                    val ids = parseIds(json)
                                    if (ids.isNotEmpty()) {
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            mergeLinks(ids)
                                        }
                                    }
                                }

                                @JavascriptInterface
                                fun onSweepProgress(found: Int, step: Int) {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        scanProgress = if (step <= 0) "Scanning page…"
                                        else "Scanning page… $found album links found (step $step)"
                                    }
                                }

                                @JavascriptInterface
                                fun onSweepDone(found: Int, step: Int) {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        scanning = false
                                        scanProgress = "Scan finished — $found album links found"
                                    }
                                }
                            }, "Android")
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                                    android.util.Log.d(
                                        "AddFromSite",
                                        "${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                                    )
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    urlText = url
                                    detected.clear()
                                    selected.clear()
                                    pageError = null
                                    scanning = false
                                    scanProgress = null
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    urlText = url
                                    prefs.edit().putString("last_url", url).apply()
                                    extractLinks(view)
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: android.webkit.WebResourceRequest,
                                    errorResponse: android.webkit.WebResourceResponse
                                ) {
                                    super.onReceivedHttpError(view, request, errorResponse)
                                    if (request.isForMainFrame) {
                                        pageError = "The page returned HTTP ${errorResponse.statusCode}. " +
                                            "The site may be blocking the in-app browser — wait a moment and tap Go again."
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: android.webkit.WebResourceRequest,
                                    error: android.webkit.WebResourceError
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request.isForMainFrame) {
                                        pageError = "Couldn't load the page (${error.errorCode} ${error.description}). " +
                                            "If the site is blocking the in-app browser, wait a moment and tap Go again."
                                    }
                                }
                            }
                            webView = this
                            loadUrl(normalize(urlText))
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                Surface(color = Color.Black, tonalElevation = 4.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        val progress = scanProgress
                        if (progress != null) {
                            Text(
                                progress,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1DB954)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        val error = pageError
                        if (error != null) {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF6B6B)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (detected.isEmpty()) {
                            Text(
                                "No Spotify album links detected yet. Scroll the page (some sites load links lazily) or tap Scan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color(0xFFB3B3B3)
                            )
                        } else {
                            Text(
                                "Detected ${detected.size} Spotify album${if (detected.size == 1) "" else "s"}" +
                                    " — scroll the page to load more",
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                                items(detected) { id ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = selected[id] == true,
                                            onCheckedChange = { selected[id] = it }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            val label = albumLabels[id]
                                            Text(
                                                text = label ?: id,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (label != null) {
                                                Text(
                                                    id,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = androidx.compose.ui.graphics.Color(0xFF696969),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            val selectedIds = detected.filter { selected[it] == true }
                            Button(
                                onClick = { onAdd(selectedIds) },
                                enabled = selectedIds.isNotEmpty() && !adding,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = androidx.compose.ui.graphics.Color(0xFF1DB954),
                                    contentColor = Color.Black
                                )
                            ) {
                                if (adding) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.Black
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Adding…")
                                } else {
                                    Text("Add selected (${selectedIds.size})")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}