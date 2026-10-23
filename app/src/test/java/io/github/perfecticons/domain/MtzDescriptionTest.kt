package io.github.perfecticons.domain

import io.github.perfecticons.domain.build.MtzBuilder
import io.github.perfecticons.domain.validation.XmlSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MtzDescriptionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sample() = MtzBuilder.MtzInfo(
        title = "完美图标补全计划",
        description = "HyperOS 4 完美图标补全\n主题：Hyper",
        author = "tester",
        version = "1.0.0",
    )

    @Test
    fun `description starts with the xml declaration and carries no indent`() {
        val xml = sample().toDescriptionXml()

        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.lines().none { it.startsWith(" ") || it.startsWith("\t") })
    }

    @Test
    fun `cdata terminators in free text cannot break out of elements`() {
        val xml = MtzBuilder.MtzInfo(
            title = "t]]></title><evil/>",
            description = "d]]>x",
            author = "a]]>b",
            version = "1.0.0",
        ).toDescriptionXml()

        val file = File(temp.root, "injection.xml")
        file.writeText(xml)
        val root = XmlSupport.parse(file)

        assertEquals("theme", root?.tagName)
        assertEquals(
            "t]]></title><evil/>",
            root?.getElementsByTagName("title")?.item(0)?.textContent,
        )
        assertEquals(0, root?.getElementsByTagName("evil")?.length)
    }

    @Test
    fun `description parses with theme root and expected fields`() {
        val file = File(temp.root, "description.xml")
        file.writeText(sample().toDescriptionXml())

        val root = XmlSupport.parse(file)

        assertEquals("theme", root?.tagName)
        assertEquals(
            "完美图标补全计划",
            root?.getElementsByTagName("title")?.item(0)?.textContent,
        )
        assertEquals(
            "1.0.0",
            root?.getElementsByTagName("version")?.item(0)?.textContent,
        )
    }
}
