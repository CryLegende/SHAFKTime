package org.Cry_Legende.sHAFKTime.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

/**
 * Temps AFK en MariaDB, partagé par tous les serveurs. Chargée seulement avec SHNetwork
 * (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal.</p>
 */
public final class AfkDatabase {

    private static final String TABLE = "shafktime";

    private final HikariDataSource pool;

    public AfkDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHAFKTime");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : le plugin reste alors en mode local
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Crée la table si elle manque. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    public void createTable() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
                return;
            } catch (SQLException missing) {
                // la table n'existe pas encore : création ci-dessous
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "seconds BIGINT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /** Temps AFK du joueur en secondes (0 sans ligne). */
    public long load(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT seconds FROM " + TABLE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0L, rs.getLong(1)) : 0L;
            }
        }
    }

    /**
     * Écrit le temps AFK d'un joueur. Valeur absolue : seul le serveur où se trouve le joueur l'écrit,
     * et l'arrivée sur un autre serveur attend que cette écriture soit finie.
     */
    public void save(Map<UUID, Long> balances) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, seconds) VALUES (?, ?) "
                 + "ON DUPLICATE KEY UPDATE seconds = VALUES(seconds)")) {
            for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
                ps.setString(1, entry.getKey().toString());
                ps.setLong(2, Math.max(0L, entry.getValue()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Reprend les temps de l'ancien players.yml. Un joueur qui a déjà une ligne n'est pas touché,
     * donc l'importation peut être relancée sans rien écraser.
     *
     * @return {importés, déjà présents}
     */
    public int[] importBalances(Map<UUID, Long> balances) throws SQLException {
        int imported = 0;
        int present = 0;
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + TABLE + " (uuid, seconds) VALUES (?, ?)")) {
            for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                ps.setString(1, entry.getKey().toString());
                ps.setLong(2, entry.getValue());
                if (ps.executeUpdate() > 0) {
                    imported++;
                } else {
                    present++;
                }
            }
        }
        return new int[]{imported, present};
    }

    public void close() {
        pool.close();
    }
}
