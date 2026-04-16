package ym.driftBottle.service

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.wrappers.EnumWrappers
import com.comphenix.protocol.wrappers.Pair
import com.comphenix.protocol.wrappers.Vector3F
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import org.bukkit.Location
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import ym.driftBottle.config.BottleConfig
import ym.driftBottle.util.HeadTextureSupport
import kotlin.math.PI
import kotlin.math.sin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BottleWaterEffectService(
    private val plugin: JavaPlugin,
    private val bottleConfigProvider: () -> BottleConfig,
) {

    private val pickupOwnerKey = NamespacedKey(plugin, "salvage_pickup_owner")
    private val pickupBottleIdKey = NamespacedKey(plugin, "salvage_pickup_bottle_id")
    private val pendingSalvagePickups = ConcurrentHashMap<UUID, SalvagePickupSession>()
    private val visualEntityIdSequence = AtomicInteger(-1)

    fun findNearestWaterTargetAsync(player: Player, callback: (WaterTarget?) -> Unit) {
        if (!player.isOnline) {
            callback(null)
            return
        }
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!player.isOnline) {
                callback(null)
                return@Runnable
            }
            val config = bottleConfigProvider()
            val origin = player.location.clone()
            val world = player.world
            val candidates = captureWaterCandidates(world, origin, config)
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                val selected = candidates.minByOrNull { it.distanceSquared }
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (!player.isOnline || player.world.uid != world.uid) {
                        callback(null)
                        return@Runnable
                    }
                    callback(selected?.let { WaterTarget(it.x, it.y, it.z, it.depth) })
                })
            })
        })
    }

    fun playThrowEffect(player: Player, target: WaterTarget, onComplete: () -> Unit) {
        if (!player.isOnline) {
            onComplete()
            return
        }
        val start = player.eyeLocation.clone().add(player.location.direction.normalize().multiply(0.8)).add(0.0, -0.35, 0.0)
        val end = target.surfaceLocation(player.world).add(0.0, 0.1, 0.0)
        playEffect(
            player = player,
            start = start,
            end = end,
            downward = true,
            trailParticle = Particle.WATER_WAKE,
            finishParticle = Particle.WATER_SPLASH,
            finishSound = Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED,
            onComplete = onComplete,
        )
    }

    fun playSalvageEffect(player: Player, target: WaterTarget, onComplete: () -> Unit) {
        playSalvageEffect(player, target, salvageLandingLocation(player), onComplete)
    }

    fun hasPendingSalvagePickup(playerUuid: UUID): Boolean {
        return pendingSalvagePickups.containsKey(playerUuid)
    }

    fun clearPendingSalvagePickup(playerUuid: UUID) {
        val session = pendingSalvagePickups.remove(playerUuid) ?: return
        session.timeoutTask?.cancel()
        session.itemUuid?.let(::removePickupItem)
    }

    fun clearAllPendingSalvagePickups() {
        pendingSalvagePickups.keys.toList().forEach(::clearPendingSalvagePickup)
    }

    fun startSalvagePickup(
        player: Player,
        target: WaterTarget,
        bottleId: Long,
        onReady: (Player, Long) -> Unit,
        onPicked: (Player, Long) -> Unit,
        onExpired: (Player, Long) -> Unit,
    ): Boolean {
        val session = SalvagePickupSession(
            bottleId = bottleId,
            itemUuid = null,
            timeoutTask = null,
            onPicked = onPicked,
            onExpired = onExpired,
        )
        if (pendingSalvagePickups.putIfAbsent(player.uniqueId, session) != null) {
            return false
        }
        val landing = salvageLandingLocation(player)
        playSalvageEffect(player, target, landing) {
            val current = pendingSalvagePickups[player.uniqueId]
            if (current == null || current.bottleId != bottleId || !player.isOnline) {
                clearPendingSalvagePickup(player.uniqueId)
                return@playSalvageEffect
            }
            val pickupItem = spawnPickupItem(player, bottleId, landing) ?: run {
                pendingSalvagePickups.remove(player.uniqueId)
                current.onExpired(player, bottleId)
                return@playSalvageEffect
            }
            val timeoutTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val timedOut = pendingSalvagePickups[player.uniqueId]
                if (timedOut == null || timedOut.bottleId != bottleId || timedOut.itemUuid != pickupItem.uniqueId) {
                    return@Runnable
                }
                pendingSalvagePickups.remove(player.uniqueId)
                if (pickupItem.isValid) {
                    pickupItem.remove()
                }
                timedOut.onExpired(player, bottleId)
            }, bottleConfigProvider().salvagePickupWaitTicks)
            pendingSalvagePickups[player.uniqueId] = current.copy(
                itemUuid = pickupItem.uniqueId,
                timeoutTask = timeoutTask,
            )
            onReady(player, bottleId)
        }
        return true
    }

    fun handleItemPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val ownerUuid = event.item.persistentDataContainer.get(pickupOwnerKey, PersistentDataType.STRING) ?: return
        val bottleId = event.item.persistentDataContainer.get(pickupBottleIdKey, PersistentDataType.LONG) ?: return
        event.isCancelled = true
        if (ownerUuid != player.uniqueId.toString()) {
            return
        }
        val session = pendingSalvagePickups[player.uniqueId] ?: run {
            if (event.item.isValid) {
                event.item.remove()
            }
            return
        }
        if (session.bottleId != bottleId || (session.itemUuid != null && session.itemUuid != event.item.uniqueId)) {
            return
        }
        session.timeoutTask?.cancel()
        pendingSalvagePickups.remove(player.uniqueId)
        if (event.item.isValid) {
            event.item.remove()
        }
        session.onPicked(player, bottleId)
    }

    private fun playSalvageEffect(player: Player, target: WaterTarget, landing: Location, onComplete: () -> Unit) {
        if (!player.isOnline) {
            onComplete()
            return
        }
        val start = target.surfaceLocation(player.world).add(0.0, 0.15, 0.0)
        playEffect(
            player = player,
            start = start,
            end = landing,
            downward = false,
            trailParticle = Particle.BUBBLE_COLUMN_UP,
            finishParticle = Particle.CLOUD,
            finishSound = Sound.ENTITY_ITEM_PICKUP,
            onComplete = onComplete,
        )
    }

    private fun playEffect(
        player: Player,
        start: Location,
        end: Location,
        downward: Boolean,
        trailParticle: Particle,
        finishParticle: Particle,
        finishSound: Sound,
        onComplete: () -> Unit,
    ) {
        val config = bottleConfigProvider()
        val visualEntity = spawnVisualHead(start, player)
        val hoverTicks = config.visualHoverTicks
        val flightTicks = config.visualFlightTicks
        val totalTicks = hoverTicks + flightTicks
        val taskHolder = arrayOfNulls<org.bukkit.scheduler.BukkitTask>(1)
        var tick = 0L
        taskHolder[0] = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (!player.isOnline) {
                taskHolder[0]?.cancel()
                destroyVisualHead(player, visualEntity)
                onComplete()
                return@Runnable
            }
            if (tick < hoverTicks) {
                val hoverLocation = start.clone().add(0.0, sin((tick / 4.0)) * 0.03, 0.0)
                updateVisualHead(player, visualEntity, hoverLocation, tick.toDouble(), downward, 0.0)
                player.spawnParticle(Particle.ENCHANTMENT_TABLE, hoverLocation, 1, 0.05, 0.05, 0.05, 0.0)
                tick++
                return@Runnable
            }

            val flightProgress = ((tick - hoverTicks).toDouble() / flightTicks.toDouble()).coerceIn(0.0, 1.0)
            val current = interpolateArc(start, end, flightProgress, downward)
            updateVisualHead(player, visualEntity, current, tick.toDouble(), downward, flightProgress)
            player.spawnParticle(trailParticle, current, 3, 0.08, 0.08, 0.08, 0.0)

            if (tick >= totalTicks) {
                taskHolder[0]?.cancel()
                player.spawnParticle(finishParticle, current, 8, 0.18, 0.18, 0.18, 0.01)
                player.playSound(player.location, finishSound, 0.8f, 1.1f)
                destroyVisualHead(player, visualEntity)
                onComplete()
                return@Runnable
            }
            tick++
        }, 0L, 1L)
    }

    private fun spawnVisualHead(location: Location, viewer: Player): PacketVisualHead {
        val visualItem = buildVisualItem(bottleConfigProvider())
        val visualHead = PacketVisualHead(
            entityId = visualEntityIdSequence.getAndDecrement(),
            uuid = UUID.randomUUID(),
            visualItem = visualItem,
        )
        sendPacket(viewer, createSpawnPacket(visualHead, location, 0.0))
        sendPacket(viewer, createEquipmentPacket(visualHead.entityId, visualHead.visualItem))
        sendPacket(viewer, createStaticMetadataPacket(visualHead.entityId))
        sendPacket(viewer, createPoseMetadataPacket(visualHead.entityId, 0.0, 0.0))
        return visualHead
    }

    private fun spawnPickupItem(player: Player, bottleId: Long, location: Location): Item? {
        if (!player.isOnline) {
            return null
        }
        val item = player.world.dropItem(location, buildVisualItem(bottleConfigProvider()))
        item.setOwner(player.uniqueId)
        item.pickupDelay = 10
        item.setGravity(false)
        item.velocity = Vector(0.0, 0.0, 0.0)
        item.persistentDataContainer.set(pickupOwnerKey, PersistentDataType.STRING, player.uniqueId.toString())
        item.persistentDataContainer.set(pickupBottleIdKey, PersistentDataType.LONG, bottleId)
        return item
    }

    private fun removePickupItem(itemUuid: UUID) {
        plugin.server.worlds.asSequence()
            .flatMap { it.entities.asSequence() }
            .filterIsInstance<Item>()
            .firstOrNull { it.uniqueId == itemUuid }
            ?.remove()
    }

    private fun buildVisualItem(config: BottleConfig): ItemStack {
        val visual = config.visualItem
        val item = ItemStack(visual.material)
        if (visual.material != Material.PLAYER_HEAD) {
            return item
        }
        val meta = item.itemMeta as? SkullMeta ?: return item
        when {
            !visual.headTexture.isNullOrBlank() -> HeadTextureSupport.applyTexture(meta, visual.headTexture)
            !visual.headOwner.isNullOrBlank() -> {
                @Suppress("DEPRECATION")
                meta.owningPlayer = Bukkit.getOfflinePlayer(visual.headOwner)
            }
        }
        item.itemMeta = meta
        return item
    }

    private fun salvageLandingLocation(player: Player): Location {
        val forward = player.location.direction.clone().setY(0.0).normalize().takeIf { it.lengthSquared() > 0.0 }
            ?: Vector(0.0, 0.0, 1.0)
        val side = Vector(0.0, 1.0, 0.0).crossProduct(forward).normalize()
        return player.location.clone()
            .add(forward.multiply(0.75))
            .add(side.multiply(1.35))
            .add(0.0, 0.2, 0.0)
    }

    private fun updateVisualHead(
        viewer: Player,
        visualHead: PacketVisualHead,
        location: Location,
        tick: Double,
        downward: Boolean,
        progress: Double,
    ) {
        val yawDegrees = (tick * 18.0) % 360.0
        val pitchRadians = if (downward) {
            progress * (PI / 2.0)
        } else {
            -(1.0 - progress) * (PI / 2.5)
        }
        sendPacket(viewer, createDestroyPacket(visualHead.entityId))
        sendPacket(viewer, createSpawnPacket(visualHead, location, yawDegrees))
        sendPacket(viewer, createEquipmentPacket(visualHead.entityId, visualHead.visualItem))
        sendPacket(viewer, createStaticMetadataPacket(visualHead.entityId))
        sendPacket(
            viewer,
            createPoseMetadataPacket(
                visualHead.entityId,
                Math.toDegrees(pitchRadians),
                yawDegrees,
            ),
        )
    }

    private fun destroyVisualHead(viewer: Player, visualHead: PacketVisualHead) {
        sendPacket(viewer, createDestroyPacket(visualHead.entityId))
    }

    private fun interpolateArc(start: Location, end: Location, progress: Double, downward: Boolean): Location {
        val vector = start.toVector().multiply(1.0 - progress).add(end.toVector().multiply(progress))
        val arc = sin(progress * PI) * 0.55
        val bias = if (downward) (1.0 - progress) * 0.15 else progress * 0.2
        return Location(
            start.world,
            vector.x,
            vector.y + arc + bias,
            vector.z,
        )
    }

    private fun createSpawnPacket(visualHead: PacketVisualHead, location: Location, yawDegrees: Double): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.SPAWN_ENTITY)
        packet.modifier.writeDefaults()
        packet.integers.write(0, visualHead.entityId)
        packet.integers.writeSafely(6, 0)
        packet.uuiDs.write(0, visualHead.uuid)
        packet.entityTypeModifier.write(0, EntityType.ARMOR_STAND)
        packet.doubles.write(0, location.x)
        packet.doubles.write(1, location.y)
        packet.doubles.write(2, location.z)
        packet.bytes.writeSafely(0, angleToByte(yawDegrees))
        packet.bytes.writeSafely(1, angleToByte(0.0))
        packet.bytes.writeSafely(2, angleToByte(0.0))
        return packet
    }

    private fun createEquipmentPacket(entityId: Int, visualItem: ItemStack): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT)
        packet.modifier.writeDefaults()
        packet.integers.write(0, entityId)
        packet.slotStackPairLists.write(
            0,
            listOf(Pair(EnumWrappers.ItemSlot.HEAD, visualItem)),
        )
        return packet
    }

    private fun createStaticMetadataPacket(entityId: Int): PacketContainer {
        val watcher = WrappedDataWatcher()
        watcher.setByte(0, ENTITY_FLAG_INVISIBLE, true)
        watcher.setBoolean(5, true, true)
        watcher.setByte(15, ARMOR_STAND_FLAG_SMALL_MARKER, true)
        return createMetadataPacket(entityId, watcher)
    }

    private fun createPoseMetadataPacket(entityId: Int, pitchDegrees: Double, yawDegrees: Double): PacketContainer {
        val watcher = WrappedDataWatcher()
        watcher.setVector3F(16, Vector3F(pitchDegrees.toFloat(), yawDegrees.toFloat(), 0.0f), true)
        return createMetadataPacket(entityId, watcher)
    }

    private fun createMetadataPacket(entityId: Int, watcher: WrappedDataWatcher): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_METADATA)
        packet.modifier.writeDefaults()
        packet.integers.write(0, entityId)
        packet.dataValueCollectionModifier.write(0, watcher.toDataValueCollection())
        return packet
    }

    private fun createDestroyPacket(entityId: Int): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_DESTROY)
        packet.modifier.writeDefaults()
        if (packet.integerArrays.size() > 0) {
            packet.integerArrays.write(0, intArrayOf(entityId))
        } else {
            packet.intLists.write(0, listOf(entityId))
        }
        return packet
    }

    private fun sendPacket(viewer: Player, packet: PacketContainer) {
        runCatching {
            ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, packet, false)
        }.onFailure {
            if (viewer.isOnline) {
                plugin.logger.warning("Failed to send bottle visual packet to ${viewer.name}: ${it.message}")
            }
        }
    }

    private fun angleToByte(angle: Double): Byte {
        return ((angle % 360.0) * 256.0 / 360.0).toInt().toByte()
    }

    private fun captureWaterCandidates(world: World, origin: Location, config: BottleConfig): List<WaterCandidateSnapshot> {
        val baseX = origin.blockX
        val baseY = origin.blockY
        val baseZ = origin.blockZ
        val radius = config.waterSearchRadius
        val minDepth = config.waterMinDepth
        val snapshots = mutableListOf<WaterCandidateSnapshot>()
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                for (y in (baseY + 2) downTo (baseY - 6)) {
                    val block = world.getBlockAt(baseX + dx, y, baseZ + dz)
                    if (block.type != Material.WATER) {
                        continue
                    }
                    if (block.getRelative(0, 1, 0).type == Material.WATER) {
                        continue
                    }
                    val depth = measureDepth(world, block.x, block.y, block.z, minDepth)
                    if (depth < minDepth) {
                        continue
                    }
                    val center = Vector(block.x + 0.5, block.y + 0.5, block.z + 0.5)
                    snapshots += WaterCandidateSnapshot(
                        x = block.x,
                        y = block.y,
                        z = block.z,
                        depth = depth,
                        distanceSquared = center.distanceSquared(origin.toVector()),
                    )
                }
            }
        }
        return snapshots
    }

    private fun measureDepth(world: World, x: Int, y: Int, z: Int, minDepth: Int): Int {
        var depth = 0
        var currentY = y
        while (depth < 32) {
            val type = world.getBlockAt(x, currentY, z).type
            if (type != Material.WATER) {
                break
            }
            depth++
            currentY--
            if (depth >= minDepth && world.getBlockAt(x, currentY, z).type != Material.WATER) {
                break
            }
        }
        return depth
    }

    data class WaterTarget(
        val x: Int,
        val y: Int,
        val z: Int,
        val depth: Int,
    ) {
        fun surfaceLocation(world: World): Location = Location(world, x + 0.5, y + 0.5, z + 0.5)
    }

    private data class WaterCandidateSnapshot(
        val x: Int,
        val y: Int,
        val z: Int,
        val depth: Int,
        val distanceSquared: Double,
    )

    private data class SalvagePickupSession(
        val bottleId: Long,
        val itemUuid: UUID?,
        val timeoutTask: org.bukkit.scheduler.BukkitTask?,
        val onPicked: (Player, Long) -> Unit,
        val onExpired: (Player, Long) -> Unit,
    )

    private data class PacketVisualHead(
        val entityId: Int,
        val uuid: UUID,
        val visualItem: ItemStack,
    )

    private companion object {
        const val ENTITY_FLAG_INVISIBLE: Byte = 0x20
        const val ARMOR_STAND_FLAG_SMALL_MARKER: Byte = 0x11
    }
}
