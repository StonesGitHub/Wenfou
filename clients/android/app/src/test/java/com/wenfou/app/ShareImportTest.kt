package com.wenfou.app

import org.junit.Assert.*
import org.junit.Test

class ShareImportTest {
    @Test fun knownPublicLinksInsideCopiedInvitations() {
        val samples = mapOf(
            "https://chat.deepseek.com/share/khnxwaltmm52h62ac2" to "DeepSeek",
            "https://chatgpt.com/share/1234-abcd?utm_source=copy" to "ChatGPT",
            "https://www.doubao.com/thread/w123/" to "豆包",
            "https://v.doubao.com/abc123/" to "豆包",
            "https://www.kimi.com/share/abc123" to "Kimi",
            "https://kimi.moonshot.cn/share/abc123" to "Kimi"
        )
        samples.forEach { (url, platform) ->
            val link = ShareImport.link("来看看这段对话：$url，复制打开")!!
            assertEquals(platform, link.platform)
            assertFalse(link.url.contains('?'))
            assertTrue(ShareImport.safeNavigation(url, platform))
        }
    }
    @Test fun rejectsPrivateSpoofedAndNonHttpsAddresses() {
        listOf("http://chatgpt.com/share/id", "https://chatgpt.com.evil.test/share/id", "https://chatgpt.com@evil.test/share/id", "https://user@chatgpt.com/share/id", "https://chatgpt.com:444/share/id", "https://chatgpt.com/c/id", "https://127.0.0.1/share/id", "https://chatgpt.com/share/../c/id").forEach { assertNull(it, ShareImport.link(it)) }
        assertFalse(ShareImport.safeNavigation("javascript:alert('https://chatgpt.com/share/id')", "ChatGPT"))
        assertFalse(ShareImport.safeNavigation("https://chat.deepseek.com/share/id", "ChatGPT"))
        assertFalse(ShareImport.safeNavigation("https://chatgpt.com/auth/login", "ChatGPT"))
    }
    @Test fun parsesCopiedChineseAndEnglishTurnsWithoutMixing() {
        assertEquals(listOf(ShareImport.Turn("怎样开始？", "先做一件小事。"), ShareImport.Turn("然后呢？", "明天再做一次。")), ShareImport.parseText("问题：怎样开始？\r\n豆包：先做一件小事。\r\n问：然后呢？\r\n答：明天再做一次。"))
        assertEquals(listOf(ShareImport.Turn("How?", "Start small.")), ShareImport.parseText("You said:\nHow?\nChatGPT said:\nStart small."))
    }
    @Test fun codeFencesAndUnlabelledTextArePreserved() {
        val answer = "示例代码：\n```text\n问题：这不是新一轮\n回答：也不是\n```\n结束。"
        assertEquals(listOf(ShareImport.Turn("展示代码", answer)), ShareImport.parseText("问题：展示代码\n回答：$answer"))
        assertEquals(listOf(ShareImport.Turn("", "只有回答，问题需要补充。")), ShareImport.parseText("只有回答，问题需要补充。"))
        val withPreamble = ShareImport.parseText("原始前言不可丢失\n问题：为什么\n回答：因为")
        assertEquals("原始前言不可丢失", withPreamble[0].answer)
        assertEquals("为什么", withPreamble[1].question)
    }
    @Test fun incompleteQuestionsAndConsecutiveAnswersStayInOrder() {
        val turns = ShareImport.pair(listOf(ShareImport.Message("user", "第一问"), ShareImport.Message("user", "第二问"), ShareImport.Message("assistant", "第一段"), ShareImport.Message("assistant", "第二段")))
        assertEquals(ShareImport.Turn("第一问", ""), turns[0])
        assertEquals(ShareImport.Turn("第二问", "第一段\n\n第二段"), turns[1])
    }
    @Test fun clipboardLimitsAndDeduplication() {
        assertFalse(ShareImport.canSuggest("123456"))
        assertFalse(ShareImport.canSuggest("a".repeat(80)))
        assertFalse(ShareImport.canSuggest("问题：问\n回答：" + "答".repeat(120001)))
        assertEquals(ShareImport.fingerprint("内容"), ShareImport.fingerprint(" 内容\n"))
        assertNotEquals(ShareImport.fingerprint("内容"), ShareImport.fingerprint("新内容"))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedTextIsRejectedNotTruncated() { ShareImport.parseText("答".repeat(120001)) }
    @Test fun excerptDoesNotInventPunctuation() {
        assertEquals("No punctuation", firstSentence("No punctuation"))
        assertEquals("第一句。", firstSentence("第一句。第二句。"))
    }
}
