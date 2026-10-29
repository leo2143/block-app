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
}
