package ym.driftBottle.repository

import org.bukkit.Bukkit
import ym.driftBottle.database.Database
import ym.driftBottle.model.BlacklistEntry
import java.util.UUID

class BlacklistRepository(private val database: Database) {

    fun add(ownerUuid: UUID, blockedUuid: UUID) {
        database.withConnection { connection ->
            connection.prepareStatement(
                if (database.isSqlite) {
                    "INSERT OR REPLACE INTO blacklist(owner_uuid, blocked_uuid, created_at) VALUES (?, ?, ?)"
                } else {
                    "INSERT INTO blacklist(owner_uuid, blocked_uuid, created_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE created_at = VALUES(created_at)"
                }
            ).use { statement ->
                statement.setString(1, ownerUuid.toString())
                statement.setString(2, blockedUuid.toString())
                statement.setLong(3, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }
    }

    fun remove(ownerUuid: UUID, blockedUuid: UUID) {
        database.withConnection { connection ->
            connection.prepareStatement("DELETE FROM blacklist WHERE owner_uuid = ? AND blocked_uuid = ?").use { statement ->
                statement.setString(1, ownerUuid.toString())
                statement.setString(2, blockedUuid.toString())
                statement.executeUpdate()
            }
        }
    }

    fun containsEither(first: UUID, second: UUID): Boolean = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT 1 FROM blacklist
            WHERE (owner_uuid = ? AND blocked_uuid = ?)
               OR (owner_uuid = ? AND blocked_uuid = ?)
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, first.toString())
            statement.setString(2, second.toString())
            statement.setString(3, second.toString())
            statement.setString(4, first.toString())
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }

    fun relatedBlockedUuids(playerUuid: UUID): Set<UUID> = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT owner_uuid, blocked_uuid
            FROM blacklist
            WHERE owner_uuid = ? OR blocked_uuid = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, playerUuid.toString())
            statement.setString(2, playerUuid.toString())
            statement.executeQuery().use { resultSet ->
                buildSet {
                    while (resultSet.next()) {
                        val ownerUuid = UUID.fromString(resultSet.getString("owner_uuid"))
                        val blockedUuid = UUID.fromString(resultSet.getString("blocked_uuid"))
                        add(if (ownerUuid == playerUuid) blockedUuid else ownerUuid)
                    }
                }
            }
        }
    }

    fun listByOwner(ownerUuid: UUID): List<BlacklistEntry> = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT owner_uuid, blocked_uuid, created_at
            FROM blacklist
            WHERE owner_uuid = ?
            ORDER BY created_at DESC
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ownerUuid.toString())
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        val blockedUuid = UUID.fromString(resultSet.getString("blocked_uuid"))
                        add(
                            BlacklistEntry(
                                ownerUuid = UUID.fromString(resultSet.getString("owner_uuid")),
                                blockedUuid = blockedUuid,
                                blockedName = Bukkit.getOfflinePlayer(blockedUuid).name,
                                createdAt = resultSet.getLong("created_at"),
                            )
                        )
                    }
                }
            }
        }
    }
}
