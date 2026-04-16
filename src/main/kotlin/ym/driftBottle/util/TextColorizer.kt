package ym.driftBottle.util

import org.bukkit.ChatColor

object TextColorizer {

    private val hexPattern = Regex("(?i)&?#([A-F0-9]{6})")

    fun color(text: String): String {
        val normalizedHex = hexPattern.replace(text) { match ->
            val hex = match.groupValues[1].uppercase()
            buildString {
                append('&')
                append('x')
                hex.forEach { char ->
                    append('&')
                    append(char)
                }
            }
        }
        return ChatColor.translateAlternateColorCodes('&', normalizedHex)
    }

    fun color(lines: List<String>): List<String> = lines.map(::color)
}
