package dev.horizonmanager

import android.app.Application
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
    if (arguments.isEmpty()) HorizonApplication.instance.getString(id)
    else HorizonApplication.instance.getString(id, *arguments)

internal fun digestLocale(locale: Locale = Locale.getDefault()): String =
    when (locale.language) {
        "ru" -> "ru"
        "zh" -> "zh"
        else -> "en"
    }

internal val digestLanguages = listOf("en", "ru", "zh")

internal fun digestLanguage(available: List<String>, preferred: String = digestLocale()): String? =
    (listOf(preferred) + digestLanguages).firstOrNull { it in available }

internal fun agentUpdateAvailable(installed: String): Boolean {
    val current = installed.split('.').mapNotNull(String::toIntOrNull)
    val target = BuildConfig.AGENT_VERSION.split('.').mapNotNull(String::toIntOrNull)
    if (current.size != 3 || target.size != 3) return false
    val difference = current.zip(target).firstOrNull { (left, right) -> left != right }
    return difference != null && difference.first < difference.second
}
