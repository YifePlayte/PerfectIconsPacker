package io.github.perfecticons.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.perfecticons.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/** Back affordance for pushed sub-screens. */
@Composable
fun BackIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = stringResource(R.string.common_back),
        )
    }
}
