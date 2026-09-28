package com.wenfou.app

import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Paper = Color(0xFFF7F5F0)
private val Ink = Color(0xFF233C34)
private val Coral = Color(0xFFE86147)
private val AccentText = Color(0xFFB9422B)
private val Sage = Color(0xFFE4EBDF)
private val Muted = Color(0xFF626D64)
private val Line = Color(0xFFE4E5DC)
private val SoftCoral = Color(0xFFFAE9E2)
private val White = Color(0xFFFFFEFB)
private val Headline = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp, color = Ink)
private val Body = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = Ink)
private val Caption = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = Muted)
private val CardShape = RoundedCornerShape(24.dp)

private data class QuestionCard(val id: Int, val question: String, val answer: String, val category: String, val author: String, val note: String)
private val samplePosts = listOf(
    QuestionCard(1, "如果不必证明自己，\n你还会做什么？", "也许可以先把『我应该』暂时放下，写下三件即使没有人看见，你也愿意持续去做的小事。\n\n答案不必宏大。那些让你忘记比较的时刻，往往藏着更接近自己的选择。", "决策", "小满", "给最近总在比较的自己，留一点思考的空间。"),
    QuestionCard(2, "为什么越想用 AI 提高效率，反而越忙？", "工具减少了单次操作时间，却也可能让我们接下更多任务。先决定哪些事情值得做，再决定怎样做得更快。", "方法", "阿木", "效率之前，先给自己的注意力排个序。"),
    QuestionCard(3, "如果记忆有颜色，星期一会是什么颜色？", "或许是一种还没调匀的蓝：有一点困倦，也有一点刚打开的可能。你可以给每一天留一个属于自己的色块。", "脑洞", "半颗橙", "一个没有标准答案、但让今天变有趣的问题。"),
    QuestionCard(4, "想换一种生活，应该先做哪一个小决定？", "先挑一个可逆、低成本、能在一周内完成的尝试。体验会提供新的信息，让下一个决定更具体。", "决策", "林间", "先试一小步，再决定要不要走远。"),
)

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<IncomingImport?>(null)
    private var resumed = false

    private fun receiveSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isNotBlank()) incoming = IncomingImport(text, false)
        intent.removeExtra(Intent.EXTRA_TEXT)
    }
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedText(intent)
    }
    override fun onResume() {
        super.onResume(); resumed = true
        window.decorView.post { if (hasWindowFocus()) inspectClipboard() }
    }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) inspectClipboard()
    }
    private fun inspectClipboard() {
        if (!resumed || incoming != null) return
        val prefs = getSharedPreferences("import_preferences", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("automatic_clipboard", true)) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // No URI coercion, background listener, or storage of clipboard contents.
        val text = runCatching {
            if (clipboard.primaryClipDescription?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }.getOrNull() ?: return
        if (!ShareImport.canSuggest(text)) return
        val hash = ShareImport.fingerprint(text)
        val seen = prefs.getString("seen_hashes", "").orEmpty().split(',').filter { it.isNotEmpty() }
        if (hash in seen) return
        prefs.edit().putString("seen_hashes", (seen.takeLast(31) + hash).joinToString(",")).apply()
        incoming = IncomingImport(text, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveSharedText(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = AccentText, onPrimary = White, background = Paper, onBackground = Ink, surface = White, onSurface = Ink, secondary = Ink, secondaryContainer = Sage, onSecondaryContainer = Ink, primaryContainer = SoftCoral, onPrimaryContainer = AccentText, onSurfaceVariant = Muted, surfaceVariant = Sage, outline = Line, outlineVariant = Line)) {
                WenfouApp(incoming) { incoming = null }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WenfouApp(incoming: IncomingImport?, onIncomingConsumed: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf("精选") }
    var question by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var shortExcerpt by rememberSaveable { mutableStateOf(false) }
    var savedIds by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var detailId by rememberSaveable { mutableStateOf<Int?>(null) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var collection by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var importRaw by rememberSaveable { mutableStateOf<String?>(null) }
    var importAuto by rememberSaveable { mutableStateOf(false) }
    var sourcePlatform by rememberSaveable { mutableStateOf("") }
    var sourceUrl by rememberSaveable { mutableStateOf("") }
    var replacement by remember { mutableStateOf<ImportedDraft?>(null) }
    val sourceLabel = if (sourcePlatform.isBlank()) "示例内容 · 非真实模型生成" else "外部导入 · $sourcePlatform（经用户编辑确认）"
    val context = LocalContext.current
    fun useImport(draft: ImportedDraft) {
        question = draft.question; answer = draft.answer
        sourcePlatform = draft.platform; sourceUrl = draft.sourceUrl
        note = ""; shortExcerpt = false; importRaw = null; replacement = null; tab = 2; preview = true
    }
    fun save(id: Int) { savedIds = if (id in savedIds) savedIds - id else savedIds + id }
    fun editQuestion(value: String) {
        question = value.take(10000)
        sourcePlatform = ""; sourceUrl = ""
        answer = ""
        preview = false
        shortExcerpt = false
    }
    fun loadSample() {
        sourcePlatform = ""; sourceUrl = ""
        question = samplePosts[0].question.replace("\n", "")
        answer = samplePosts[0].answer
        shortExcerpt = false
    }
    val excerpt = if (shortExcerpt) firstSentence(answer) else answer

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = Paper,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { BrandHeader(tab) { importAuto = false; importRaw = "" } },
        bottomBar = { BottomNavigation(tab) { tab = it } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> DiscoverScreen(category, { category = it }, savedIds, { save(it) }, { detailId = it }, { tab = 1 })
                1 -> AskScreen(question, { editQuestion(it) }, answer, {
                    sourcePlatform = ""; sourceUrl = ""
                    // Explicitly marked local sample; no network/model call is made.
                    answer = "试着先把问题拆成三个部分：你希望改变什么、目前有哪些限制、最小的一步是什么。\n\n写下一个这周能尝试的小行动，再用实际体验检查自己的判断。好的问题，可以先带来一个具体的开始。"
                }, { tab = 2 }, sourceLabel)
                2 -> ShareScreen(question, answer, note, { note = it.take(240) }, shortExcerpt, { shortExcerpt = it }, { preview = true }, { loadSample() }, { tab = 1 }, sourceLabel, { importAuto = false; importRaw = "" })
                else -> ProfileScreen(savedIds.size, question.isNotBlank(), answer.isNotBlank(), { collection = true }, { tab = 1 }, { tab = 2 }, { about = true })
            }
        }
    }
    if (incoming != null) AlertDialog(
        onDismissRequest = onIncomingConsumed,
        title = { Text(if (incoming.fromClipboard) "发现剪贴板内容" else "收到分享内容") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(ShareImport.link(incoming.text)?.let { "发现 ${it.platform} 分享链接，是否解析问答并准备发布？" } ?: "是否将这段复制文字整理成问答？")
            Text(incoming.text.take(150), maxLines = 4, overflow = TextOverflow.Ellipsis, style = Caption)
            Text("解析后可编辑核对，不会自动公开。", style = Caption)
        } },
        confirmButton = { TextButton(onClick = { importRaw = incoming.text; importAuto = true; onIncomingConsumed() }) { Text("解析并预览") } },
        dismissButton = { TextButton(onClick = onIncomingConsumed) { Text("暂不导入") } }
    )
    importRaw?.let { raw ->
        ImportDialog(raw, importAuto, onDismiss = { importRaw = null; replacement = null }, onUse = { draft ->
            if (question.isNotBlank() || answer.isNotBlank() || note.isNotBlank()) replacement = draft else useImport(draft)
        })
    }
    replacement?.let { draft -> AlertDialog(
        onDismissRequest = { replacement = null },
        title = { Text("替换当前分享草稿？") },
        text = { Text("现有问题、回答和分享理由会被这次导入的内容替换。") },
        confirmButton = { TextButton(onClick = { useImport(draft) }) { Text("替换草稿") } },
        dismissButton = { TextButton(onClick = { replacement = null }) { Text("保留原草稿") } }
    ) }
    detailId?.let { id ->
        val post = samplePosts.first { it.id == id }
        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { detailId = null }, containerColor = Paper) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Eyebrow("${post.category} / 示例问答", AccentText)
                Text(post.question, style = Headline)
                AnswerBlock(post.answer)
                Text("分享者的话", style = Caption)
                Text(post.note, style = Body)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { save(id) }, shape = CircleShape, modifier = Modifier.heightIn(min = 52.dp)) { Text(if (id in savedIds) "已收藏" else "收藏") }
                    PrimaryButton("带着这个问题继续", Modifier.weight(1f)) {
                        editQuestion(post.question.replace("\n", "")); detailId = null; tab = 1
                    }
                }
            }
        }
    }
    if (preview && answer.isNotBlank()) {
        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { preview = false }, containerColor = Paper) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("分享预览", style = Headline)
                Surface(shape = CardShape, color = White, border = BorderStroke(1.dp, Line)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { BrandMark(28); BrandWordmark(64) }
                        Text(question, style = Headline.copy(fontSize = 23.sp, lineHeight = 32.sp))
                        Text(excerpt, style = Body)
                        if (note.isNotBlank()) { HorizontalDivider(color = Line); Text("我的分享理由", style = Caption); Text(note, style = Body) }
                        Text("问否 · $sourceLabel", style = Caption)
                        if (sourceUrl.isNotBlank()) Text(sourceUrl, style = Caption)
                    }
                }
                PrimaryButton("分享文字", Modifier.fillMaxWidth()) {
                    val text = "问否 · 一个值得分享的问题\n\n$question\n\n$excerpt" +
                        (if (note.isBlank()) "" else "\n\n我的分享理由：$note") + "\n\n【$sourceLabel】" + (if (sourceUrl.isBlank()) "" else "\n来源：$sourceUrl")
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "分享问答"))
                }
                Text("当前仅预览和分享文字，不会发布到社区。", style = Caption)
            }
        }
    }
    if (collection) {
        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { collection = false }, containerColor = Paper) {
            LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("我的收藏", style = Headline) }
                if (savedIds.isEmpty()) item { Text("遇到想再读一遍的回答，点一下书签，留在这里。", style = Body) }
                items(samplePosts.filter { it.id in savedIds }) { post ->
                    PostCard(post, true, { save(post.id) }, { collection = false; detailId = post.id })
                }
            }
        }
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, containerColor = Paper, title = { Text("好问题，值得被看见。", style = Headline.copy(fontSize = 24.sp)) }, text = { Text("问否 0.3.0\n\n人与 AI 的问答分享空间。\n\n这是本地体验版：支持外部问答导入；App 内生成的回答为示例。收藏和草稿仅保留在当前会话中，尚未接入登录与社区发布服务。", style = Body) }, confirmButton = { TextButton(onClick = { about = false }) { Text("知道了") } })
}

@Composable
private fun BrandHeader(tab: Int, onImport: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(34)
        Spacer(Modifier.width(9.dp))
        BrandWordmark(76)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onImport) { Text("导入", color = AccentText) }

    }
}

@Composable
private fun BottomNavigation(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = White, shadowElevation = 10.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            listOf("发现" to "discover", "提问" to "ask", "分享" to "share", "我的" to "person").forEachIndexed { index, pair ->
                val active = selected == index
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(index) }.padding(vertical = 5.dp).semantics { contentDescription = pair.first }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(48.dp, 29.dp).background(if (active) SoftCoral else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) { LineIcon(pair.second, if (active) Coral else Muted, Modifier.size(23.dp)) }
                    Text(pair.first, style = Caption.copy(color = if (active) AccentText else Muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal))
                }
            }
        }
    }
}

@Composable
private fun DiscoverScreen(category: String, onCategory: (String) -> Unit, saved: List<Int>, onSave: (Int) -> Unit, onOpen: (Int) -> Unit, onAsk: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("人与 AI 的问答分享空间", Muted)
                Text("好问题，\n值得被看见。", style = Headline.copy(fontSize = 32.sp, lineHeight = 43.sp))
                Text("从别人的提问里，发现自己的新可能。", style = Caption.copy(fontSize = 13.sp))
            }
        }
        item {
            Surface(onClick = { onOpen(1) }, shape = CardShape, color = Ink) {
                Box {
                    Canvas(Modifier.align(Alignment.TopEnd).size(96.dp).padding(16.dp)) {
                        val c = size.width / 2
                        val r = size.width * .42f
                        for (i in 0..7) {
                            val a = i * Math.PI / 4
                            drawLine(Sage.copy(alpha = .25f), Offset(c + kotlin.math.cos(a).toFloat() * r * .42f, c + kotlin.math.sin(a).toFloat() * r * .42f), Offset(c + kotlin.math.cos(a).toFloat() * r, c + kotlin.math.sin(a).toFloat() * r), 3.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Eyebrow("今日好问  /  01", Color(0xFFC5D7C3))
                        Text("如果不必证明自己，\n你还会做什么？", style = Headline.copy(fontSize = 24.sp, lineHeight = 34.sp, color = White))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("留一点时间，问问自己", style = Caption.copy(color = Color(0xFFBBCBBF)), modifier = Modifier.weight(1f))
                            Box(Modifier.size(38.dp).background(Coral, CircleShape), contentAlignment = Alignment.Center) { LineIcon("arrow", White, Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("精选", "脑洞", "方法", "决策").forEach { name ->
                    Surface(onClick = { onCategory(name) }, shape = CircleShape, color = if (category == name) Ink else Color.Transparent, border = if (category == name) null else BorderStroke(1.dp, Line)) {
                        Text(name, Modifier.padding(horizontal = 21.dp, vertical = 12.dp), style = Body.copy(fontSize = 14.sp, color = if (category == name) White else Muted, fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("值得一读", style = Headline.copy(fontSize = 20.sp, lineHeight = 26.sp), modifier = Modifier.weight(1f)); Text("示例内容", style = Caption) } }
        items(if (category == "精选") samplePosts.drop(1) else samplePosts.filter { it.category == category }) { post -> PostCard(post, post.id in saved, { onSave(post.id) }, { onOpen(post.id) }) }
        item {
            Row(Modifier.fillMaxWidth().clip(CardShape).background(Sage).clickable(onClick = onAsk).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("你的问题，也值得一个位置。", style = Body.copy(fontWeight = FontWeight.Bold)); Text("从一个小小的好奇开始", style = Caption) }
                LineIcon("arrow", Ink, Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun PostCard(post: QuestionCard, saved: Boolean, onSave: () -> Unit, onOpen: () -> Unit) {
    Surface(onClick = onOpen, shape = CardShape, color = White, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(29.dp).background(if (post.id % 2 == 0) SoftCoral else Sage, CircleShape), contentAlignment = Alignment.Center) { Text(post.author.take(1), style = Caption.copy(color = Ink, fontWeight = FontWeight.Bold)) }
                Text(post.author, Modifier.padding(start = 8.dp).weight(1f), style = Caption.copy(color = Ink))
                Text(post.category, style = Caption.copy(color = AccentText))
            }
            Text(post.question.replace("\n", ""), style = Headline.copy(fontSize = 21.sp, lineHeight = 30.sp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("答", style = Caption.copy(color = AccentText, fontWeight = FontWeight.Bold))
                Text(post.answer, style = Body.copy(fontSize = 14.sp, lineHeight = 22.sp, color = Muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("问答样例 · 非真实模型生成", style = Caption.copy(fontSize = 10.sp), modifier = Modifier.weight(1f))
                IconButton(onClick = onSave, modifier = Modifier.size(48.dp).semantics { contentDescription = if (saved) "取消收藏" else "收藏问答" }) { LineIcon(if (saved) "saved" else "bookmark", if (saved) Coral else Muted, Modifier.size(20.dp)) }
                LineIcon("arrow", Muted, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AskScreen(question: String, onChange: (String) -> Unit, answer: String, onAsk: () -> Unit, onShare: () -> Unit, sourceLabel: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Eyebrow("每个好答案，都从好奇开始", AccentText)
        Text("把困惑，\n问成新的可能。", style = Headline.copy(fontSize = 32.sp, lineHeight = 43.sp))
        Text("先自由地问。想分享时，再选择公开的片段。", style = Body.copy(fontSize = 14.sp, color = Muted))
        Surface(shape = CardShape, color = White, border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) { Box(Modifier.size(6.dp).background(Coral, CircleShape)); Text(sourceLabel, style = Caption) }
                TextField(value = question, onValueChange = onChange, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "我的问题" }, placeholder = { Text("此刻，有什么想问的？\n\n一个困惑、一个脑洞，\n或一个迟迟没做的决定。", style = Body.copy(color = Muted)) }, minLines = 5, maxLines = 9, textStyle = Body.copy(fontSize = 17.sp, lineHeight = 27.sp), colors = TextFieldDefaults.colors(focusedContainerColor = White, unfocusedContainerColor = White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { LineIcon("lock", Muted, Modifier.size(14.dp)); Text(" 仅自己可见", style = Caption, modifier = Modifier.weight(1f)); Text("${question.length}/10000", style = Caption) }
                PrimaryButton(if (answer.isBlank()) "看看示例回答" else "重新查看示例", Modifier.fillMaxWidth(), question.isNotBlank(), onAsk)
            }
        }
        if (answer.isBlank()) {
            Text("还没想好？从这里开始", style = Caption)
            listOf("怎样把一个模糊的问题问清楚？", "如果记忆有颜色，会是什么颜色？").forEach { prompt ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sage.copy(alpha = .65f)).clickable { onChange(prompt) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(prompt, style = Body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f)); LineIcon("arrow", Muted, Modifier.size(18.dp)) }
            }
        } else {
            AnswerBlock(answer, sourceLabel)
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CircleShape, border = BorderStroke(1.dp, Ink)) { LineIcon("share", Ink, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("整理成分享", color = Ink) }
        }
    }
}

@Composable
private fun ShareScreen(question: String, answer: String, note: String, onNote: (String) -> Unit, short: Boolean, onShort: (Boolean) -> Unit, onPreview: () -> Unit, onSample: () -> Unit, onAsk: () -> Unit, sourceLabel: String, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Eyebrow("把有启发的一刻，留给更多人", AccentText)
        Text("分享一个\n值得停留的答案。", style = Headline.copy(fontSize = 30.sp, lineHeight = 41.sp))
        Surface(shape = RoundedCornerShape(20.dp), color = Sage, onClick = onImport) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("导入 AI 对话  ↗", style = Body.copy(fontWeight = FontWeight.Bold))
                Text("DeepSeek · ChatGPT · 豆包 · Kimi\n粘贴分享链接，或复制的问答文字", style = Caption)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("01  选片段", "02  写理由", "03  看预览").forEach { Text(it, style = Caption.copy(color = Ink, fontWeight = FontWeight.Medium)) } }
        if (answer.isBlank()) {
            Surface(shape = CardShape, color = Sage) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    BrandMark(74)
                    Text("每一次分享，\n都从一个好问题开始。", style = Headline.copy(fontSize = 23.sp, lineHeight = 33.sp))
                    Text("先去提问，再把想留下的片段整理成卡片。", style = Body.copy(fontSize = 14.sp, color = Muted))
                    PrimaryButton("去问一个问题", Modifier.fillMaxWidth(), onClick = onAsk)
                    TextButton(onClick = onSample) { Text("用一条示例试试", color = Ink) }
                }
            }
        } else {
            Surface(shape = CardShape, color = White, border = BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Eyebrow("将分享的内容", Muted)
                    Text(question, style = Headline.copy(fontSize = 22.sp, lineHeight = 31.sp))
                    HorizontalDivider(color = Line)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(shape = CircleShape, selected = !short, onClick = { onShort(false) }, label = { Text("完整回答") })
                        FilterChip(shape = CircleShape, selected = short, onClick = { onShort(true) }, label = { Text("只选第一句") })
                    }
                    Text(if (short) firstSentence(answer) else answer, style = Body)
                    Text(sourceLabel, style = Caption)
                }
            }
            Text("你为什么想分享？", style = Headline.copy(fontSize = 20.sp, lineHeight = 27.sp))
            OutlinedTextField(value = note, onValueChange = onNote, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), placeholder = { Text("打动你的地方，或一个不同的看法……", style = Body.copy(color = Muted)) }, minLines = 3, maxLines = 6, textStyle = Body, supportingText = { Text("${note.length}/240 · 可选") })
            PrimaryButton("预览分享卡片", Modifier.fillMaxWidth(), onClick = onPreview)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { LineIcon("lock", Muted, Modifier.size(15.dp)); Text("只展示你选中的内容。当前体验版不会发布到社区。", style = Caption) }
        }
    }
}

@Composable
private fun ProfileScreen(savedCount: Int, hasQuestion: Boolean, hasDraft: Boolean, onSaved: () -> Unit, onQuestions: () -> Unit, onDrafts: () -> Unit, onAbout: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 18.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(70.dp).background(Sage, RoundedCornerShape(25.dp)), contentAlignment = Alignment.Center) { LineIcon("person", Ink, Modifier.size(34.dp)) }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("好奇心收藏家", style = Headline.copy(fontSize = 25.sp)); Text("在这里，积累自己的思考。", style = Caption) }
        }
        Surface(shape = CardShape, color = Ink) {
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                listOf(Triple(savedCount, "收藏", onSaved), Triple(if (hasQuestion) 1 else 0, "本次提问", onQuestions), Triple(if (hasDraft) 1 else 0, "分享草稿", onDrafts)).forEach { (count, title, action) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = action).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(count.toString().padStart(2, '0'), style = Headline.copy(fontSize = 30.sp, color = White)); Text(title, style = Caption.copy(color = Color(0xFFC5D7C3))) }
                }
            }
        }
        Text("我的思考空间", style = Headline.copy(fontSize = 21.sp, lineHeight = 28.sp))
        Surface(shape = CardShape, color = White, border = BorderStroke(1.dp, Line)) {
            Column {
                ProfileRow("bookmark", "我的收藏", "想再读一次的问答", onSaved)
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Line)
                ProfileRow("ask", "我的提问", "从上次的好奇继续", onQuestions)
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Line)
                ProfileRow("share", "分享草稿", "还没说完的想法", onDrafts)
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onAbout).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("关于问否", style = Body, modifier = Modifier.weight(1f)); Text("v0.3.0", style = Caption); Spacer(Modifier.width(12.dp)); LineIcon("arrow", Muted, Modifier.size(18.dp)) }
        Text("保持好奇。\n下一个好问题，就在生活里。", style = Headline.copy(fontSize = 22.sp, lineHeight = 34.sp, color = Muted))
        Text("本地体验版 · 收藏和草稿仅在当前会话中保留", style = Caption.copy(fontSize = 11.sp))
    }
}

@Composable
private fun ProfileRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LineIcon(icon, Ink, Modifier.size(23.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = Body.copy(fontWeight = FontWeight.Medium)); Text(subtitle, style = Caption) }
        LineIcon("arrow", Muted, Modifier.size(18.dp))
    }
}

@Composable
private fun AnswerBlock(answer: String, sourceLabel: String = "示例回答 · 非真实模型生成") {
    Surface(shape = CardShape, color = Sage) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { BrandMark(25); Text("一个思考角度", style = Body.copy(fontWeight = FontWeight.Bold)) }
            Text(answer, style = Body.copy(lineHeight = 27.sp))
            Text(sourceLabel, style = Caption.copy(fontSize = 11.sp))
        }
    }
}

@Composable
private fun Eyebrow(text: String, color: Color) { Text(text, style = Caption.copy(color = color, fontWeight = FontWeight.Medium, letterSpacing = .7.sp)) }

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 54.dp), enabled = enabled, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = AccentText, contentColor = White, disabledContainerColor = Line, disabledContentColor = Muted), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
        Text(text, style = Body.copy(color = if (enabled) White else Muted, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(12.dp)); LineIcon("arrow", if (enabled) White else Muted, Modifier.size(19.dp))
    }
}

// Brand artwork uses paths rather than a device font, so the wordmark stays consistent.
@Composable
private fun BrandMark(sizeDp: Int) {
    Canvas(Modifier.size(sizeDp.dp).semantics { contentDescription = "问否标志" }) {
        val s = size.width / 100
        scale(s, s, pivot = Offset.Zero) {
            drawRoundRect(Coral, size = Size(100f, 100f), cornerRadius = CornerRadius(27f))
            val bubble = Path().apply { moveTo(29f, 27f); lineTo(70f, 27f); quadraticTo(78f, 27f, 78f, 35f); lineTo(78f, 62f); quadraticTo(78f, 70f, 70f, 70f); lineTo(50f, 70f); lineTo(33f, 82f); lineTo(33f, 70f); lineTo(29f, 70f); quadraticTo(22f, 70f, 22f, 62f); lineTo(22f, 43f) }
            drawPath(bubble, White, style = Stroke(6f, cap = StrokeCap.Round))
            val question = Path().apply { moveTo(44f, 40f); cubicTo(43f, 30f, 64f, 32f, 62f, 43f); cubicTo(61f, 48f, 52f, 46f, 52f, 52f) }
            drawPath(question, White, style = Stroke(5.5f, cap = StrokeCap.Round))
            drawCircle(White, 3f, Offset(52f, 61f))
        }
    }
}

@Composable
private fun BrandWordmark(widthDp: Int) {
    Canvas(Modifier.size(widthDp.dp, (widthDp * .48f).dp).semantics { contentDescription = "问否" }) {
        val s = size.width / 106f
        scale(s, s, pivot = Offset.Zero) {
            fun line(x: Float, y: Float, xx: Float, yy: Float, color: Color = Ink) = drawLine(color, Offset(x, y), Offset(xx, yy), 4.4f, StrokeCap.Round)
            line(5f, 17f, 5f, 45f); line(7f, 5f, 13f, 11f, Coral)
            val gate = Path().apply { moveTo(23f, 7f); lineTo(43f, 7f); lineTo(43f, 44f); quadraticTo(43f, 47f, 36f, 46f) }
            drawPath(gate, Ink, style = Stroke(4.4f, cap = StrokeCap.Round))
            drawRoundRect(Ink, Offset(16f, 23f), Size(16f, 14f), CornerRadius(2f), style = Stroke(4f))
            line(62f, 7f, 100f, 7f); line(82f, 9f, 63f, 25f); line(81f, 17f, 81f, 27f); line(89f, 16f, 101f, 24f)
            drawRoundRect(Ink, Offset(67f, 34f), Size(29f, 13f), CornerRadius(2f), style = Stroke(4.4f))
        }
    }
}

@Composable
private fun LineIcon(name: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.width / 24
        scale(s, s, pivot = Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x, y), Offset(xx, yy), 1.7f, StrokeCap.Round)
            when (name) {
                "arrow" -> { line(5f, 12f, 19f, 12f); line(13f, 6f, 19f, 12f); line(13f, 18f, 19f, 12f) }
                "discover" -> { drawCircle(color, 9f, Offset(12f, 12f), style = stroke); drawPath(Path().apply { moveTo(15.5f, 8.5f); lineTo(13.7f, 13.7f); lineTo(8.5f, 15.5f); lineTo(10.3f, 10.3f); close() }, color, style = stroke) }
                "ask" -> { drawRoundRect(color, Offset(3f, 3f), Size(18f, 16f), CornerRadius(5f), style = stroke); line(7f, 19f, 7f, 22f); line(7f, 22f, 12f, 19f); line(8f, 10f, 16f, 10f); line(12f, 6f, 12f, 14f) }
                "share" -> { drawRoundRect(color, Offset(4f, 9f), Size(16f, 12f), CornerRadius(4f), style = stroke); line(12f, 3f, 12f, 14f); line(8f, 7f, 12f, 3f); line(16f, 7f, 12f, 3f) }
                "person" -> { drawCircle(color, 4f, Offset(12f, 7f), style = stroke); drawPath(Path().apply { moveTo(4f, 21f); cubicTo(4f, 12f, 20f, 12f, 20f, 21f) }, color, style = stroke) }
                "bookmark", "saved" -> { val p = Path().apply { moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(12f, 17f); lineTo(6f, 21f); close() }; if (name == "saved") drawPath(p, color) else drawPath(p, color, style = stroke) }
                "lock" -> { drawRoundRect(color, Offset(5f, 10f), Size(14f, 11f), CornerRadius(3f), style = stroke); drawPath(Path().apply { moveTo(8f, 10f); lineTo(8f, 7f); cubicTo(8f, 1f, 16f, 1f, 16f, 7f); lineTo(16f, 10f) }, color, style = stroke); drawCircle(color, 1.4f, Offset(12f, 15f)) }
            }
        }
    }
}

internal fun firstSentence(text: String): String {
    val end = text.indexOfFirst { it in "。！？!?\n" }
    return if (end < 0) text else text.take(end + 1).trimEnd()
}
