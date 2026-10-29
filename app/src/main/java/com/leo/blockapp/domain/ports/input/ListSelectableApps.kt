package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.SelectableApp

/** Input port: apps the editor can offer, sorted by label. */
interface ListSelectableApps {
    suspend operator fun invoke(editingBlockId: String?): List<SelectableApp>
}
