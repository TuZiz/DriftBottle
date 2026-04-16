package ym.driftBottle.repository

import ym.driftBottle.database.Database

class CurrencyLedgerRepository(private val database: Database) {

    fun append(playerUuid: String, referenceId: Long?, changeAmount: Double, reason: String) {
        database.withConnection { connection ->
            connection.prepareStatement(
                "INSERT INTO currency_ledger(player_uuid, session_id, change_amount, reason, created_at) VALUES (?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, playerUuid)
                if (referenceId == null) {
                    statement.setNull(2, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(2, referenceId)
                }
                statement.setDouble(3, changeAmount)
                statement.setString(4, reason)
                statement.setLong(5, System.currentTimeMillis())
                statement.executeUpdate()
            }
        }
    }
}
