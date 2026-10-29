package com.leo.blockapp.adapters.input.ui.blocks

/** Joins labels the Spanish way: "A", "A y B", "A, B y C". */
internal object LabelList {

    fun join(labels: List<String>): String = when (labels.size) {
        0 -> ""
        1 -> labels.first()
        else -> labels.dropLast(1).joinToString(", ") + " y " + labels.last()
    }
}
