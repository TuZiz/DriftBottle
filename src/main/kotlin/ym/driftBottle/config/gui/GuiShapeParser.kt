package ym.driftBottle.config.gui

class GuiShapeParser {

    fun parse(rows: List<String>): GuiShapeLayout {
        require(rows.isNotEmpty()) { "GUI shape cannot be empty" }
        val invalidRow = rows.withIndex().firstOrNull { it.value.length != 9 }
        require(invalidRow == null) {
            "Each GUI shape row must have exactly 9 characters, but row ${invalidRow!!.index + 1} has ${invalidRow.value.length}: '${invalidRow.value}'"
        }
        val slotsByChar = linkedMapOf<Char, MutableList<Int>>()
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, charKey ->
                if (charKey == ' ') {
                    return@forEachIndexed
                }
                val slot = rowIndex * 9 + columnIndex
                slotsByChar.computeIfAbsent(charKey) { mutableListOf() }.add(slot)
            }
        }
        return GuiShapeLayout(rows, slotsByChar)
    }
}
