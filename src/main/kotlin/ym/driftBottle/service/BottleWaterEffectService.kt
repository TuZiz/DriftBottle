package ym.driftBottle.service

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.wrappers.EnumWrappers
import com.comphenix.protocol.wrappers.Pair
import com.comphenix.protocol.wrappers.Vector3F
import com.comphenix.protocol.wrappers.WrappedChatComponent
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import ym.driftBottle.config.BottleConfig
import ym.driftBottle.util.HeadTextureSupport
import ym.driftBottle.util.TextColorizer
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class BottleWaterEffectService(
    private val plugin: JavaPlugin,
    private val bottleConfigProvider: () -> BottleConfig,
) {

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

    fun playThrowEffect(
        player: Player,
        target: WaterTarget,
        labelContext: BottleVisualLabelContext,
        onComplete: () -> Unit,
    ) {
        if (!player.isOnline) {
            onComplete()
            return
        }
        val start = player.eyeLocation.clone()
            .add(player.location.direction.normalize().multiply(0.8))
            .add(0.0, -0.35, 0.0)
        val end = target.surfaceLocation(player.world).add(0.0, 0.1, 0.0)
        playEffect(
            player = player,
            start = start,
            end = end,
            labelContext = labelContext,
            downward = true,
            trailParticle = Particle.WATER_WAKE,
            finishParticle = Particle.WATER_SPLASH,
            finishSound = Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED,
        ) { visual ->
            destroyVisual(player, visual)
            onComplete()
        }
    }

    fun hasPendingSalvagePickup(playerUuid: UUID): Boolean {
        return pendingSalvagePickups.containsKey(playerUuid)
    }

    fun clearPendingSalvagePickup(playerUuid: UUID) {
        val session = pendingSalvagePickups.remove(playerUuid) ?: return
        session.hoverTask?.cancel()
        session.timeoutTask?.cancel()
        plugin.server.getPlayer(playerUuid)?.takeIf(Player::isOnline)?.let { viewer ->
            session.visual?.let { destroyVisual(viewer, it) }
        }
    }

    fun clearAllPendingSalvagePickups() {
        pendingSalvagePickups.keys.toList().forEach(::clearPendingSalvagePickup)
    }

    fun startSalvagePickup(
        player: Player,
        target: WaterTarget,
        bottleId: Long,
        labelContext: BottleVisualLabelContext,
        onReady: (Player, Long) -> Unit,
        onPicked: (Player, Long) -> Unit,
        onExpired: (Player, Long) -> Unit,
    ): Boolean {
        val session = SalvagePickupSession(
            bottleId = bottleId,
            visual = null,
            hoverTask = null,
            timeoutTask = null,
            onPicked = onPicked,
            onExpired = onExpired,
        )
        if (pendingSalvagePickups.putIfAbsent(player.uniqueId, session) != null) {
            return false
        }
        val landing = salvageLandingLocation(player)
        playSalvageEffect(player, target, landing, labelContext) { visual ->
            val current = pendingSalvagePickups[player.uniqueId]
            if (current == null || current.bottleId != bottleId || !player.isOnline) {
                destroyVisual(player, visual)
                return@playSalvageEffect
            }
            val tasks = beginPendingPickupHover(player, bottleId, landing, visual)
            pendingSalvagePickups[player.uniqueId] = current.copy(
                visual = visual,
                hoverTask = tasks.hoverTask,
                timeoutTask = tasks.timeoutTask,
            )
            onReady(player, bottleId)
        }
        return true
    }

    private fun playSalvageEffect(
        player: Player,
        target: WaterTarget,
        landing: Location,
        labelContext: BottleVisualLabelContext,
        onComplete: (PacketVisual) -> Unit,
    ) {
        if (!player.isOnline) {
            return
        }
        val start = target.surfaceLocation(player.world).add(0.0, 0.15, 0.0)
        playEffect(
            player = player,
            start = start,
            end = landing,
            labelContext = labelContext,
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
        labelContext: BottleVisualLabelContext,
        downward: Boolean,
        trailParticle: Particle,
        finishParticle: Particle,
        finishSound: Sound,
        onComplete: (PacketVisual) -> Unit,
    ) {
        val config = bottleConfigProvider()
        val visual = spawnVisual(start, player, labelContext)
        val hoverTicks = config.visualHoverTicks
        val flightTicks = config.visualFlightTicks
        val totalTicks = hoverTicks + flightTicks
        val taskHolder = arrayOfNulls<BukkitTask>(1)
        var tick = 0L
        taskHolder[0] = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (!player.isOnline) {
                taskHolder[0]?.cancel()
                destroyVisual(player, visual)
                return@Runnable
            }
            if (tick < hoverTicks) {
                val hoverLocation = start.clone().add(0.0, sin(tick / 4.0) * 0.03, 0.0)
                updateFlightVisual(player, visual, hoverLocation, tick.toDouble(), downward, 0.0)
                player.spawnParticle(Particle.ENCHANTMENT_TABLE, hoverLocation, 1, 0.05, 0.05, 0.05, 0.0)
                tick++
                return@Runnable
            }

            val flightProgress = ((tick - hoverTicks).toDouble() / flightTicks.toDouble()).coerceIn(0.0, 1.0)
            val current = interpolateArc(start, end, flightProgress, downward)
            updateFlightVisual(player, visual, current, tick.toDouble(), downward, flightProgress)
            player.spawnParticle(trailParticle, current, 3, 0.08, 0.08, 0.08, 0.0)

            if (tick >= totalTicks) {
                taskHolder[0]?.cancel()
                player.spawnParticle(finishParticle, current, 8, 0.18, 0.18, 0.18, 0.01)
                player.playSound(player.location, finishSound, 0.8f, 1.1f)
                onComplete(visual)
                return@Runnable
            }
            tick++
        }, 0L, 1L)
    }

    private fun beginPendingPickupHover(
        player: Player,
        bottleId: Long,
        baseLocation: Location,
        visual: PacketVisual,
    ): PendingPickupTasks {
        val taskHolder = arrayOfNulls<BukkitTask>(1)
        var tick = 0L
        taskHolder[0] = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val current = pendingSalvagePickups[player.uniqueId]
            if (current == null || current.bottleId != bottleId) {
                taskHolder[0]?.cancel()
                return@Runnable
            }
            if (!player.isOnline || player.world.uid != requireNotNull(baseLocation.world).uid) {
                taskHolder[0]?.cancel()
                expirePendingPickup(player.uniqueId)
                return@Runnable
            }
            val hoverLocation = baseLocation.clone().add(0.0, sin(tick / 5.0) * 0.05, 0.0)
            updateIdleVisual(player, visual, hoverLocation, tick.toDouble())
            if (player.location.distanceSquared(baseLocation) <= bottleConfigProvider().pickupRadius * bottleConfigProvider().pickupRadius) {
                taskHolder[0]?.cancel()
                completePendingPickup(player.uniqueId)
                return@Runnable
            }
            tick++
        }, 0L, 1L)

        val timeoutTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            expirePendingPickup(player.uniqueId)
        }, bottleConfigProvider().salvagePickupWaitTicks)

        return PendingPickupTasks(
            hoverTask = taskHolder[0],
            timeoutTask = timeoutTask,
        )
    }

    private fun completePendingPickup(playerUuid: UUID) {
        val session = pendingSalvagePickups.remove(playerUuid) ?: return
        session.hoverTask?.cancel()
        session.timeoutTask?.cancel()
        val player = plugin.server.getPlayer(playerUuid) ?: return
        session.visual?.let { destroyVisual(player, it) }
        session.onPicked(player, session.bottleId)
    }

    private fun expirePendingPickup(playerUuid: UUID) {
        val session = pendingSalvagePickups.remove(playerUuid) ?: return
        session.hoverTask?.cancel()
        session.timeoutTask?.cancel()
        val player = plugin.server.getPlayer(playerUuid) ?: return
        session.visual?.let { destroyVisual(player, it) }
        session.onExpired(player, session.bottleId)
    }

    private fun spawnVisual(location: Location, viewer: Player, labelContext: BottleVisualLabelContext): PacketVisual {
        val visualItem = buildVisualItem(bottleConfigProvider())
        val head = PacketVisualHead(
            entityId = visualEntityIdSequence.getAndDecrement(),
            uuid = UUID.randomUUID(),
            visualItem = visualItem,
            currentLocation = location.clone(),
        )
        sendPacket(viewer, createSpawnPacket(head.entityId, head.uuid, location, 0.0))
        sendPacket(viewer, createEquipmentPacket(head.entityId, head.visualItem))
        sendPacket(viewer, createHeadStaticMetadataPacket(head.entityId))
        sendPacket(viewer, createHeadPoseMetadataPacket(head.entityId, 0.0, 0.0))

        val hologramText = resolveHologramText(labelContext)
        val hologram = if (hologramText != null) {
            PacketVisualHologram(
                entityId = visualEntityIdSequence.getAndDecrement(),
                uuid = UUID.randomUUID(),
                currentLocation = hologramLocation(location),
                text = hologramText,
            ).also { hologramEntity ->
                sendPacket(viewer, createSpawnPacket(hologramEntity.entityId, hologramEntity.uuid, hologramEntity.currentLocation, 0.0))
                sendPacket(viewer, createHologramMetadataPacket(hologramEntity.entityId, hologramEntity.text))
            }
        } else {
            null
        }
        return PacketVisual(head = head, hologram = hologram)
    }

    private fun updateFlightVisual(
        viewer: Player,
        visual: PacketVisual,
        headLocation: Location,
        tick: Double,
        downward: Boolean,
        progress: Double,
    ) {
        val yawDegrees = (tick * 18.0) % 360.0
        val pitchRadians = if (downward) progress * (PI / 2.0) else -(1.0 - progress) * (PI / 2.5)
        createRelativeMoveLookPacket(visual.head, headLocation, yawDegrees)?.let { sendPacket(viewer, it) }
        sendPacket(
            viewer,
            createHeadPoseMetadataPacket(
                visual.head.entityId,
                Math.toDegrees(pitchRadians),
                yawDegrees,
            ),
        )
        visual.head.currentLocation = headLocation.clone()
        visual.hologram?.let { hologram ->
            val nextLocation = hologramLocation(headLocation)
            createRelativeMovePacket(hologram, nextLocation)?.let { sendPacket(viewer, it) }
            hologram.currentLocation = nextLocation
        }
    }

    private fun updateIdleVisual(
        viewer: Player,
        visual: PacketVisual,
        headLocation: Location,
        tick: Double,
    ) {
        val yawDegrees = (tick * 14.0) % 360.0
        createRelativeMoveLookPacket(visual.head, headLocation, yawDegrees)?.let { sendPacket(viewer, it) }
        sendPacket(viewer, createHeadPoseMetadataPacket(visual.head.entityId, 0.0, yawDegrees))
        visual.head.currentLocation = headLocation.clone()
        visual.hologram?.let { hologram ->
            val nextLocation = hologramLocation(headLocation)
            createRelativeMovePacket(hologram, nextLocation)?.let { sendPacket(viewer, it) }
            hologram.currentLocation = nextLocation
        }
    }

    private fun destroyVisual(viewer: Player, visual: PacketVisual) {
        val ids = buildList {
            add(visual.head.entityId)
            visual.hologram?.let { add(it.entityId) }
        }
        sendPacket(viewer, createDestroyPacket(ids))
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

    private fun resolveHologramText(labelContext: BottleVisualLabelContext): String? {
        val hologramConfig = bottleConfigProvider().hologram
        if (!hologramConfig.enabled) {
            return null
        }
        val rendered = hologramConfig.text
            .replace("%owner_alias%", labelContext.ownerAlias)
            .replace("%alias%", labelContext.ownerAlias)
            .replace("%anonymous_code%", labelContext.anonymousCode)
            .trim()
        return rendered.takeIf { it.isNotEmpty() }?.let(TextColorizer::color)
    }

    private fun hologramLocation(headLocation: Location): Location {
        return headLocation.clone().add(0.0, bottleConfigProvider().hologram.yOffset, 0.0)
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

    private fun interpolateArc(start: Location, end: Location, progress: Double, downward: Boolean): Location {
        val vector = start.toVector().multiply(1.0 - progress).add(end.toVector().multiply(progress))
        val arc = sin(progress * PI) * 0.55
        val bias = if (downward) (1.0 - progress) * 0.15 else progress * 0.2
        return Location(start.world, vector.x, vector.y + arc + bias, vector.z)
    }

    private fun createSpawnPacket(entityId: Int, uuid: UUID, location: Location, yawDegrees: Double): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.SPAWN_ENTITY)
        packet.modifier.writeDefaults()
        packet.integers.write(0, entityId)
        packet.integers.writeSafely(6, 0)
        packet.uuiDs.write(0, uuid)
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
        packet.slotStackPairLists.write(0, listOf(Pair(EnumWrappers.ItemSlot.HEAD, visualItem)))
        return packet
    }

    private fun createHeadStaticMetadataPacket(entityId: Int): PacketContainer {
        val watcher = WrappedDataWatcher()
        watcher.setByte(0, ENTITY_FLAG_INVISIBLE, true)
        watcher.setBoolean(5, true, true)
        watcher.setByte(15, ARMOR_STAND_FLAG_SMALL_MARKER, true)
        return createMetadataPacket(entityId, watcher)
    }

    private fun createHeadPoseMetadataPacket(entityId: Int, pitchDegrees: Double, yawDegrees: Double): PacketContainer {
        val watcher = WrappedDataWatcher()
        watcher.setVector3F(16, Vector3F(pitchDegrees.toFloat(), yawDegrees.toFloat(), 0.0f), true)
        return createMetadataPacket(entityId, watcher)
    }

    private fun createHologramMetadataPacket(entityId: Int, text: String): PacketContainer {
        val watcher = WrappedDataWatcher()
        watcher.setByte(0, ENTITY_FLAG_INVISIBLE, true)
        watcher.setOptionalChatComponent(2, Optional.of(WrappedChatComponent.fromLegacyText(text)), true)
        watcher.setBoolean(3, true, true)
        watcher.setBoolean(5, true, true)
        watcher.setByte(15, ARMOR_STAND_FLAG_MARKER, true)
        return createMetadataPacket(entityId, watcher)
    }

    private fun createMetadataPacket(entityId: Int, watcher: WrappedDataWatcher): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_METADATA)
        packet.modifier.writeDefaults()
        packet.integers.write(0, entityId)
        packet.dataValueCollectionModifier.write(0, watcher.toDataValueCollection())
        return packet
    }

    private fun createRelativeMoveLookPacket(
        head: PacketVisualHead,
        nextLocation: Location,
        yawDegrees: Double,
    ): PacketContainer? {
        val packet = PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK)
        packet.modifier.writeDefaults()
        if (packet.integers.size() <= 0 || packet.shorts.size() < 3) {
            return null
        }
        packet.integers.write(0, head.entityId)
        packet.shorts.write(0, encodeRelativeDelta(nextLocation.x - head.currentLocation.x))
        packet.shorts.write(1, encodeRelativeDelta(nextLocation.y - head.currentLocation.y))
        packet.shorts.write(2, encodeRelativeDelta(nextLocation.z - head.currentLocation.z))
        if (packet.bytes.size() >= 2) {
            packet.bytes.write(0, angleToByte(yawDegrees))
            packet.bytes.write(1, angleToByte(0.0))
        }
        if (packet.booleans.size() >= 1) {
            packet.booleans.write(0, false)
        }
        return packet
    }

    private fun createRelativeMovePacket(
        hologram: PacketVisualHologram,
        nextLocation: Location,
    ): PacketContainer? {
        val packet = PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE)
        packet.modifier.writeDefaults()
        if (packet.integers.size() <= 0 || packet.shorts.size() < 3) {
            return null
        }
        packet.integers.write(0, hologram.entityId)
        packet.shorts.write(0, encodeRelativeDelta(nextLocation.x - hologram.currentLocation.x))
        packet.shorts.write(1, encodeRelativeDelta(nextLocation.y - hologram.currentLocation.y))
        packet.shorts.write(2, encodeRelativeDelta(nextLocation.z - hologram.currentLocation.z))
        if (packet.booleans.size() >= 1) {
            packet.booleans.write(0, false)
        }
        return packet
    }

    private fun createDestroyPacket(entityIds: List<Int>): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_DESTROY)
        packet.modifier.writeDefaults()
        if (packet.integerArrays.size() > 0) {
            packet.integerArrays.write(0, entityIds.toIntArray())
        } else {
            packet.intLists.write(0, entityIds)
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

    private fun encodeRelativeDelta(delta: Double): Short {
        return (delta * RELATIVE_MOVE_SCALE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
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

    data class BottleVisualLabelContext(
        val ownerAlias: String,
        val anonymousCode: String,
    )

    private data class WaterCandidateSnapshot(
        val x: Int,
        val y: Int,
        val z: Int,
        val depth: Int,
        val distanceSquared: Double,
    )

    private data class PendingPickupTasks(
        val hoverTask: BukkitTask?,
        val timeoutTask: BukkitTask?,
    )

    private data class SalvagePickupSession(
        val bottleId: Long,
        val visual: PacketVisual?,
        val hoverTask: BukkitTask?,
        val timeoutTask: BukkitTask?,
        val onPicked: (Player, Long) -> Unit,
        val onExpired: (Player, Long) -> Unit,
    )

    private data class PacketVisual(
        val head: PacketVisualHead,
        val hologram: PacketVisualHologram?,
    )

    private data class PacketVisualHead(
        val entityId: Int,
        val uuid: UUID,
        val visualItem: ItemStack,
        var currentLocation: Location,
    )

    private data class PacketVisualHologram(
        val entityId: Int,
        val uuid: UUID,
        var currentLocation: Location,
        val text: String,
    )

    private companion object {
        const val ENTITY_FLAG_INVISIBLE: Byte = 0x20
        const val ARMOR_STAND_FLAG_SMALL_MARKER: Byte = 0x11
        const val ARMOR_STAND_FLAG_MARKER: Byte = 0x10
        const val RELATIVE_MOVE_SCALE = 4096.0
    }
}
