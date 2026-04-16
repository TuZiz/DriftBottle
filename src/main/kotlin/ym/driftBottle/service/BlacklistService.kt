package ym.driftBottle.service

import ym.driftBottle.repository.BlacklistRepository
import java.util.UUID

class BlacklistService(private val blacklistRepository: BlacklistRepository) {

    fun add(ownerUuid: UUID, blockedUuid: UUID) {
        if (ownerUuid == blockedUuid) {
            return
        }
        blacklistRepository.add(ownerUuid, blockedUuid)
    }

    fun remove(ownerUuid: UUID, blockedUuid: UUID) {
        blacklistRepository.remove(ownerUuid, blockedUuid)
    }

    fun isBlockedEither(first: UUID, second: UUID): Boolean = blacklistRepository.containsEither(first, second)

    fun relatedBlockedUuids(playerUuid: UUID): Set<UUID> = blacklistRepository.relatedBlockedUuids(playerUuid)

    fun list(ownerUuid: UUID) = blacklistRepository.listByOwner(ownerUuid)
}
