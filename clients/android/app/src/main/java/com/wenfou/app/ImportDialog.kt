package com.wenfou.app

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import org.json.JSONTokener

internal data class ImportedDraft(val question: String, val answer: String, val platform: String, val sourceUrl: String)
internal data class IncomingImport(val text: String, val fromClipboard: Boolean)

@Composable
internal fun ImportDialog(initialText: String, autoParse: Boolean, onDismiss: () -> Unit, onUse: (ImportedDraft) -> Unit) {
    val context = LocalContext.current
    var raw by rememberSaveable { mutableStateOf(initialText) }
    var stage by rememberSaveable { mutableStateOf("input") }
    var platform by rememberSaveable { mutableStateOf(ShareImport.platformHint(initialText)) }
    var sourceUrl by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var turns by remember { mutableStateOf(emptyList<ShareImport.Turn>()) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var error by rememberSaveable { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val prefs = remember { context.getSharedPreferences("import_preferences", Context.MODE_PRIVATE) }
    var automatic by remember { mutableStateOf(prefs.getBoolean("automatic_clipboard", true)) }
    fun useTurns(values: List<ShareImport.Turn>) {
        if (values.isEmpty()) { error = "没有找到可导入的问答，请复制正文后再试。"; return }
        turns = values; selected = values.indexOfFirst { it.question.isNotBlank() && it.answer.isNotBlank() }.coerceAtLeast(0); question = values[selected].question; answer = values[selected].answer; error = ""; stage = "review"
    }
    fun parse() {
        error = ""
        if (raw.length > ShareImport.MAX_INPUT) { error = "内容超过 12 万字符，请只复制需要分享的片段。"; return }
        val link = ShareImport.link(raw)
        if (link != null) { platform = link.platform; sourceUrl = link.url; stage = "link" }
        else if (Regex("https?://\\S+").containsMatchIn(raw) && raw.trim().matches(Regex("https?://\\S+"))) {
            error = "请使用公开的对话分享链接，或粘贴问答正文。私人会话地址无法导入。"
        } else if (raw.isBlank()) error = "先粘贴分享链接或复制的内容。"
        else { sourceUrl = ""; platform = ShareImport.platformHint(raw); useTurns(ShareImport.parseText(raw)) }
    }
    LaunchedEffect(Unit) { if (autoParse && stage == "input") parse() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("导入外部问答", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("把值得分享的对话，带回问否。", style = MaterialTheme.typography.titleMedium)
                    when (stage) {
                        "input" -> {
                            Text("支持 DeepSeek、ChatGPT、豆包、Kimi 的公开分享链接，或直接复制的问答文字。")
                            OutlinedTextField(value = raw, onValueChange = { raw = it; error = "" }, label = { Text("分享链接或复制内容") }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 9, shape = RoundedCornerShape(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    raw = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty() }.getOrDefault("")
                                    if (raw.isBlank()) error = "剪贴板中没有可读取的文字，请手动粘贴。"
                                }) { Text("粘贴剪贴板") }
                                Button(onClick = { parse() }, enabled = raw.isNotBlank()) { Text("解析内容") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("返回 App 时识别剪贴板", modifier = Modifier.weight(1f).padding(top = 12.dp))
                                Switch(checked = automatic, onCheckedChange = { automatic = it; prefs.edit().putBoolean("automatic_clipboard", it).apply() })
                            }
                            Text("仅在问否回到前台时检查。同一内容只提醒一次；粘贴文本在本机解析，链接在确认后访问来源网页。", style = MaterialTheme.typography.bodySmall)
                        }
                        "link" -> {
                            Text("来源：$platform", style = MaterialTheme.typography.titleMedium)
                            Text(sourceUrl, style = MaterialTheme.typography.bodySmall)
                            Text("读取公开分享页。若出现登录或验证提示，请改用复制文字。长对话可能只加载部分轮次，请核对原文。", style = MaterialTheme.typography.bodySmall)
                            key(sourceUrl, retry) {
                                PublicSharePage(ShareImport.Link(platform, sourceUrl), onResult = { useTurns(it) }, onError = { error = it })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { retry++; error = "" }) { Text("重新读取") }
                                TextButton(onClick = { stage = "input"; raw = ""; sourceUrl = ""; error = "" }) { Text("改用复制文字") }
                            }
                        }
                        "review" -> {
                            Text("解析完成 · 请核对后再发布", style = MaterialTheme.typography.titleMedium)
                            if (turns.size > 1) {
                                Text("识别到 ${turns.size} 轮，选择一轮发布。切换轮次会重置本页编辑。", style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    turns.forEachIndexed { index, turn ->
                                        FilterChip(selected = selected == index, onClick = { selected = index; question = turn.question; answer = turn.answer }, label = { Text("第 ${index + 1} 轮") })
                                    }
                                }
                            }
                            if (sourceUrl.isNotBlank()) { Text("来源：$platform"); Text(sourceUrl, style = MaterialTheme.typography.bodySmall) }
                            else {
                                Text("确认来源平台（复制文本无法可靠判断来源）", style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ShareImport.platforms.forEach { name -> FilterChip(selected = platform == name, onClick = { platform = name }, label = { Text(name) }) }
                                }
                            }
                            if (question.isBlank()) Text("这段内容没有提问，请补充原问题。", color = MaterialTheme.colorScheme.primary)
                            OutlinedTextField(value = question, onValueChange = { question = it }, label = { Text("导入的问题") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), supportingText = { Text("${question.length}/10000") })
                            OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("导入的回答") }, minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), supportingText = { Text("${answer.length}/100000") })
                            Text("请确认这些内容可以公开，并移除个人信息。当前版本会进入发布预览，尚未连接社区发布服务。", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { onUse(ImportedDraft(question.trim(), answer.trim(), platform, sourceUrl)) }, enabled = question.isNotBlank() && answer.isNotBlank() && question.length <= 10000 && answer.length <= 100000, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("确认，进入发布预览") }
                            TextButton(onClick = { stage = "input"; error = "" }) { Text("返回修改原始内容") }
                        }
                    }
                    if (error.isNotBlank()) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) { Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PublicSharePage(link: ShareImport.Link, onResult: (List<ShareImport.Turn>) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val currentResult by rememberUpdatedState(onResult)
    val currentError by rememberUpdatedState(onError)
    val handler = remember { Handler(Looper.getMainLooper()) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var active by remember { mutableStateOf(true) }
    var completed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var attempts by remember { mutableIntStateOf(0) }
    var previous by remember { mutableStateOf("") }
    val script = remember { context.assets.open("share-extractor.js").bufferedReader().use { it.readText() } }
    fun fail(message: String) { if (active && !completed) { completed = true; loading = false; currentError(message) } }
    val poll = remember {
        object : Runnable {
            override fun run() {
                val web = view ?: return
                if (!active || completed) return
                if (++attempts > 40) { fail("暂未解析出正式问答。可能是网络、登录限制、页面改版或已失效。可在原产品复制文字后导入。"); return }
                if (!ShareImport.safeNavigation(web.url.orEmpty(), link.platform)) { handler.postDelayed(this, 1000); return }
                web.evaluateJavascript(script) { value ->
                    if (!active || completed) return@evaluateJavascript
                    runCatching {
                        val decoded = JSONTokener(value).nextValue() as? String ?: return@runCatching
                        val data = JSONObject(decoded)
                        if (data.optBoolean("oversized")) { fail("对话过长，请复制需要发布的一轮问答后导入。"); return@runCatching }
                        val array = data.getJSONArray("messages")
                        val messages = (0 until array.length()).map { array.getJSONObject(it) }.map { ShareImport.Message(it.getString("role"), it.getString("text")) }
                        if (messages.any { it.role == "assistant" } && previous == decoded) {
                            completed = true; loading = false; currentResult(ShareImport.pair(messages))
                        }
                        previous = decoded
                    }
                    if (active && !completed) handler.postDelayed(this, 1000)
                }
            }
        }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        androidx.compose.ui.viewinterop.AndroidView(modifier = Modifier.fillMaxWidth().height(300.dp), factory = {
            WebView(it).apply {
                view = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val allowed = ShareImport.safeNavigation(request.url.toString(), link.platform)
                        if (!allowed && request.isForMainFrame) fail("此链接跳转到了非公开分享页，请改用复制文字导入。")
                        return !allowed
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) fail("分享页加载失败，请检查网络或改用复制文字。")
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) fail("分享页无法访问（${errorResponse.statusCode}），请改用复制文字。")
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        fail("网页加载进程已退出，请关闭导入页后重试。"); return true
                    }
                }
                loadUrl(link.url)
                handler.postDelayed(poll, 1000)
            }
        })
    }
    DisposableEffect(Unit) {
        onDispose { active = false; handler.removeCallbacks(poll); view?.apply { stopLoading(); destroy() }; view = null }
    }
}
