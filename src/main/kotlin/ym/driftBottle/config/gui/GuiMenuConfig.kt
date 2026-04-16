package ym.driftBottle.config.gui

import org.bukkit.Material

data class GuiElementConfig(
    val charKey: Char,
    val function: String?,
    val template: String?,
    val material: Material,
    val name: String,
    val lore: List<String>,
    val amount: Int,
    val glow: Boolean,
    val customModelData: Int?,
    val head: Boolean,
    val texture: String?,
    val disabledMaterial: Material?,
    val disabledName: String?,
    val disabledLore: List<String>?,
    val disabledAmount: Int?,
    val disabledGlow: Boolean?,
    val disabledCustomModelData: Int?,
    val disabledHead: Boolean?,
    val disabledTexture: String?,
)

data class GuiShapeLayout(
    val rows: List<String>,
    val slotsByChar: Map<Char, List<Int>>,
) {
    val size: Int
        get() = rows.size * 9

    fun firstSlot(charKey: Char): Int? = slotsByChar[charKey]?.firstOrNull()

    fun slots(charKey: Char): List<Int> = slotsByChar[charKey].orEmpty()
}

data class GuiMenuConfig(
    val key: String,
    val title: String,
    val shape: GuiShapeLayout,
    val elementsByChar: Map<Char, GuiElementConfig>,
) {
    private val elementsByFunction = elementsByChar.entries
        .mapNotNull { entry -> entry.value.function?.let { it to (entry.key to entry.value) } }
        .toMap()

    private val elementsByTemplate = elementsByChar.entries
        .mapNotNull { entry -> entry.value.template?.let { it to (entry.key to entry.value) } }
        .toMap()

    fun findByFunction(function: String): Pair<Char, GuiElementConfig>? = elementsByFunction[function]

    fun findByTemplate(template: String): Pair<Char, GuiElementConfig>? = elementsByTemplate[template]
}
