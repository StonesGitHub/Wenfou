package com.wenfou.app

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
class VisualFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/ui-review/$name.png")
    }

    @Test fun mainScreensAndQuestionToShare() {
        shot("01-discover")
        compose.onNodeWithText("提问", useUnmergedTree = true).performClick()
        compose.onNodeWithText("看看示例回答").assertIsNotEnabled()
        shot("02-ask")
        compose.onNode(hasSetTextAction()).performTextInput("怎样开始一个新的生活习惯？")
        compose.onNodeWithText("看看示例回答").performScrollTo().performClick()
        compose.onNodeWithText("整理成分享").performScrollTo().performClick()
        compose.onNodeWithText("完整回答").assertExists()
        shot("03-share")
        compose.onNodeWithText("预览分享卡片").performScrollTo().performClick()
        compose.onNodeWithText("分享文字").assertExists()
        shot("04-preview")
    }

    @Test fun profileScreen() {
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("好奇心收藏家").assertIsDisplayed()
        shot("05-profile")
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h740dp-xhdpi")
    fun compactScreen() {
        shot("06-compact-discover")
        compose.onNodeWithText("分享", useUnmergedTree = true).performClick()
        compose.onNodeWithText("去问一个问题").performScrollTo().assertIsDisplayed()
        shot("07-empty-share")
    }
}
