package dev.horizonmanager

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

class HorizonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        internal lateinit var instance: HorizonApplication
            private set
    }
}

internal fun ui(@StringRes id: Int, vararg arguments: Any?): String =
    localizedResources(HorizonApplication.instance).let { resources ->
        if (arguments.isEmpty()) resources.getString(id) else resources.getString(id, *arguments)
    }

internal val legacyDigestLanguages = listOf("en", "ru", "zh")
internal val digestLanguages = listOf("ru", "en", "de", "fr", "it", "es", "ja", "ko", "zh", "kk", "tt")

internal fun digestLocale(locale: Locale = systemLanguage()): String = when {
    locale.language !in digestLanguages -> "en"
    locale.language == "zh" && (locale.script == "Hant" || locale.script != "Hans" &&
        locale.country in setOf("TW", "HK", "MO")) -> "en"
    locale.language in setOf("kk", "tt") && locale.script.isNotEmpty() && locale.script != "Cyrl" -> "en"
    else -> locale.language
}

private fun systemLanguage(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.ENGLISH

internal fun appLocale(system: Locale = systemLanguage()): Locale = when (digestLocale(system)) {
    "zh" -> Locale.Builder().setLocale(system).setScript("Hans").build()
    system.language -> system
    else -> Locale.ENGLISH
}

internal fun appLanguageContext(base: Context): Context {
    val configuration = Configuration(base.resources.configuration).apply { setLocales(LocaleList(appLocale())) }
    return base.createConfigurationContext(configuration)
}

private var stringResources: Pair<Locale, Resources>? = null

@Synchronized
private fun localizedResources(base: Context): Resources {
    val language = systemLanguage()
    stringResources?.takeIf { it.first == language }?.let { return it.second }
    return appLanguageContext(base).resources.also { stringResources = language to it }
}

internal fun digestLanguageLabel(code: String): String = when (code) {
    "ru" -> "Русский"
    "en" -> "English"
    "de" -> "Deutsch"
    "fr" -> "Français"
    "it" -> "Italiano"
    "es" -> "Español"
    "ja" -> "日本語"
    "ko" -> "한국어"
    "zh" -> "简体中文"
    "kk" -> "Қазақша"
    "tt" -> "Татарча"
    else -> code
}

internal fun digestLanguage(available: List<String>, preferred: String = digestLocale()): String? =
    (listOf(preferred, "en") + digestLanguages).firstOrNull { it in available }

private fun agentVersionParts(version: String): List<Int>? {
    val parts = version.split('.')
    if (parts.size != 3 || parts.any { it.isEmpty() || !it.all { digit -> digit in '0'..'9' } }) return null
    return parts.map { it.toIntOrNull() ?: return null }
}

internal fun isAgentVersion(version: String): Boolean = agentVersionParts(version) != null

private fun agentVersionDifference(installed: String): Int? {
    val current = agentVersionParts(installed) ?: return null
    val target = checkNotNull(agentVersionParts(BuildConfig.AGENT_VERSION))
    val difference = current.zip(target).firstOrNull { (left, right) -> left != right }
    return difference?.let { (left, right) -> left.compareTo(right) } ?: 0
}

internal fun agentUpdateAvailable(installed: String) = agentVersionDifference(installed) == -1
internal fun agentRollbackRequired(installed: String) = agentVersionDifference(installed) == 1
internal fun agentCompatible(installed: String) = installed == BuildConfig.AGENT_VERSION
