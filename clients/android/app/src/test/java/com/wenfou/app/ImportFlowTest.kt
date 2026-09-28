package com.wenfou.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-xhdpi")
class ImportFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage("build/outputs/ui-review/$name.png")
    }
    private fun openText(text: String) {
        compose.onNodeWithText("导入", useUnmergedTree = true).performClick()
        compose.onNodeWithText("分享链接或复制内容").performTextInput(text)
        compose.onNodeWithText("解析内容").performScrollTo().performClick()
    }
    @Test(timeout = 60000) fun copyImportReviewEditAndShare() {
        openText("问题：怎样开始一个新习惯？\n豆包：把目标缩小到每天两分钟。\n问题：如果中断了呢？\n豆包：从下一次重新开始。")
        compose.onNodeWithText("第 2 轮").performClick()
        compose.onNodeWithText("导入的问题").assertTextContains("如果中断了呢？")
        compose.onNodeWithText("第 1 轮").performClick()
        compose.onNodeWithText("导入的回答").performTextReplacement("把目标缩小到每天两分钟，并记下实际感受。")
        shot("08-import-review")
        compose.onNodeWithText("确认，进入发布预览").performScrollTo().performClick()
        compose.onNodeWithText("分享文字").assertExists()
        compose.onNodeWithText("问否 · 外部导入 · 豆包（经用户编辑确认）").assertExists()
        compose.onNodeWithText("把目标缩小到每天两分钟，并记下实际感受。").assertExists()
    }
    @Test(timeout = 60000) fun answerOnlyRequiresQuestionAndProtectsExistingDraft() {
        compose.onNodeWithText("提问", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("原来的问题")
        openText("这是一段只有回答的复制文字，需要补充问题。")
        compose.onNodeWithText("确认，进入发布预览").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("导入的问题").performScrollTo().performTextInput("新的问题")
        compose.onNodeWithText("确认，进入发布预览").performScrollTo().performClick()
        compose.onNodeWithText("替换当前分享草稿？").assertExists()
        compose.onNodeWithText("保留原草稿").performClick()
        compose.onNodeWithText("关闭").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("原来的问题")
    }
    @Test(timeout = 60000) fun foregroundClipboardOffersOnceAndCanBeDisabled() {
        val activity = compose.activity
        val prefs = activity.getSharedPreferences("import_preferences", Context.MODE_PRIVATE)
        compose.runOnUiThread {
            prefs.edit().clear().commit()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "https://chat.deepseek.com/share/khnxwaltmm52h62ac2"))
            activity.onWindowFocusChanged(true)
        }
        compose.onNodeWithText("发现剪贴板内容").assertExists()
        shot("09-clipboard-confirm")
        compose.onNodeWithText("暂不导入").performClick()
        compose.runOnUiThread { activity.onWindowFocusChanged(true) }
        compose.onNodeWithText("发现剪贴板内容").assertDoesNotExist()
        compose.runOnUiThread {
            prefs.edit().putBoolean("automatic_clipboard", false).commit()
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("test", "问题：新问题\n回答：新回答"))
            activity.onWindowFocusChanged(true)
        }
        compose.onNodeWithText("发现剪贴板内容").assertDoesNotExist()
    }
    @Test(timeout = 60000) fun sensitiveClipboardIgnoredAndSystemShareAccepted() {
        val activity = compose.activity
        compose.runOnUiThread {
            activity.getSharedPreferences("import_preferences", Context.MODE_PRIVATE).edit().clear().commit()
            val clip = ClipData.newPlainText("test", "问题：敏感问题\n回答：敏感回答")
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            activity.onWindowFocusChanged(true)
        }
        compose.onNodeWithText("发现剪贴板内容").assertDoesNotExist()
        // ActivityScenario matches lifecycle events against the original launch Intent.
        // onNewIntent legitimately updates it in the app; restore it for scenario teardown.
        val launchIntent = activity.intent
        try {
            compose.runOnUiThread { activity.onNewIntent(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "问题：系统分享的问题\n回答：系统分享的答案") }) }
            compose.onNodeWithText("收到分享内容").assertExists()
            compose.onNodeWithText("解析并预览").performClick()
            compose.onNodeWithText("导入的问题").assertTextContains("系统分享的问题")
        } finally {
            compose.runOnUiThread { activity.intent = launchIntent }
        }
    }
}
