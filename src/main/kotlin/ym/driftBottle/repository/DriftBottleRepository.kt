package ym.driftBottle.repository

import ym.driftBottle.database.Database
import ym.driftBottle.model.BottleReply
import ym.driftBottle.model.BottleStatus
import ym.driftBottle.model.DriftBottle
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.util.UUID

class DriftBottleRepository(private val database: Database) {

    fun createBottle(ownerUuid: UUID, ownerAlias: String, content: String): DriftBottle {
        val now = System.currentTimeMillis()
        val bottleId = database.withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO drift_bottle(owner_uuid, owner_alias, content, status, salvaged_by_uuid, salvaged_by_alias, created_at, salvaged_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, ownerUuid.toString())
                statement.setString(2, ownerAlias)
                statement.setString(3, content)
                statement.setString(4, BottleStatus.DRIFTING.name)
                statement.setNull(5, Types.VARCHAR)
                statement.setNull(6, Types.VARCHAR)
                statement.setLong(7, now)
                statement.setNull(8, Types.BIGINT)
                statement.setLong(9, now)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }
        return DriftBottle(
            id = bottleId,
            ownerUuid = ownerUuid,
            ownerAlias = ownerAlias,
            content = content,
            status = BottleStatus.DRIFTING,
            salvagedByUuid = null,
            salvagedByAlias = null,
            createdAt = now,
            salvagedAt = null,
            updatedAt = now,
        )
    }

    fun listDriftingBottles(): List<DriftBottle> = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT id, owner_uuid, owner_alias, content, status, salvaged_by_uuid, salvaged_by_alias, created_at, salvaged_at, updated_at
            FROM drift_bottle
            WHERE status = ?
            ORDER BY created_at ASC
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, BottleStatus.DRIFTING.name)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(mapBottle(resultSet))
                    }
                }
            }
        }
    }

    fun findBottle(bottleId: Long): DriftBottle? = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT id, owner_uuid, owner_alias, content, status, salvaged_by_uuid, salvaged_by_alias, created_at, salvaged_at, updated_at
            FROM drift_bottle
            WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, bottleId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection null
                }
                mapBottle(resultSet)
            }
        }
    }

    fun listReplies(bottleId: Long): List<BottleReply> = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT id, bottle_id, sender_uuid, sender_alias, content, created_at
            FROM bottle_reply
            WHERE bottle_id = ?
            ORDER BY created_at ASC
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, bottleId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(mapReply(resultSet))
                    }
                }
            }
        }
    }

    fun countReplies(bottleId: Long): Int = database.withConnection { connection ->
        connection.prepareStatement("SELECT COUNT(*) AS total FROM bottle_reply WHERE bottle_id = ?").use { statement ->
            statement.setLong(1, bottleId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun countOwnedBottles(ownerUuid: UUID): Int = database.withConnection { connection ->
        connection.prepareStatement("SELECT COUNT(*) AS total FROM drift_bottle WHERE owner_uuid = ?").use { statement ->
            statement.setString(1, ownerUuid.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun countOpenBottlesByOwner(ownerUuid: UUID): Int = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT COUNT(*) AS total
            FROM drift_bottle
            WHERE owner_uuid = ? AND status <> ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ownerUuid.toString())
            statement.setString(2, BottleStatus.CLOSED.name)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun countDriftingBottlesByOwner(ownerUuid: UUID): Int = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT COUNT(*) AS total
            FROM drift_bottle
            WHERE owner_uuid = ? AND status = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ownerUuid.toString())
            statement.setString(2, BottleStatus.DRIFTING.name)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun countSalvagedBottles(salvagerUuid: UUID): Int = database.withConnection { connection ->
        connection.prepareStatement("SELECT COUNT(*) AS total FROM drift_bottle WHERE salvaged_by_uuid = ?").use { statement ->
            statement.setString(1, salvagerUuid.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun countRepliesSent(senderUuid: UUID): Int = database.withConnection { connection ->
        connection.prepareStatement("SELECT COUNT(*) AS total FROM bottle_reply WHERE sender_uuid = ?").use { statement ->
            statement.setString(1, senderUuid.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun countRepliesReceived(ownerUuid: UUID): Int = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT COUNT(*) AS total
            FROM bottle_reply reply
            INNER JOIN drift_bottle bottle ON bottle.id = reply.bottle_id
            WHERE bottle.owner_uuid = ? AND reply.sender_uuid <> ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ownerUuid.toString())
            statement.setString(2, ownerUuid.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun countAllBottles(status: BottleStatus? = null): Int = database.withConnection { connection ->
        val sql = if (status == null) {
            "SELECT COUNT(*) AS total FROM drift_bottle"
        } else {
            "SELECT COUNT(*) AS total FROM drift_bottle WHERE status = ?"
        }
        connection.prepareStatement(sql).use { statement ->
            if (status != null) {
                statement.setString(1, status.name)
            }
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun countAllReplies(): Int = database.withConnection { connection ->
        connection.prepareStatement("SELECT COUNT(*) AS total FROM bottle_reply").use { statement ->
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun listAllBottles(status: BottleStatus?, limit: Int, offset: Int): List<DriftBottle> = database.withConnection { connection ->
        val sql = if (status == null) {
            """
            SELECT id, owner_uuid, owner_alias, content, status, salvaged_by_uuid, salvaged_by_alias, created_at, salvaged_at, updated_at
            FROM drift_bottle
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """.trimIndent()
        } else {
            """
            SELECT id, owner_uuid, owner_alias, content, status, salvaged_by_uuid, salvaged_by_alias, created_at, salvaged_at, updated_at
            FROM drift_bottle
            WHERE status = ?
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """.trimIndent()
        }
        connection.prepareStatement(sql).use { statement ->
            var parameterIndex = 1
            if (status != null) {
                statement.setString(parameterIndex, status.name)
                parameterIndex++
            }
            statement.setInt(parameterIndex, limit)
            statement.setInt(parameterIndex + 1, offset)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(mapBottle(resultSet))
                    }
                }
            }
        }
    }

    fun listOwnedBottles(ownerUuid: UUID, limit: Int, offset: Int): List<DriftBottle> = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT id, owner_uuid, owner_alias, content, status, salvaged_by_uuid, salvaged_by_alias, created_at, salvaged_at, updated_at
            FROM drift_bottle
            WHERE owner_uuid = ?
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ownerUuid.toString())
            statement.setInt(2, limit)
            statement.setInt(3, offset)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(mapBottle(resultSet))
                    }
                }
            }
        }
    }

    fun listSalvagedBottles(salvagerUuid: UUID, limit: Int, offset: Int): List<DriftBottle> = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT id, owner_uuid, owner_alias, content, status, salvaged_by_uuid, salvaged_by_alias, created_at, salvaged_at, updated_at
            FROM drift_bottle
            WHERE salvaged_by_uuid = ?
            ORDER BY updated_at DESC, id DESC
            LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, salvagerUuid.toString())
            statement.setInt(2, limit)
            statement.setInt(3, offset)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(mapBottle(resultSet))
                    }
                }
            }
        }
    }

    fun markSalvaged(bottleId: Long, salvagerUuid: UUID, salvagerAlias: String): Boolean {
        val now = System.currentTimeMillis()
        return database.withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE drift_bottle
                SET status = ?, salvaged_by_uuid = ?, salvaged_by_alias = ?, salvaged_at = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, BottleStatus.SALVAGED.name)
                statement.setString(2, salvagerUuid.toString())
                statement.setString(3, salvagerAlias)
                statement.setLong(4, now)
                statement.setLong(5, now)
                statement.setLong(6, bottleId)
                statement.setString(7, BottleStatus.DRIFTING.name)
                statement.executeUpdate() > 0
            }
        }
    }

    fun closeBottle(bottleId: Long): Boolean {
        val now = System.currentTimeMillis()
        return database.withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE drift_bottle
                SET status = ?, updated_at = ?
                WHERE id = ? AND status <> ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, BottleStatus.CLOSED.name)
                statement.setLong(2, now)
                statement.setLong(3, bottleId)
                statement.setString(4, BottleStatus.CLOSED.name)
                statement.executeUpdate() > 0
            }
        }
    }

    fun deleteBottleWithReplies(bottleId: Long): Boolean {
        return database.withConnection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM bottle_reply WHERE bottle_id = ?").use { statement ->
                    statement.setLong(1, bottleId)
                    statement.executeUpdate()
                }
                val deleted = connection.prepareStatement("DELETE FROM drift_bottle WHERE id = ?").use { statement ->
                    statement.setLong(1, bottleId)
                    statement.executeUpdate()
                }
                connection.commit()
                deleted > 0
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun addReply(bottleId: Long, senderUuid: UUID, senderAlias: String, content: String): BottleReply {
        val now = System.currentTimeMillis()
        return database.withConnection { connection ->
            connection.autoCommit = false
            try {
                val replyId = connection.prepareStatement(
                    """
                    INSERT INTO bottle_reply(bottle_id, sender_uuid, sender_alias, content, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, bottleId)
                    statement.setString(2, senderUuid.toString())
                    statement.setString(3, senderAlias)
                    statement.setString(4, content)
                    statement.setLong(5, now)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
                connection.prepareStatement("UPDATE drift_bottle SET updated_at = ? WHERE id = ?").use { statement ->
                    statement.setLong(1, now)
                    statement.setLong(2, bottleId)
                    statement.executeUpdate()
                }
                connection.commit()
                BottleReply(
                    id = replyId,
                    bottleId = bottleId,
                    senderUuid = senderUuid,
                    senderAlias = senderAlias,
                    content = content,
                    createdAt = now,
                )
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun mapBottle(resultSet: ResultSet): DriftBottle {
        val salvagedByUuid = resultSet.getString("salvaged_by_uuid")?.takeIf { it.isNotBlank() }?.let(UUID::fromString)
        val salvagedAt = resultSet.getLong("salvaged_at").takeIf { !resultSet.wasNull() }
        return DriftBottle(
            id = resultSet.getLong("id"),
            ownerUuid = UUID.fromString(resultSet.getString("owner_uuid")),
            ownerAlias = resultSet.getString("owner_alias"),
            content = resultSet.getString("content"),
            status = BottleStatus.valueOf(resultSet.getString("status")),
            salvagedByUuid = salvagedByUuid,
            salvagedByAlias = resultSet.getString("salvaged_by_alias")?.takeIf { it.isNotBlank() },
            createdAt = resultSet.getLong("created_at"),
            salvagedAt = salvagedAt,
            updatedAt = resultSet.getLong("updated_at"),
        )
    }

    private fun mapReply(resultSet: ResultSet): BottleReply {
        return BottleReply(
            id = resultSet.getLong("id"),
            bottleId = resultSet.getLong("bottle_id"),
            senderUuid = UUID.fromString(resultSet.getString("sender_uuid")),
            senderAlias = resultSet.getString("sender_alias"),
            content = resultSet.getString("content"),
            createdAt = resultSet.getLong("created_at"),
        )
    }
}
