package ym.driftBottle.repository

import ym.driftBottle.database.Database
import ym.driftBottle.model.PlayerProfile
import java.util.UUID

class PlayerProfileRepository(private val database: Database) {

    fun ensurePlayer(uuid: UUID, name: String) {
        val now = System.currentTimeMillis()
        database.withConnection { connection ->
            connection.prepareStatement(
                if (database.isSqlite) {
                    """
                    INSERT INTO player_profile(uuid, last_name, total_rating_points, total_ratings, created_at, updated_at)
                    VALUES (?, ?, 0, 0, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET last_name = excluded.last_name, updated_at = excluded.updated_at
                    """.trimIndent()
                } else {
                    """
                    INSERT INTO player_profile(uuid, last_name, total_rating_points, total_ratings, created_at, updated_at)
                    VALUES (?, ?, 0, 0, ?, ?)
                    ON DUPLICATE KEY UPDATE last_name = VALUES(last_name), updated_at = VALUES(updated_at)
                    """.trimIndent()
                }
            ).use { statement ->
                statement.setString(1, uuid.toString())
                statement.setString(2, name)
                statement.setLong(3, now)
                statement.setLong(4, now)
                statement.executeUpdate()
            }
        }
    }

    fun findByUuid(uuid: UUID): PlayerProfile? = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT uuid, last_name, total_rating_points, total_ratings, created_at, updated_at
            FROM player_profile
            WHERE uuid = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) return@withConnection null
                PlayerProfile(
                    uuid = UUID.fromString(resultSet.getString("uuid")),
                    lastName = resultSet.getString("last_name"),
                    totalRatingPoints = resultSet.getInt("total_rating_points"),
                    totalRatings = resultSet.getInt("total_ratings"),
                    createdAt = resultSet.getLong("created_at"),
                    updatedAt = resultSet.getLong("updated_at"),
                )
            }
        }
    }

    fun countPlayers(): Int = database.withConnection { connection ->
        connection.prepareStatement("SELECT COUNT(*) AS total FROM player_profile").use { statement ->
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return@withConnection 0
                }
                resultSet.getInt("total")
            }
        }
    }

    fun listProfiles(limit: Int, offset: Int): List<PlayerProfile> = database.withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT uuid, last_name, total_rating_points, total_ratings, created_at, updated_at
            FROM player_profile
            ORDER BY updated_at DESC, last_name ASC
            LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            PlayerProfile(
                                uuid = UUID.fromString(resultSet.getString("uuid")),
                                lastName = resultSet.getString("last_name"),
                                totalRatingPoints = resultSet.getInt("total_rating_points"),
                                totalRatings = resultSet.getInt("total_ratings"),
                                createdAt = resultSet.getLong("created_at"),
                                updatedAt = resultSet.getLong("updated_at"),
                            )
                        )
                    }
                }
            }
        }
    }

    fun addRating(targetUuid: UUID, score: Int) {
        database.withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE player_profile
                SET total_rating_points = total_rating_points + ?, total_ratings = total_ratings + 1, updated_at = ?
                WHERE uuid = ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, score)
                statement.setLong(2, System.currentTimeMillis())
                statement.setString(3, targetUuid.toString())
                statement.executeUpdate()
            }
        }
    }
}
