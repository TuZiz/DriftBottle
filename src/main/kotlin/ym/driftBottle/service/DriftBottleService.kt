package ym.driftBottle.service

import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Sound
import org.bukkit.entity.Player
import ym.driftBottle.config.BottleConfig
import ym.driftBottle.lang.LangService
import ym.driftBottle.model.ActionResult
import ym.driftBottle.model.BottleListEntry
import ym.driftBottle.model.BottleListScope
import ym.driftBottle.model.BottleStatus
import ym.driftBottle.model.BottleThreadView
import ym.driftBottle.model.DriftBottle
import ym.driftBottle.model.PagedResult
import ym.driftBottle.model.PlayerBottleSummary
import ym.driftBottle.repository.CurrencyLedgerRepository
import ym.driftBottle.repository.DriftBottleRepository
import ym.driftBottle.repository.PlayerProfileRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class DriftBottleService(
    private val bottleConfigProvider: () -> BottleConfig,
    private val lang: LangService,
    private val bottleRepository: DriftBottleRepository,
    private val blacklistService: BlacklistService,
    private val economyService: EconomyService,
    private val currencyLedgerRepository: CurrencyLedgerRepository,
    private val playerProfileRepository: PlayerProfileRepository,
) {

    private val lastThrowAt = ConcurrentHashMap<UUID, Long>()
    private val lastSalvageAt = ConcurrentHashMap<UUID, Long>()

    fun createBottle(player: Player, content: String): ActionResult<DriftBottle> {
        val config = bottleConfigProvider()
        val now = System.currentTimeMillis()
        playerProfileRepository.ensurePlayer(player.uniqueId, player.name)

        cooldownMessage(lastThrowAt[player.uniqueId], config.throwCooldownSeconds, now, "bottle.throw-cooldown")?.let {
            return ActionResult(false, it, null)
        }
        if (bottleRepository.countOpenBottlesByOwner(player.uniqueId) >= config.maxActiveBottlesPerPlayer) {
            return ActionResult(
                false,
                lang.format("bottle.throw-limit-reached", mapOf("limit" to config.maxActiveBottlesPerPlayer.toString())),
                null,
            )
        }
        if (economyService.isEnabled() && config.throwCost > 0.0) {
            if (!economyService.has(player, config.throwCost)) {
                return ActionResult(
                    false,
                    lang.format("bottle.throw-not-enough-money", mapOf("cost" to formatAmount(config.throwCost))),
                    null,
                )
            }
            if (!economyService.withdraw(player, config.throwCost)) {
                return ActionResult(false, lang.get("bottle.throw-charge-failed"), null)
            }
        }

        val bottle = bottleRepository.createBottle(player.uniqueId, aliasOf(player.uniqueId), content)
        lastThrowAt[player.uniqueId] = now
        if (economyService.isEnabled() && config.throwCost > 0.0) {
            currencyLedgerRepository.append(player.uniqueId.toString(), bottle.id, -config.throwCost, "bottle_throw_cost")
        }
        playConfiguredSound(player, config.throwSound)
        return ActionResult(true, lang.format("bottle.throw-success", mapOf("bottle_id" to bottle.id.toString())), bottle)
    }

    fun salvageBottle(player: Player): ActionResult<DriftBottle> {
        val prepared = findSalvageCandidate(player)
        val bottle = prepared.payload ?: return ActionResult(prepared.success, prepared.message, null)
        return completeSalvageBottlePickup(player, bottle.id)
    }

    fun findSalvageCandidate(player: Player): ActionResult<DriftBottle> {
        val config = bottleConfigProvider()
        val now = System.currentTimeMillis()
        playerProfileRepository.ensurePlayer(player.uniqueId, player.name)

        cooldownMessage(lastSalvageAt[player.uniqueId], config.salvageCooldownSeconds, now, "bottle.salvage-cooldown")?.let {
            return ActionResult(false, it, null)
        }

        val blocked = blacklistService.relatedBlockedUuids(player.uniqueId)
        val candidates = bottleRepository.listDriftingBottles()
            .filter { it.ownerUuid != player.uniqueId && it.ownerUuid !in blocked }
            .shuffled()

        val candidate = candidates.firstOrNull()
            ?: return ActionResult(false, lang.get("bottle.salvage-empty"), null)
        return ActionResult(true, lang.get("bottle.salvage-pickup-ready"), candidate)
    }

    fun completeSalvageBottlePickup(player: Player, bottleId: Long): ActionResult<DriftBottle> {
        val config = bottleConfigProvider()
        val bottle = bottleRepository.findBottle(bottleId)
            ?: return ActionResult(false, lang.get("bottle.salvage-pickup-lost"), null)
        if (bottle.ownerUuid == player.uniqueId) {
            return ActionResult(false, lang.get("bottle.salvage-pickup-lost"), null)
        }
        if (bottle.ownerUuid in blacklistService.relatedBlockedUuids(player.uniqueId)) {
            return ActionResult(false, lang.get("bottle.salvage-pickup-lost"), null)
        }
        if (!bottleRepository.markSalvaged(bottleId, player.uniqueId, aliasOf(player.uniqueId))) {
            return ActionResult(false, lang.get("bottle.salvage-pickup-lost"), null)
        }
        val updatedBottle = bottleRepository.findBottle(bottleId)
            ?: return ActionResult(false, lang.get("bottle.salvage-pickup-lost"), null)
        if (economyService.isEnabled() && config.salvageReward > 0.0 && economyService.deposit(player, config.salvageReward)) {
            currencyLedgerRepository.append(player.uniqueId.toString(), updatedBottle.id, config.salvageReward, "bottle_salvage_reward")
        }
        lastSalvageAt[player.uniqueId] = System.currentTimeMillis()
        playConfiguredSound(player, config.salvageSound)
        player.server.getPlayer(updatedBottle.ownerUuid)?.let { owner ->
            sendBottleNotification(
                player = owner,
                message = lang.format(
                    "bottle.owner-salvaged-notify",
                    mapOf("salvager_alias" to (updatedBottle.salvagedByAlias ?: aliasOf(player.uniqueId))),
                ),
                hover = lang.get("bottle.notify-open-hover"),
                bottleId = updatedBottle.id,
            )
        }
        return ActionResult(
            true,
            lang.format(
                "bottle.salvage-success",
                mapOf(
                    "owner_alias" to updatedBottle.ownerAlias,
                    "reward" to formatAmount(config.salvageReward),
                ),
            ),
            updatedBottle,
        )
    }

    fun replyToBottle(player: Player, bottleId: Long, content: String): ActionResult<BottleThreadView> {
        val thread = getBottleThreadFor(player.uniqueId, bottleId)
            ?: return ActionResult(false, lang.get("bottle.detail-no-access"), null)
        val bottle = thread.bottle
        if (bottle.status != BottleStatus.SALVAGED) {
            return ActionResult(false, lang.get("bottle.reply-unavailable"), null)
        }
        val counterpartUuid = bottle.counterpartUuidFor(player.uniqueId)
            ?: return ActionResult(false, lang.get("bottle.reply-unavailable"), null)
        if (blacklistService.isBlockedEither(player.uniqueId, counterpartUuid)) {
            return ActionResult(false, lang.get("bottle.reply-blocked"), null)
        }
        if (!canReply(player.uniqueId, thread)) {
            return ActionResult(false, lang.get("bottle.reply-turn-blocked"), null)
        }

        bottleRepository.addReply(bottleId, player.uniqueId, aliasOf(player.uniqueId), content)
        val updatedThread = bottleRepository.findBottle(bottleId)?.let { BottleThreadView(it, bottleRepository.listReplies(bottleId)) }
            ?: return ActionResult(false, lang.get("bottle.detail-no-access"), null)

        playConfiguredSound(player, bottleConfigProvider().replySound)
        player.server.getPlayer(counterpartUuid)?.let { counterpart ->
            sendBottleNotification(
                player = counterpart,
                message = lang.format("bottle.reply-received-notify", mapOf("target_alias" to aliasOf(player.uniqueId))),
                hover = lang.get("bottle.notify-open-hover"),
                bottleId = bottleId,
            )
            playConfiguredSound(counterpart, bottleConfigProvider().replySound)
        }
        return ActionResult(true, lang.get("bottle.reply-success"), updatedThread)
    }

    fun closeBottle(player: Player, bottleId: Long): ActionResult<DriftBottle> {
        val bottle = bottleRepository.findBottle(bottleId)
            ?: return ActionResult(false, lang.get("bottle.detail-no-access"), null)
        if (!bottle.isOwner(player.uniqueId)) {
            return ActionResult(false, lang.get("bottle.close-owner-only"), null)
        }
        if (!bottleRepository.closeBottle(bottleId)) {
            return ActionResult(false, lang.get("bottle.close-failed"), null)
        }
        val updatedBottle = bottleRepository.findBottle(bottleId)
            ?: return ActionResult(false, lang.get("bottle.detail-no-access"), null)
        return ActionResult(true, lang.get("bottle.close-success"), updatedBottle)
    }

    fun deleteClosedBottle(player: Player, bottleId: Long): ActionResult<Unit> {
        val bottle = bottleRepository.findBottle(bottleId)
            ?: return ActionResult(false, lang.get("bottle.detail-no-access"), null)
        if (!bottle.isOwner(player.uniqueId)) {
            return ActionResult(false, lang.get("bottle.delete-owner-only"), null)
        }
        if (bottle.status != BottleStatus.CLOSED) {
            return ActionResult(false, lang.get("bottle.delete-closed-only"), null)
        }
        if (!bottleRepository.deleteBottleWithReplies(bottleId)) {
            return ActionResult(false, lang.get("bottle.delete-failed"), null)
        }
        return ActionResult(
            true,
            lang.format("bottle.delete-success", mapOf("bottle_id" to bottleId.toString())),
            Unit,
        )
    }

    fun blacklistCounterpart(player: Player, bottleId: Long): ActionResult<Unit> {
        val bottle = bottleRepository.findBottle(bottleId)
            ?: return ActionResult(false, lang.get("bottle.detail-no-access"), null)
        val targetUuid = bottle.counterpartUuidFor(player.uniqueId)
            ?: return ActionResult(false, lang.get("bottle.blacklist-unavailable"), null)
        blacklistService.add(player.uniqueId, targetUuid)
        return ActionResult(true, lang.get("bottle.blacklist-success"), Unit)
    }

    fun getBottleThreadFor(playerUuid: UUID, bottleId: Long): BottleThreadView? {
        val bottle = bottleRepository.findBottle(bottleId) ?: return null
        if (!bottle.isParticipant(playerUuid)) {
            return null
        }
        return BottleThreadView(bottle, bottleRepository.listReplies(bottleId))
    }

    fun listBottles(playerUuid: UUID, scope: BottleListScope, page: Int, pageSize: Int): PagedResult<BottleListEntry> {
        val totalItems = when (scope) {
            BottleListScope.OWNED -> bottleRepository.countOwnedBottles(playerUuid)
            BottleListScope.SALVAGED -> bottleRepository.countSalvagedBottles(playerUuid)
        }
        val totalPages = maxOf(1, ((totalItems - 1).coerceAtLeast(0) / pageSize) + 1)
        val resolvedPage = page.coerceIn(0, totalPages - 1)
        val offset = resolvedPage * pageSize
        val bottles = when (scope) {
            BottleListScope.OWNED -> bottleRepository.listOwnedBottles(playerUuid, pageSize, offset)
            BottleListScope.SALVAGED -> bottleRepository.listSalvagedBottles(playerUuid, pageSize, offset)
        }
        val entries = bottles.map { bottle ->
            val replies = bottleRepository.listReplies(bottle.id)
            val lastReply = replies.lastOrNull()
            BottleListEntry(
                bottle = bottle,
                replyCount = replies.size,
                lastReplyAt = lastReply?.createdAt,
                lastReplyAlias = lastReply?.senderAlias,
            )
        }
        return PagedResult(entries, resolvedPage, totalPages, totalItems)
    }

    fun summarizePlayer(playerUuid: UUID): PlayerBottleSummary {
        return PlayerBottleSummary(
            thrownCount = bottleRepository.countOwnedBottles(playerUuid),
            openBottleCount = bottleRepository.countOpenBottlesByOwner(playerUuid),
            driftingCount = bottleRepository.countDriftingBottlesByOwner(playerUuid),
            salvagedCount = bottleRepository.countSalvagedBottles(playerUuid),
            sentReplyCount = bottleRepository.countRepliesSent(playerUuid),
            receivedReplyCount = bottleRepository.countRepliesReceived(playerUuid),
        )
    }

    fun aliasOf(playerUuid: UUID): String {
        val prefixes = arrayOf("海星", "月潮", "贝壳", "白帆", "潮汐", "海鸥")
        val hash = abs(playerUuid.hashCode())
        val prefix = prefixes[hash % prefixes.size]
        val suffix = anonymousCodeOf(playerUuid)
        return "匿名$prefix$suffix"
    }

    fun anonymousCodeOf(playerUuid: UUID): String {
        return playerUuid.toString().replace("-", "").takeLast(4).uppercase()
    }

    fun canReply(playerUuid: UUID, thread: BottleThreadView): Boolean {
        if (!thread.bottle.isParticipant(playerUuid) || thread.bottle.status != BottleStatus.SALVAGED) {
            return false
        }
        if (thread.replies.size >= bottleConfigProvider().maxThreadReplies) {
            return false
        }
        if (thread.replies.isEmpty()) {
            return thread.bottle.isSalvager(playerUuid)
        }
        return thread.replies.last().senderUuid != playerUuid
    }

    fun clearPlayerState(playerUuid: UUID) {
        lastThrowAt.remove(playerUuid)
        lastSalvageAt.remove(playerUuid)
    }

    private fun cooldownMessage(lastActionAt: Long?, cooldownSeconds: Long, now: Long, key: String): String? {
        if (lastActionAt == null || cooldownSeconds <= 0L) {
            return null
        }
        val remainingMillis = (lastActionAt + cooldownSeconds * 1000L) - now
        if (remainingMillis <= 0L) {
            return null
        }
        val remainingSeconds = ((remainingMillis + 999L) / 1000L).toString()
        return lang.format(key, mapOf("seconds" to remainingSeconds))
    }

    private fun formatAmount(value: Double): String = String.format("%.2f", value)

    private fun playConfiguredSound(player: Player, sound: Sound) {
        if (!bottleConfigProvider().soundsEnabled) {
            return
        }
        player.playSound(player.location, sound, 1f, 1f)
    }

    private fun sendBottleNotification(player: Player, message: String, hover: String, bottleId: Long) {
        @Suppress("DEPRECATION")
        val hoverEvent = HoverEvent(
            HoverEvent.Action.SHOW_TEXT,
            TextComponent.fromLegacyText(hover),
        )
        val clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/driftbottle open $bottleId")
        val components = mutableListOf<BaseComponent>()
        components += TextComponent.fromLegacyText("$message ")
        components += TextComponent.fromLegacyText(lang.get("bottle.notify-open-action"))
        components.forEach { component ->
            component.clickEvent = clickEvent
            component.hoverEvent = hoverEvent
        }
        player.spigot().sendMessage(*components.toTypedArray())
    }
}
