package ym.driftBottle.config

import org.bukkit.plugin.java.JavaPlugin

data class ChatProtectionConfig(
    val messageCooldownMillis: Long,
    val maxMessageLength: Int,
    val bannedWords: List<String>,
) {
    companion object {
        fun load(plugin: JavaPlugin): ChatProtectionConfig {
            return ChatProtectionConfig(
                messageCooldownMillis = plugin.config.getLong("chat.message-cooldown-millis", 3000L),
                maxMessageLength = plugin.config.getInt("chat.max-message-length", 120),
                bannedWords = plugin.config.getStringList("chat.banned-words").filter { it.isNotBlank() },
            )
        }
    }
}
