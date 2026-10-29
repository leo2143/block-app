package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.SelectableApp
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {

    private fun app(label: String, packageName: String = label.lowercase()) =
        SelectableApp(packageName = packageName, label = label, installed = true, ownerBlock = null)

    @Test
    fun `normalize lowercases and strips diacritics`() {
        assertEquals("camara", AppSearch.normalize("Cámara"))
        assertEquals("camara", AppSearch.normalize("CAMARA"))
    }

    @Test
    fun `query without accents finds an accented label`() {
        val result = AppSearch.search(listOf(app("Cámara"), app("Reloj")), "camara")

        assertEquals(listOf("Cámara"), result.map { it.label })
    }

    @Test
    fun `query with accents finds an unaccented label`() {
        val result = AppSearch.search(listOf(app("Camara"), app("Reloj")), "CÁMARA")

        assertEquals(listOf("Camara"), result.map { it.label })
    }

    @Test
    fun `matching is case insensitive and by substring`() {
        val result = AppSearch.search(listOf(app("Instagram"), app("WhatsApp")), "STA")

        assertEquals(listOf("Instagram"), result.map { it.label })
    }

    @Test
    fun `blank query keeps every app`() {
        val result = AppSearch.search(listOf(app("zeta"), app("Alfa")), "  ")

        assertEquals(2, result.size)
    }

    @Test
    fun `results are sorted case insensitively by label`() {
        val result = AppSearch.search(listOf(app("zeta"), app("Alfa"), app("beta")), "")

        assertEquals(listOf("Alfa", "beta", "zeta"), result.map { it.label })
    }

    @Test
    fun `an uninstalled app is found by its package name label`() {
        val missing = SelectableApp("com.old.app", "com.old.app", installed = false, ownerBlock = null)

        assertEquals(listOf(missing), AppSearch.search(listOf(missing, app("Reloj")), "old"))
    }

    @Test
    fun `picker lists apps selected at open first in opening order then the rest sorted`() {
        val apps = listOf(app("Alfa"), app("Beta"), app("Gamma"), app("Zeta"))

        val result = AppSearch.pickerList(apps, query = "", openedSelection = listOf("zeta", "beta"))

        assertEquals(listOf("Zeta", "Beta", "Alfa", "Gamma"), result.map { it.label })
    }

    @Test
    fun `picker applies the query before putting opened apps first`() {
        val apps = listOf(app("Alfa"), app("Beta"), app("Alba"), app("Zeta"))

        val result = AppSearch.pickerList(apps, query = "al", openedSelection = listOf("alba"))

        assertEquals(listOf("Alba", "Alfa"), result.map { it.label })
    }

    @Test
    fun `picker ignores opened packages that are not in the list`() {
        val result = AppSearch.pickerList(listOf(app("Beta"), app("Alfa")), "", listOf("gone"))

        assertEquals(listOf("Alfa", "Beta"), result.map { it.label })
    }
}
