package io.github.perfecticons.domain

import io.github.perfecticons.data.repo.PathSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSelectorTest {

    private val style = setOf("Hyper")

    @Test
    fun `traversal segments are rejected anywhere in the path`() {
        assertFalse(PathSelector.isSelected("icons/../0.png", setOf("Hyper")))
        assertFalse(
            PathSelector.isSelected(
                "themes/common/layer_animating_icons/../../../evil",
                setOf("Hyper"),
            ),
        )
        assertFalse(
            PathSelector.isSelected(
                "themes/Hyper/layer_animating_icons/com.foo/../../../../evil",
                setOf("Hyper"),
            ),
        )
        assertTrue(
            PathSelector.isSelected(
                "themes/Hyper/layer_animating_icons/com.foo/quietImage.png",
                setOf("Hyper"),
            ),
        )
    }

    @Test
    fun `static layer accepts only 0 and 1`() {
        assertTrue(PathSelector.isSelected("icons/com.example/0.png", style))
        assertTrue(PathSelector.isSelected("icons/com.example/1.png", style))
        assertFalse(PathSelector.isSelected("icons/com.example/10.png", style))
        assertFalse(PathSelector.isSelected("icons/com.example/0.jpg", style))
        assertFalse(PathSelector.isSelected("icons/com.example/background.png", style))
    }

    @Test
    fun `style and base paths are separated`() {
        assertTrue(PathSelector.isSelected("themes/Hyper/icons/com.example/0.png", style))
        assertTrue(PathSelector.isSelected("themes/Hyper/icons/flat.png", style))
        assertFalse(PathSelector.isSelected("themes/default/icons/com.example/0.png", style))
        assertFalse(PathSelector.isSelected("themes/Hyper/other/file.png", style))
    }

    @Test
    fun `common dynamic applies to every style`() {
        assertTrue(
            PathSelector.isSelected(
                "themes/common/layer_animating_icons/com.shared/config.xml",
                style,
            ),
        )
    }

    @Test
    fun `dynamic keeps any layer count`() {
        assertTrue(
            PathSelector.isSelected(
                "themes/Hyper/layer_animating_icons/com.anim/2/fancy/manifest.xml",
                style,
            ),
        )
        assertTrue(
            PathSelector.isSelected(
                "themes/Hyper/layer_animating_icons/com.anim/2/quiet/quietImage.png",
                style,
            ),
        )
    }

    @Test
    fun `unsupported trees are excluded`() {
        assertFalse(PathSelector.isSelected("emui/something.png", style))
        assertFalse(PathSelector.isSelected("icons/com.example/.nomedia", style))
        assertFalse(PathSelector.isSelected("icons/../escape/0.png", style))
    }
}
