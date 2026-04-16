package ym.driftBottle.model

import java.util.UUID

data class PlayerProfile(
    val uuid: UUID,
    val lastName: String,
    val totalRatingPoints: Int,
    val totalRatings: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val averageRating: Double
        get() = if (totalRatings == 0) 0.0 else totalRatingPoints.toDouble() / totalRatings.toDouble()
}

data class BlacklistEntry(
    val ownerUuid: UUID,
    val blockedUuid: UUID,
    val blockedName: String?,
    val createdAt: Long,
)

enum class BottleStatus {
    DRIFTING,
    SALVAGED,
    CLOSED
}

enum class BottleListScope {
    OWNED,
    SALVAGED
}

data class DriftBottle(
    val id: Long,
    val ownerUuid: UUID,
    val ownerAlias: String,
    val content: String,
    val status: BottleStatus,
    val salvagedByUuid: UUID?,
    val salvagedByAlias: String?,
    val createdAt: Long,
    val salvagedAt: Long?,
    val updatedAt: Long,
) {
    fun isOwner(playerUuid: UUID): Boolean = ownerUuid == playerUuid

    fun isSalvager(playerUuid: UUID): Boolean = salvagedByUuid == playerUuid

    fun isParticipant(playerUuid: UUID): Boolean = isOwner(playerUuid) || isSalvager(playerUuid)

    fun counterpartUuidFor(playerUuid: UUID): UUID? = when (playerUuid) {
        ownerUuid -> salvagedByUuid
        salvagedByUuid -> ownerUuid
        else -> null
    }

    fun counterpartAliasFor(playerUuid: UUID): String? = when (playerUuid) {
        ownerUuid -> salvagedByAlias
        salvagedByUuid -> ownerAlias
        else -> null
    }
}

data class BottleReply(
    val id: Long,
    val bottleId: Long,
    val senderUuid: UUID,
    val senderAlias: String,
    val content: String,
    val createdAt: Long,
)

data class BottleThreadView(
    val bottle: DriftBottle,
    val replies: List<BottleReply>,
)

data class BottleListEntry(
    val bottle: DriftBottle,
    val replyCount: Int,
    val lastReplyAt: Long?,
    val lastReplyAlias: String?,
)

data class AdminBottleStats(
    val totalBottles: Int,
    val driftingBottles: Int,
    val salvagedBottles: Int,
    val closedBottles: Int,
    val totalReplies: Int,
    val totalPlayers: Int,
)

data class AdminPlayerListEntry(
    val profile: PlayerProfile,
    val summary: PlayerBottleSummary,
)

data class PlayerBottleSummary(
    val thrownCount: Int,
    val openBottleCount: Int,
    val driftingCount: Int,
    val salvagedCount: Int,
    val sentReplyCount: Int,
    val receivedReplyCount: Int,
)

data class PagedResult<T>(
    val entries: List<T>,
    val page: Int,
    val totalPages: Int,
    val totalItems: Int,
)

data class ActionResult<T>(
    val success: Boolean,
    val message: String,
    val payload: T?,
)
