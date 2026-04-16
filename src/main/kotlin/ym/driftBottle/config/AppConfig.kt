package ym.driftBottle.config

import org.bukkit.plugin.java.JavaPlugin

data class AppConfig(
    val database: DatabaseConfig,
    val language: LanguageConfig,
    val chatProtection: ChatProtectionConfig,
    val bottle: BottleConfig,
) {
    companion object {
        fun load(plugin: JavaPlugin): AppConfig {
            plugin.reloadConfig()
            return AppConfig(
                database = DatabaseConfig.from(plugin.config),
                language = LanguageConfig.load(plugin),
                chatProtection = ChatProtectionConfig.load(plugin),
                bottle = BottleConfig.load(plugin),
            )
        }
    }
}
