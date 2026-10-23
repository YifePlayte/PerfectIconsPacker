package io.github.perfecticons.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Top-level destinations are pages of one pager (the miuix Example's shell):
 * the bottom bar drives `pagerState.animateToPage`, so switching tabs slides.
 * Sub-screens push additional entries on top of [Main].
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    /** Custom icon entry: pick one or more installed apps to add ids for. */
    @Serializable
    data class CustomStaticAdd(val dynamic: Boolean = false) : Route

    /** Custom icon entry: pick which icon ids of one app to create. */
    @Serializable
    data class CustomStaticPick(val packageName: String, val dynamic: Boolean = false) : Route
}
