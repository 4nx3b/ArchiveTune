/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import org.json.JSONArray

data class TranslatorLang(
    val name: String,
    val code: String,
)

object TranslatorLanguages {

    fun load(context: Context): List<TranslatorLang> {
        try {
            val json =
                context.assets
                    .open("translator_languages.json")
                    .bufferedReader()
                    .use { it.readText() }
            val arr = JSONArray(json)
            val out = mutableListOf<TranslatorLang>()
            for (i in 0 until arr.length()) {
                val o = arr.opt(i)
                if (o is String) {
                    val name = o
                    val code = name.uppercase().replace(' ', '_')
                    out.add(TranslatorLang(name, code))
                } else {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", obj.optString("label", ""))
                    val code = obj.optString("code", name.uppercase().replace(' ', '_'))
                    if (name.isNotBlank()) out.add(TranslatorLang(name, code))
                }
            }
            if (out.isNotEmpty()) return out
        } catch (_: Exception) {

        }

        return listOf(
            TranslatorLang("English", "ENGLISH"),
            TranslatorLang("Japanese", "JAPANESE"),
            TranslatorLang("Spanish", "SPANISH"),
            TranslatorLang("Chinese", "CHINESE"),
            TranslatorLang("Korean", "KOREAN"),
            TranslatorLang("French", "FRENCH"),
            TranslatorLang("German", "GERMAN"),
        )
    }
}
