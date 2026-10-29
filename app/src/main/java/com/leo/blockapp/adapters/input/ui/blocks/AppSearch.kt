package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.SelectableApp
import java.text.Normalizer

/** Case and diacritic insensitive app filtering for the picker, sorted by label. */
object AppSearch {

    private val combiningMarks = Regex("\\p{Mn}+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

    fun search(apps: List<SelectableApp>, query: String): List<SelectableApp> {
        val normalizedQuery = normalize(query.trim())
        return apps
            .filter { normalizedQuery.isEmpty() || normalize(it.label).contains(normalizedQuery) }
            .sortedBy { normalize(it.label) }
    }

    /**
     * Rows for the picker: the apps selected when the editor opened come first, in opening order, so rows do not
     * jump while the user taps; everything else follows sorted by label. The [query] filters both groups.
     */
    fun pickerList(apps: List<SelectableApp>, query: String, openedSelection: List<String>): List<SelectableApp> {
        val matching = search(apps, query)
        val matchingByPackage = matching.associateBy { it.packageName }
        val opened = openedSelection.mapNotNull { matchingByPackage[it] }
        val openedPackages = opened.mapTo(mutableSetOf()) { it.packageName }
        return opened + matching.filter { it.packageName !in openedPackages }
    }
}
