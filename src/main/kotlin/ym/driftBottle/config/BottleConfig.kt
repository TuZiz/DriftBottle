package ym.driftBottle.config

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.plugin.java.JavaPlugin

data class BottleConfig(
    val throwCost: Double,
    val salvageReward: Double,
    val throwCooldownSeconds: Long,
    val salvageCooldownSeconds: Long,
    val maxActiveBottlesPerPlayer: Int,
    val maxThreadReplies: Int,
    val soundsEnabled: Boolean,
    val throwSound: Sound,
    val salvageSound: Sound,
    val replySound: Sound,
    val waterSearchRadius: Int,
    val waterMinDepth: Int,
    val visualHoverTicks: Long,
    val visualFlightTicks: Long,
    val salvagePickupWaitTicks: Long,
    val visualItem: BottleVisualItemConfig,
) {
    companion object {
        fun load(plugin: JavaPlugin): BottleConfig {
            return BottleConfig(
                throwCost = plugin.config.getDouble("bottle.throw-cost", 5.0).coerceAtLeast(0.0),
                salvageReward = plugin.config.getDouble("bottle.salvage-reward", 3.0).coerceAtLeast(0.0),
                throwCooldownSeconds = plugin.config.getLong("bottle.throw-cooldown-seconds", 10L).coerceAtLeast(0L),
                salvageCooldownSeconds = plugin.config.getLong("bottle.salvage-cooldown-seconds", 5L).coerceAtLeast(0L),
                maxActiveBottlesPerPlayer = plugin.config.getInt("bottle.max-active-bottles-per-player", 5).coerceAtLeast(1),
                maxThreadReplies = plugin.config.getInt("bottle.max-thread-replies", 6).coerceAtLeast(1),
                soundsEnabled = plugin.config.getBoolean("bottle.sounds-enabled", true),
                throwSound = readSound(plugin, "bottle.throw-sound", Sound.ITEM_BOTTLE_FILL),
                salvageSound = readSound(plugin, "bottle.salvage-sound", Sound.ENTITY_EXPERIENCE_ORB_PICKUP),
                replySound = readSound(plugin, "bottle.reply-sound", Sound.BLOCK_NOTE_BLOCK_CHIME),
                waterSearchRadius = plugin.config.getInt("bottle.water-search-radius", 5).coerceAtLeast(1),
                waterMinDepth = plugin.config.getInt("bottle.water-min-depth", 4).coerceAtLeast(1),
                visualHoverTicks = plugin.config.getLong("bottle.visual-hover-ticks", 24L).coerceAtLeast(0L),
                visualFlightTicks = plugin.config.getLong("bottle.visual-flight-ticks", 36L).coerceAtLeast(1L),
                salvagePickupWaitTicks = plugin.config.getLong("bottle.salvage-pickup-wait-ticks", 200L).coerceAtLeast(20L),
                visualItem = BottleVisualItemConfig(
                    material = readMaterial(plugin, "bottle.visual-item.material", Material.PLAYER_HEAD),
                    headOwner = plugin.config.getString("bottle.visual-item.head-owner")?.trim()?.takeIf { it.isNotEmpty() },
                    headTexture = plugin.config.getString("bottle.visual-item.head-texture")?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }

        private fun readSound(plugin: JavaPlugin, path: String, fallback: Sound): Sound {
            val raw = plugin.config.getString(path)?.uppercase() ?: return fallback
            @Suppress("DEPRECATION")
            return runCatching { Sound.valueOf(raw) }.getOrDefault(fallback)
        }

        private fun readMaterial(plugin: JavaPlugin, path: String, fallback: Material): Material {
            val raw = plugin.config.getString(path)?.uppercase() ?: return fallback
            return runCatching { Material.valueOf(raw) }.getOrDefault(fallback)
        }
    }
}

data class BottleVisualItemConfig(
    val material: Material,
    val headOwner: String?,
    val headTexture: String?,
)
