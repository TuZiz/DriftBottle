package ym.driftBottle.service

import ym.driftBottle.config.ChatProtectionConfig
import ym.driftBottle.lang.LangService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatProtectionService(
    private val configProvider: () -> ChatProtectionConfig,
    private val lang: LangService,
) {

    private val lastMessageAt = ConcurrentHashMap<UUID, Long>()

    fun validate(playerUuid: UUID, rawMessage: String): String? {
        val config = configProvider()
        val message = rawMessage.trim()
        if (message.isEmpty()) {
            return lang.get("input.empty")
        }
        if (message.length > config.maxMessageLength) {
            return lang.format("input.too-long", mapOf("max_length" to config.maxMessageLength.toString()))
        }
        val lowerMessage = message.lowercase()
        if (config.bannedWords.any { lowerMessage.contains(it.lowercase()) }) {
            return lang.get("input.banned-word")
        }
        val now = System.currentTimeMillis()
        val previous = lastMessageAt[playerUuid]
        if (previous != null && now - previous < config.messageCooldownMillis) {
            val remaining = ((config.messageCooldownMillis - (now - previous)) / 1000.0).coerceAtLeast(0.1)
            return lang.format("input.cooldown", mapOf("seconds" to "%.1f".format(remaining)))
        }
        lastMessageAt[playerUuid] = now
        return null
    }

    fun clear(playerUuid: UUID) {
        lastMessageAt.remove(playerUuid)
    }
}
