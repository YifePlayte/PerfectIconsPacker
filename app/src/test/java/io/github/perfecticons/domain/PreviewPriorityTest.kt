package io.github.perfecticons.domain

import io.github.perfecticons.data.apps.InstalledApp
import io.github.perfecticons.data.apps.LaunchEntry
import io.github.perfecticons.data.apps.PreviewPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPriorityTest {

    private fun app(label: String, packageName: String, entries: List<String> = emptyList()) =
        InstalledApp(
            packageName = packageName,
            label = label,
            entries = entries.map { LaunchEntry(packageName, it, label) },
        )

    @Test
    fun `chinese names sort by pinyin`() {
        val apps = listOf(
            app("支付宝", "com.alipay"),
            app("微信", "com.tencent.mm"),
            app("百度", "com.baidu"),
            app("爱奇艺", "com.iqiyi"),
        )
        val order = PreviewPriority.order(apps)
        assertEquals(
            listOf("com.iqiyi", "com.baidu", "com.tencent.mm", "com.alipay"),
            order,
        )
    }

    @Test
    fun `latin names sort before chinese and alphabetically`() {
        val apps = listOf(
            app("微信", "com.tencent.mm"),
            app("QQ", "com.tencent.mobileqq"),
            app("Bilibili", "tv.danmaku.bili"),
            app("支付宝", "com.alipay"),
        )
        val order = PreviewPriority.order(apps)
        assertEquals(
            listOf("tv.danmaku.bili", "com.tencent.mobileqq", "com.tencent.mm", "com.alipay"),
            order,
        )
    }

    @Test
    fun `multiple launcher entries expand in sorted app order`() {
        val apps = listOf(
            app(
                label = "示例",
                packageName = "com.example",
                entries = listOf("com.example.MainActivity", "com.other.Second"),
            ),
            app("阿里", "com.alibaba"),
        )
        val order = PreviewPriority.order(apps)
        assertEquals(
            listOf(
                "com.alibaba",
                "com.example",
                "com.example.MainActivity",
                "com.example#com.other.Second",
            ),
            order,
        )
    }

    @Test
    fun `no launcher apps yields empty priority`() {
        assertTrue(PreviewPriority.order(emptyList()).isEmpty())
    }

    @Test
    fun `only ids present in the theme are kept`() {
        val priority = listOf("com.installed.a", "com.missing", "com.installed.b")
        val available = listOf("com.installed.b", "com.theme.only", "com.installed.a")
        val order = PreviewPriority.orderIcons(priority, available)
        assertEquals(
            listOf("com.installed.a", "com.installed.b", "com.theme.only"),
            order,
        )
    }

    @Test
    fun `available ids fill the remainder in stable order`() {
        val order = PreviewPriority.orderIcons(emptyList(), listOf("b", "a", "c"))
        assertEquals(listOf("a", "b", "c"), order)
    }
}
