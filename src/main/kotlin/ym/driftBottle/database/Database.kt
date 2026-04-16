package ym.driftBottle.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ym.driftBottle.config.DatabaseConfig
import ym.driftBottle.config.DatabaseType
import java.io.Closeable
import java.io.File
import java.sql.Connection

class Database(private val dataFolder: File, val config: DatabaseConfig) : Closeable {

    private val dataSource: HikariDataSource = HikariDataSource(createHikariConfig())

    val isSqlite: Boolean
        get() = config.type == DatabaseType.SQLITE

    fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun initSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                schemaStatements().forEach(statement::executeUpdate)
            }
        }
    }

    override fun close() {
        dataSource.close()
    }

    private fun createHikariConfig(): HikariConfig {
        val hikariConfig = HikariConfig()
        if (isSqlite) {
            val databaseFile = File(dataFolder, config.sqliteFile)
            hikariConfig.jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
            hikariConfig.driverClassName = "org.sqlite.JDBC"
            hikariConfig.maximumPoolSize = 1
        } else {
            hikariConfig.jdbcUrl = "jdbc:mysql://${config.mysqlHost}:${config.mysqlPort}/${config.mysqlDatabase}?${config.mysqlParameters}"
            hikariConfig.driverClassName = "com.mysql.cj.jdbc.Driver"
            hikariConfig.username = config.mysqlUsername
            hikariConfig.password = config.mysqlPassword
            hikariConfig.maximumPoolSize = 10
        }
        hikariConfig.poolName = "DriftBottlePool"
        hikariConfig.connectionTimeout = 10000
        hikariConfig.maxLifetime = 600000
        hikariConfig.keepaliveTime = 300000
        return hikariConfig
    }

    private fun schemaStatements(): List<String> {
        val idColumn = if (isSqlite) "INTEGER PRIMARY KEY AUTOINCREMENT" else "BIGINT PRIMARY KEY AUTO_INCREMENT"
        val uuidColumn = if (isSqlite) "TEXT" else "VARCHAR(36)"
        val longColumn = if (isSqlite) "INTEGER" else "BIGINT"
        val doubleColumn = if (isSqlite) "REAL" else "DOUBLE"
        val nameColumn = if (isSqlite) "TEXT" else "VARCHAR(64)"
        val textColumn = if (isSqlite) "TEXT" else "VARCHAR(255)"

        return listOf(
            """
            CREATE TABLE IF NOT EXISTS player_profile (
                uuid $uuidColumn PRIMARY KEY,
                last_name $nameColumn NOT NULL,
                total_rating_points INTEGER NOT NULL DEFAULT 0,
                total_ratings INTEGER NOT NULL DEFAULT 0,
                created_at $longColumn NOT NULL,
                updated_at $longColumn NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS blacklist (
                owner_uuid $uuidColumn NOT NULL,
                blocked_uuid $uuidColumn NOT NULL,
                created_at $longColumn NOT NULL,
                PRIMARY KEY (owner_uuid, blocked_uuid)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS currency_ledger (
                id $idColumn,
                player_uuid $uuidColumn NOT NULL,
                session_id $longColumn NULL,
                change_amount $doubleColumn NOT NULL,
                reason $textColumn NOT NULL,
                created_at $longColumn NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS drift_bottle (
                id $idColumn,
                owner_uuid $uuidColumn NOT NULL,
                owner_alias $textColumn NOT NULL,
                content $textColumn NOT NULL,
                status VARCHAR(32) NOT NULL,
                salvaged_by_uuid $uuidColumn NULL,
                salvaged_by_alias $textColumn NULL,
                created_at $longColumn NOT NULL,
                salvaged_at $longColumn NULL,
                updated_at $longColumn NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS bottle_reply (
                id $idColumn,
                bottle_id $longColumn NOT NULL,
                sender_uuid $uuidColumn NOT NULL,
                sender_alias $textColumn NOT NULL,
                content $textColumn NOT NULL,
                created_at $longColumn NOT NULL
            )
            """.trimIndent(),
        )
    }
}
