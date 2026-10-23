package io.github.perfecticons.domain

import io.github.perfecticons.data.net.RepoEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoEndpointTest {

    private val base = "https://github.com/pzcn/Perfect-Icons-Completion-Project.git"

    @Test
    fun `empty accelerator keeps direct access only`() {
        assertEquals(listOf(base), RepoEndpoint.candidates(base, ""))
        assertEquals(listOf(base), RepoEndpoint.candidates(base, null))
        assertNull(RepoEndpoint.resolve(base, "   "))
    }

    @Test
    fun `prefix accelerator is prepended`() {
        assertEquals(
            "https://proxy.example/$base",
            RepoEndpoint.resolve(base, "https://proxy.example/"),
        )
        assertEquals(
            listOf("https://proxy.example/$base", base),
            RepoEndpoint.candidates(base, "https://proxy.example"),
        )
    }

    @Test
    fun `template accelerator replaces url placeholder`() {
        assertEquals(
            "https://proxy.example/fetch?u=$base",
            RepoEndpoint.resolve(base, "https://proxy.example/fetch?u={url}"),
        )
    }

    @Test
    fun `invalid accelerators are rejected`() {
        assertFalse(RepoEndpoint.isUsableAccelerator("not a url"))
        assertFalse(RepoEndpoint.isUsableAccelerator("ftp://proxy.example"))
        assertFalse(RepoEndpoint.isUsableAccelerator(""))
        assertTrue(RepoEndpoint.isUsableAccelerator("https://proxy.example/"))
        assertTrue(RepoEndpoint.isUsableAccelerator("https://proxy.example/{url}"))
    }

    @Test
    fun `direct url is always the final fallback`() {
        val candidates = RepoEndpoint.candidates(base, "https://proxy.example/")
        assertEquals(base, candidates.last())
    }
}
