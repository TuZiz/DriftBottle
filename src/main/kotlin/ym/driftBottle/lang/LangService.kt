package ym.driftBottle.lang

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.driftBottle.util.TextColorizer
import java.io.File
import java.io.InputStreamReader

class LangService(
    private val plugin: JavaPlugin,
    private var languageCode: String,
) {

    private var messages: YamlConfiguration = load(languageCode)

    fun reload(languageCode: String) {
        this.languageCode = languageCode
        messages = load(languageCode)
    }

    fun get(key: String): String {
        val raw = messages.getString(key)
            ?: error("Missing language key: $key in $languageCode.yml")
        return TextColorizer.color(raw)
    }

    fun format(key: String, placeholders: Map<String, String> = emptyMap()): String {
        var result = get(key)
        placeholders.forEach { (placeholder, value) ->
            result = result.replace("%$placeholder%", value)
        }
        return TextColorizer.color(result)
    }

    fun formatLines(key: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        return messages.getStringList(key)
            .ifEmpty { listOf(messages.getString(key) ?: error("Missing language key: $key in $languageCode.yml")) }
            .map { line ->
                var result = line
                placeholders.forEach { (placeholder, value) ->
                    result = result.replace("%$placeholder%", value)
                }
                TextColorizer.color(result)
            }
    }

    private fun load(languageCode: String): YamlConfiguration {
        val target = File(plugin.dataFolder, "lang/$languageCode.yml")
        if (!target.exists()) {
            target.parentFile.mkdirs()
            plugin.saveResource("lang/$languageCode.yml", false)
        }
        val loaded = YamlConfiguration.loadConfiguration(target)
        plugin.getResource("lang/$languageCode.yml")?.use { stream ->
            val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
            loaded.setDefaults(defaults)
        }
        return loaded
    }
}
