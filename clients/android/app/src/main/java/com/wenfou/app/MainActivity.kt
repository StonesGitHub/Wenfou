package com.wenfou.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Local UI prototype. Replace sample content with the authenticated API repository.
private data class QuestionCard(
    val question: String,
    val excerpt: String,
    val category: String,
)

private val samplePosts = listOf(
    QuestionCard(
        "为什么越想用 AI 提高效率，反而越忙？",
        "工具减少了单次操作时间，但也可能增加要处理的任务数量。",
        "讨论",
    ),
    QuestionCard(
        "怎样把一个模糊问题问得更清楚？",
        "先说明目标、已有信息和希望答案帮助你做什么决定。",
        "方法",
    ),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { WenfouApp() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WenfouApp() {
    var tab by rememberSaveable { mutableStateOf(0) }
    var question by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var preview by rememberSaveable { mutableStateOf(false) }
    val tabs = listOf("发现", "提问", "发布", "我的")

    Scaffold(
        topBar = { TopAppBar(title = { Text("问否") }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(if (tab == index) "●" else "○") },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            0 -> DiscoverScreen(padding)
            1 -> AskScreen(padding, question, { question = it }, answer, {
                // Only a local demonstration; do not represent this as a model reply.
                answer = "这是界面样例。接入服务端后，回答会保存在你的私密会话中。"
            })
            2 -> PublishScreen(
                padding, question, answer, note, { note = it },
                preview, { preview = it },
            )
            else -> ProfileScreen(padding)
        }
    }
}

@Composable
private fun DiscoverScreen(padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("看看大家在问什么", style = MaterialTheme.typography.headlineSmall) }
        items(samplePosts) { post ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(post.category, color = MaterialTheme.colorScheme.primary)
                    Text(post.question, style = MaterialTheme.typography.titleMedium)
                    Text(post.excerpt, style = MaterialTheme.typography.bodyMedium)
                    Text("AI 生成内容 · 样例帖", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AskScreen(
    padding: PaddingValues,
    question: String,
    onQuestionChange: (String) -> Unit,
    answer: String,
    onAsk: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("先私下问问", style = MaterialTheme.typography.headlineSmall)
        Text("你的提问和回答默认只对自己可见。")
        OutlinedTextField(
            value = question,
            onValueChange = onQuestionChange,
            label = { Text("你想问什么？") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Button(onClick = onAsk, enabled = question.isNotBlank()) { Text("生成界面样例") }
        if (answer.isNotEmpty()) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("回答预览", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(answer)
                }
            }
        }
    }
}

@Composable
private fun PublishScreen(
    padding: PaddingValues,
    question: String,
    answer: String,
    note: String,
    onNoteChange: (String) -> Unit,
    preview: Boolean,
    onPreviewChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("选择后再公开", style = MaterialTheme.typography.headlineSmall)
        if (question.isBlank() || answer.isBlank()) {
            Text("先在提问页准备内容。真实发布需要登录、逐段选取和审核。")
            return@Column
        }
        Text("问题：", style = MaterialTheme.typography.titleMedium)
        Text(question)
        Text("回答片段：", style = MaterialTheme.typography.titleMedium)
        Text(answer)
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text("我为什么分享") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = { onPreviewChange(!preview) }) {
                Text(if (preview) "返回编辑" else "预览公开内容")
            }
        }
        if (preview) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(question, style = MaterialTheme.typography.titleMedium)
                    Text(answer)
                    Text(note)
                    Text("AI 生成内容 · 当前仅为本地预览")
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall)
        Text("账号、私密提问和已发布帖子将在登录接入后显示。")
    }
}
