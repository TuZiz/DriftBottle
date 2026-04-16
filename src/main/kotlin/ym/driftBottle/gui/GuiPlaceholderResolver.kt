package ym.driftBottle.gui

class GuiPlaceholderResolver {

    fun apply(text: String, values: Map<String, String>): String {
        var result = text
        values.forEach { (key, value) ->
            result = result.replace("%$key%", value)
        }
        return result
    }

    fun apply(lines: List<String>, values: Map<String, String>): List<String> {
        return lines.map { apply(it, values) }
    }
}
