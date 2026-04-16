package ym.driftBottle.config.gui

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class GuiFileLoader(
    private val plugin: JavaPlugin,
    private val shapeParser: GuiShapeParser = GuiShapeParser(),
) {

    fun ensureDefaults() {
        defaultFiles().forEach { fileName ->
            val target = File(plugin.dataFolder, "gui/$fileName")
            target.parentFile.mkdirs()
            if (!target.exists()) {
                plugin.saveResource("gui/$fileName", false)
                return@forEach
            }
            if (shouldReplaceLegacy(fileName, target)) {
                plugin.saveResource("gui/$fileName", true)
            }
        }
    }

    fun load(fileName: String, key: String): GuiMenuConfig {
        val file = File(plugin.dataFolder, "gui/$fileName")
        val yaml = YamlConfiguration.loadConfiguration(file)
        val title = yaml.getString("title") ?: error("Missing title in gui/$fileName")
        val shapeRows = yaml.getStringList("shape")
        val shape = shapeParser.parse(shapeRows)
        val keySection = yaml.getConfigurationSection("key") ?: error("Missing key section in gui/$fileName")
        val elements = keySection.getKeys(false).associate { rawKey ->
            val charKey = rawKey.singleOrNull() ?: error("Each key entry in gui/$fileName must be exactly one character: $rawKey")
            val section = keySection.getConfigurationSection(rawKey)!!
            charKey to readElement(charKey, section)
        }
        shape.slotsByChar.keys.forEach { charKey ->
            require(elements.containsKey(charKey)) { "Shape in gui/$fileName uses '$charKey' but key section does not define it" }
        }
        return GuiMenuConfig(
            key = key,
            title = title,
            shape = shape,
            elementsByChar = elements,
        )
    }

    private fun readElement(charKey: Char, section: ConfigurationSection): GuiElementConfig {
        return GuiElementConfig(
            charKey = charKey,
            function = section.getString("function"),
            template = section.getString("template"),
            material = readMaterial(section.getString("material"), Material.STONE),
            name = section.getString("name", charKey.toString())!!,
            lore = section.getStringList("lore"),
            amount = section.getInt("amount", 1).coerceAtLeast(1),
            glow = section.getBoolean("glow", false),
            customModelData = section.getInt("custom-model-data").takeIf { section.contains("custom-model-data") },
            head = section.getBoolean("head", false),
            texture = section.getString("texture")?.trim()?.takeIf { it.isNotEmpty() },
            disabledMaterial = readOptionalMaterial(section.getString("disabled-material")),
            disabledName = section.getString("disabled-name"),
            disabledLore = section.getStringList("disabled-lore").takeIf { section.contains("disabled-lore") },
            disabledAmount = section.getInt("disabled-amount").takeIf { section.contains("disabled-amount") }?.coerceAtLeast(1),
            disabledGlow = section.getBoolean("disabled-glow").takeIf { section.contains("disabled-glow") },
            disabledCustomModelData = section.getInt("disabled-custom-model-data").takeIf { section.contains("disabled-custom-model-data") },
            disabledHead = section.getBoolean("disabled-head").takeIf { section.contains("disabled-head") },
            disabledTexture = section.getString("disabled-texture")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun defaultFiles(): List<String> = listOf(
        "main.yml",
        "my_bottles.yml",
        "inbox.yml",
        "bottle_detail.yml",
        "blacklist.yml",
        "profile.yml",
        "admin/main.yml",
        "admin/bottles.yml",
        "admin/bottle_detail.yml",
        "admin/players.yml",
        "admin/confirm.yml",
    )

    private fun shouldReplaceLegacy(fileName: String, file: File): Boolean {
        val content = file.readText()
        return when (fileName) {
            "main.yml" -> "start_match" in content || "cancel_match" in content || "session" in content
            "profile.yml" -> "rating_count" in content || "average_rating" in content || "match_status_profile" in content
            else -> false
        }
    }

    private fun readMaterial(raw: String?, fallback: Material): Material {
        return raw?.uppercase()?.let(Material::matchMaterial) ?: fallback
    }

    private fun readOptionalMaterial(raw: String?): Material? {
        return raw?.uppercase()?.let(Material::matchMaterial)
    }
}
