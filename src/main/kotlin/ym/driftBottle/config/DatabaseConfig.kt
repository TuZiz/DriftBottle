package ym.driftBottle.config

import org.bukkit.configuration.file.FileConfiguration

enum class DatabaseType {
    SQLITE,
    MYSQL
}

data class DatabaseConfig(
    val type: DatabaseType,
    val sqliteFile: String,
    val mysqlHost: String,
    val mysqlPort: Int,
    val mysqlDatabase: String,
    val mysqlUsername: String,
    val mysqlPassword: String,
    val mysqlParameters: String,
) {
    companion object {
        fun from(config: FileConfiguration): DatabaseConfig {
            val section = config.getConfigurationSection("database")
                ?: error("Missing database section in config.yml")

            return DatabaseConfig(
                type = DatabaseType.valueOf(section.getString("type", "SQLITE")!!.uppercase()),
                sqliteFile = section.getString("sqlite-file", "driftbottle.db")!!,
                mysqlHost = section.getString("mysql.host", "127.0.0.1")!!,
                mysqlPort = section.getInt("mysql.port", 3306),
                mysqlDatabase = section.getString("mysql.database", "driftbottle")!!,
                mysqlUsername = section.getString("mysql.username", "root")!!,
                mysqlPassword = section.getString("mysql.password", "")!!,
                mysqlParameters = section.getString(
                    "mysql.parameters",
                    "useSSL=false&characterEncoding=utf8&serverTimezone=UTC"
                )!!,
            )
        }
    }
}
