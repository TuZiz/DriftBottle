package ym.driftBottle.config

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.plugin.java.JavaPlugin

data class BottleConfig(
    val economy: BottleEconomyConfig,
    val limits: BottleLimitConfig,
    val sounds: BottleSoundConfig,
    val water: BottleWaterConfig,
    val animation: BottleAnimationConfig,
    val pickup: BottlePickupConfig,
    val visual: BottleVisualConfig,
) {
    val throwCost: Double
        get() = economy.throwCost
    val salvageReward: Double
        get() = economy.salvageReward
    val throwCooldownSeconds: Long
        get() = limits.throwCooldownSeconds
    val salvageCooldownSeconds: Long
        get() = limits.salvageCooldownSeconds
    val maxActiveBottlesPerPlayer: Int
        get() = limits.maxActiveBottlesPerPlayer
    val maxThreadReplies: Int
        get() = limits.maxThreadReplies
    val soundsEnabled: Boolean
        get() = sounds.enabled
    val throwSound: Sound
        get() = sounds.throwSound
    val salvageSound: Sound
        get() = sounds.salvageSound
    val replySound: Sound
        get() = sounds.replySound
    val waterSearchRadius: Int
        get() = water.searchRadius
    val waterMinDepth: Int
        get() = water.minDepth
    val visualHoverTicks: Long
        get() = animation.hoverTicks
    val visualFlightTicks: Long
        get() = animation.flightTicks
    val salvagePickupWaitTicks: Long
        get() = pickup.waitTicks
    val pickupRadius: Double
        get() = pickup.radius
    val visualItem: BottleVisualItemConfig
        get() = visual.item
    val hologram: BottleHologramConfig
        get() = visual.hologram

    companion object {
        fun load(plugin: JavaPlugin): BottleConfig {
            return BottleConfig(
                economy = BottleEconomyConfig(
                    throwCost = readDouble(plugin, "bottle.economy.throw-cost", "bottle.throw-cost", 5.0).coerceAtLeast(0.0),
                    salvageReward = readDouble(plugin, "bottle.economy.salvage-reward", "bottle.salvage-reward", 3.0).coerceAtLeast(0.0),
                ),
                limits = BottleLimitConfig(
                    throwCooldownSeconds = readLong(plugin, "bottle.limits.throw-cooldown-seconds", "bottle.throw-cooldown-seconds", 10L).coerceAtLeast(0L),
                    salvageCooldownSeconds = readLong(plugin, "bottle.limits.salvage-cooldown-seconds", "bottle.salvage-cooldown-seconds", 5L).coerceAtLeast(0L),
                    maxActiveBottlesPerPlayer = readInt(plugin, "bottle.limits.max-active-bottles-per-player", "bottle.max-active-bottles-per-player", 5).coerceAtLeast(1),
                    maxThreadReplies = readInt(plugin, "bottle.limits.max-thread-replies", "bottle.max-thread-replies", 6).coerceAtLeast(1),
                ),
                sounds = BottleSoundConfig(
                    enabled = readBoolean(plugin, "bottle.sounds.enabled", "bottle.sounds-enabled", true),
                    throwSound = readSound(plugin, "bottle.sounds.throw", "bottle.throw-sound", Sound.ITEM_BOTTLE_FILL),
                    salvageSound = readSound(plugin, "bottle.sounds.salvage", "bottle.salvage-sound", Sound.ENTITY_EXPERIENCE_ORB_PICKUP),
                    replySound = readSound(plugin, "bottle.sounds.reply", "bottle.reply-sound", Sound.BLOCK_NOTE_BLOCK_CHIME),
                ),
                water = BottleWaterConfig(
                    searchRadius = readInt(plugin, "bottle.water.search-radius", "bottle.water-search-radius", 5).coerceAtLeast(1),
                    minDepth = readInt(plugin, "bottle.water.min-depth", "bottle.water-min-depth", 4).coerceAtLeast(1),
                ),
                animation = BottleAnimationConfig(
                    hoverTicks = readLong(plugin, "bottle.animation.hover-ticks", "bottle.visual-hover-ticks", 24L).coerceAtLeast(0L),
                    flightTicks = readLong(plugin, "bottle.animation.flight-ticks", "bottle.visual-flight-ticks", 36L).coerceAtLeast(1L),
                ),
                pickup = BottlePickupConfig(
                    waitTicks = readLong(plugin, "bottle.pickup.wait-ticks", "bottle.salvage-pickup-wait-ticks", 200L).coerceAtLeast(20L),
                    radius = readDouble(plugin, "bottle.pickup.radius", null, 1.0).coerceAtLeast(0.2),
                ),
                visual = BottleVisualConfig(
                    item = BottleVisualItemConfig(
                        material = readMaterial(plugin, "bottle.visual.item.material", "bottle.visual-item.material", Material.PLAYER_HEAD),
                        headOwner = readTrimmedString(plugin, "bottle.visual.item.head-owner", "bottle.visual-item.head-owner"),
                        headTexture = readTrimmedString(plugin, "bottle.visual.item.head-texture", "bottle.visual-item.head-texture"),
                    ),
                    hologram = BottleHologramConfig(
                        enabled = readBoolean(plugin, "bottle.visual.hologram.enabled", null, true),
                        text = readString(plugin, "bottle.visual.hologram.text", null, "&#FFE066匿名用户编号: &f%owner_alias%"),
                        yOffset = readDouble(plugin, "bottle.visual.hologram.y-offset", null, 0.65),
                    ),
                ),
            )
        }

        private fun readBoolean(plugin: JavaPlugin, primary: String, legacy: String?, default: Boolean): Boolean {
            return when {
                plugin.config.contains(primary) -> plugin.config.getBoolean(primary, default)
                legacy != null && plugin.config.contains(legacy) -> plugin.config.getBoolean(legacy, default)
                else -> default
            }
        }

        private fun readInt(plugin: JavaPlugin, primary: String, legacy: String?, default: Int): Int {
            return when {
                plugin.config.contains(primary) -> plugin.config.getInt(primary, default)
                legacy != null && plugin.config.contains(legacy) -> plugin.config.getInt(legacy, default)
                else -> default
            }
        }

        private fun readLong(plugin: JavaPlugin, primary: String, legacy: String?, default: Long): Long {
            return when {
                plugin.config.contains(primary) -> plugin.config.getLong(primary, default)
                legacy != null && plugin.config.contains(legacy) -> plugin.config.getLong(legacy, default)
                else -> default
            }
        }

        private fun readDouble(plugin: JavaPlugin, primary: String, legacy: String?, default: Double): Double {
            return when {
                plugin.config.contains(primary) -> plugin.config.getDouble(primary, default)
                legacy != null && plugin.config.contains(legacy) -> plugin.config.getDouble(legacy, default)
                else -> default
            }
        }

        private fun readString(plugin: JavaPlugin, primary: String, legacy: String?, default: String): String {
            return when {
                plugin.config.contains(primary) -> plugin.config.getString(primary, default) ?: default
                legacy != null && plugin.config.contains(legacy) -> plugin.config.getString(legacy, default) ?: default
                else -> default
            }
        }

        private fun readTrimmedString(plugin: JavaPlugin, primary: String, legacy: String?): String? {
            val value = when {
                plugin.config.contains(primary) -> plugin.config.getString(primary)
                legacy != null && plugin.config.contains(legacy) -> plugin.config.getString(legacy)
                else -> null
            }
            return value?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun readSound(plugin: JavaPlugin, primary: String, legacy: String?, fallback: Sound): Sound {
            val raw = readTrimmedString(plugin, primary, legacy)?.uppercase() ?: return fallback
            @Suppress("DEPRECATION")
            return runCatching { Sound.valueOf(raw) }.getOrDefault(fallback)
        }

        private fun readMaterial(plugin: JavaPlugin, primary: String, legacy: String?, fallback: Material): Material {
            val raw = readTrimmedString(plugin, primary, legacy)?.uppercase() ?: return fallback
            return runCatching { Material.valueOf(raw) }.getOrDefault(fallback)
        }
    }
}

data class BottleEconomyConfig(
    val throwCost: Double,
    val salvageReward: Double,
)

data class BottleLimitConfig(
    val throwCooldownSeconds: Long,
    val salvageCooldownSeconds: Long,
    val maxActiveBottlesPerPlayer: Int,
    val maxThreadReplies: Int,
)

data class BottleSoundConfig(
    val enabled: Boolean,
    val throwSound: Sound,
    val salvageSound: Sound,
    val replySound: Sound,
)

data class BottleWaterConfig(
    val searchRadius: Int,
    val minDepth: Int,
)

data class BottleAnimationConfig(
    val hoverTicks: Long,
    val flightTicks: Long,
)

data class BottlePickupConfig(
    val waitTicks: Long,
    val radius: Double,
)

data class BottleVisualConfig(
    val item: BottleVisualItemConfig,
    val hologram: BottleHologramConfig,
)

data class BottleVisualItemConfig(
    val material: Material,
    val headOwner: String?,
    val headTexture: String?,
)

data class BottleHologramConfig(
    val enabled: Boolean,
    val text: String,
    val yOffset: Double,
)
