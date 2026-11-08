package xin.dshaa.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTitleTest {

    @Test
    fun takesFirstMeaningfulLine() {
        assertEquals("帮我看看这个 bug", SessionTitle.from("帮我看看这个 bug\n第二行不要"))
        assertEquals("第二行", SessionTitle.from("\n\n第二行\n第三行"))
    }

    @Test
    fun stripsMarkdownMarkers() {
        assertEquals("标题", SessionTitle.from("## 标题\n正文"))
        assertEquals("引用", SessionTitle.from("> 引用"))
        assertEquals("列表项", SessionTitle.from("- 列表项"))
        assertEquals("有序项", SessionTitle.from("1. 有序项"))
        assertEquals("代码", SessionTitle.from("```kotlin\n代码\n```"))
    }

    @Test
    fun collapsesWhitespaceAndTruncates() {
        assertEquals("a b c", SessionTitle.from("  a   b\tc  "))
        assertEquals("x".repeat(SessionTitle.MAX_LENGTH), SessionTitle.from("x".repeat(100)))
    }

    @Test
    fun emptyTextFallsBackToImage() {
        assertEquals("图片", SessionTitle.from(""))
        assertEquals("图片", SessionTitle.from("   \n\n  "))
        assertEquals("图片", SessionTitle.from("```\n```"))
    }
}
