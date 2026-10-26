package io.github.perfecticons.data.apps

import java.text.Collator
import java.util.Locale

/**
 * Determines which icons a preview should show first.
 *
 * Priority follows the user's own launcher: only apps that actually expose a
 * launcher entry are considered, and they are ordered by display name using a
 * Chinese collator, so Chinese names sort by pinyin and Latin names sort
 * dictionary-wise in the same sequence.
 *
 * Apps that are not installed simply never contribute, and [order] returns an
 * empty list when the launcher has no visible apps.
 */
object PreviewPriority {

    fun order(apps: List<InstalledApp>): List<String> {
        if (apps.isEmpty()) return emptyList()
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
            strength = Collator.SECONDARY
        }
        return apps
            .sortedWith { left, right -> collator.compare(left.label, right.label) }
            .flatMap { app -> app.suggestedIds.map { it.id } }
            .distinct()
    }


    /**
     * Orders theme icons by [priority], then appends everything else in a stable
     * alphabetical order so a preview can always be filled.
     */
    fun orderIcons(priority: List<String>, availableIds: List<String>): List<String> {
        val ordered = LinkedHashSet<String>(priority.size + availableIds.size)
        priority.forEach { id ->
            if (id in availableIds) ordered += id
        }
        availableIds.sorted().forEach { ordered += it }
        return ordered.toList()
    }
}
