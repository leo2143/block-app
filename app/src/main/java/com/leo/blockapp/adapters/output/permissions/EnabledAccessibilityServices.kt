package com.leo.blockapp.adapters.output.permissions

/**
 * Reads the `enabled_accessibility_services` setting: entries separated by ':', each `package/class`, where the
 * class is either fully qualified or short (starts with '.', relative to the package). A service counts as enabled
 * only when both the package and the FULL class name match, so a lookalike from another app never does.
 */
internal object EnabledAccessibilityServices {

    fun contains(settingValue: String?, packageName: String, serviceClassName: String): Boolean =
        settingValue.orEmpty().split(ENTRY_SEPARATOR).any { entry ->
            val parts = entry.trim().split(COMPONENT_SEPARATOR)
            if (parts.size != 2) return@any false
            val entryPackage = parts[0]
            val entryClass = parts[1].let { if (it.startsWith(".")) entryPackage + it else it }
            entryPackage == packageName && entryClass == serviceClassName
        }

    private const val ENTRY_SEPARATOR = ':'
    private const val COMPONENT_SEPARATOR = '/'
}
