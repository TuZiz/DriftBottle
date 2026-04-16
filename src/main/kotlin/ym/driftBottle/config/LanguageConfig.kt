package ym.driftBottle.config

import org.bukkit.plugin.java.JavaPlugin

data class LanguageConfig(
    val currentLanguage: String,
) {
    companion object {
        fun load(plugin: JavaPlugin): LanguageConfig {
            return LanguageConfig(
                currentLanguage = plugin.config.getString("language", "zh_cn")!!,
            )
        }
    }
}
