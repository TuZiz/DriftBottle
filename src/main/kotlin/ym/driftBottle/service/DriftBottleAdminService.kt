package ym.driftBottle.service

import ym.driftBottle.lang.LangService
import ym.driftBottle.model.ActionResult
import ym.driftBottle.model.AdminBottleStats
import ym.driftBottle.model.AdminPlayerListEntry
import ym.driftBottle.model.BottleListEntry
import ym.driftBottle.model.BottleStatus
import ym.driftBottle.model.BottleThreadView
import ym.driftBottle.model.DriftBottle
import ym.driftBottle.model.PagedResult
import ym.driftBottle.model.PlayerBottleSummary
import ym.driftBottle.repository.DriftBottleRepository
import ym.driftBottle.repository.PlayerProfileRepository

class DriftBottleAdminService(
    private val lang: LangService,
    private val bottleRepository: DriftBottleRepository,
    private val playerProfileRepository: PlayerProfileRepository,
) {

    fun stats(): AdminBottleStats {
        return AdminBottleStats(
            totalBottles = bottleRepository.countAllBottles(),
            driftingBottles = bottleRepository.countAllBottles(BottleStatus.DRIFTING),
            salvagedBottles = bottleRepository.countAllBottles(BottleStatus.SALVAGED),
            closedBottles = bottleRepository.countAllBottles(BottleStatus.CLOSED),
            totalReplies = bottleRepository.countAllReplies(),
            totalPlayers = playerProfileRepository.countPlayers(),
        )
    }

    fun listBottles(status: BottleStatus?, page: Int, pageSize: Int): PagedResult<BottleListEntry> {
        val totalItems = bottleRepository.countAllBottles(status)
        val totalPages = maxOf(1, ((totalItems - 1).coerceAtLeast(0) / pageSize) + 1)
        val resolvedPage = page.coerceIn(0, totalPages - 1)
        val offset = resolvedPage * pageSize
        val entries = bottleRepository.listAllBottles(status, pageSize, offset).map { bottle ->
            val replies = bottleRepository.listReplies(bottle.id)
            BottleListEntry(
                bottle = bottle,
                replyCount = replies.size,
                lastReplyAt = replies.lastOrNull()?.createdAt,
                lastReplyAlias = replies.lastOrNull()?.senderAlias,
            )
        }
        return PagedResult(entries, resolvedPage, totalPages, totalItems)
    }

    fun getBottleThread(bottleId: Long): BottleThreadView? {
        val bottle = bottleRepository.findBottle(bottleId) ?: return null
        return BottleThreadView(bottle, bottleRepository.listReplies(bottleId))
    }

    fun forceCloseBottle(bottleId: Long): ActionResult<DriftBottle> {
        val bottle = bottleRepository.findBottle(bottleId)
            ?: return ActionResult(false, lang.get("admin.bottle-not-found"), null)
        if (bottle.status == BottleStatus.CLOSED) {
            return ActionResult(false, lang.get("admin.force-close-already-closed"), bottle)
        }
        if (!bottleRepository.closeBottle(bottleId)) {
            return ActionResult(false, lang.get("admin.force-close-failed"), bottle)
        }
        val updatedBottle = bottleRepository.findBottle(bottleId) ?: bottle
        return ActionResult(
            true,
            lang.format("admin.force-close-success", mapOf("bottle_id" to bottleId.toString())),
            updatedBottle,
        )
    }

    fun deleteBottle(bottleId: Long): ActionResult<Unit> {
        if (bottleRepository.findBottle(bottleId) == null) {
            return ActionResult(false, lang.get("admin.bottle-not-found"), null)
        }
        if (!bottleRepository.deleteBottleWithReplies(bottleId)) {
            return ActionResult(false, lang.get("admin.delete-failed"), null)
        }
        return ActionResult(true, lang.format("admin.delete-success", mapOf("bottle_id" to bottleId.toString())), Unit)
    }

    fun listPlayers(page: Int, pageSize: Int): PagedResult<AdminPlayerListEntry> {
        val totalItems = playerProfileRepository.countPlayers()
        val totalPages = maxOf(1, ((totalItems - 1).coerceAtLeast(0) / pageSize) + 1)
        val resolvedPage = page.coerceIn(0, totalPages - 1)
        val offset = resolvedPage * pageSize
        val entries = playerProfileRepository.listProfiles(pageSize, offset).map { profile ->
            AdminPlayerListEntry(
                profile = profile,
                summary = PlayerBottleSummary(
                    thrownCount = bottleRepository.countOwnedBottles(profile.uuid),
                    openBottleCount = bottleRepository.countOpenBottlesByOwner(profile.uuid),
                    driftingCount = bottleRepository.countDriftingBottlesByOwner(profile.uuid),
                    salvagedCount = bottleRepository.countSalvagedBottles(profile.uuid),
                    sentReplyCount = bottleRepository.countRepliesSent(profile.uuid),
                    receivedReplyCount = bottleRepository.countRepliesReceived(profile.uuid),
                ),
            )
        }
        return PagedResult(entries, resolvedPage, totalPages, totalItems)
    }
}
