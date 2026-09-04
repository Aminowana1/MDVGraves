package xyz.mdvcraft.mdvgraves.logoutbody;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class LogoutBodyRepository {
    private final Connection connection;

    public LogoutBodyRepository(Connection connection) {
        this.connection = connection;
    }

    public void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS logout_body_sessions (
                      player_uuid TEXT PRIMARY KEY,
                      player_name TEXT NOT NULL,
                      state TEXT NOT NULL,
                      world TEXT NOT NULL,
                      x REAL NOT NULL,
                      y REAL NOT NULL,
                      z REAL NOT NULL,
                      yaw REAL NOT NULL,
                      pitch REAL NOT NULL,
                      health REAL NOT NULL,
                      max_health REAL NOT NULL,
                      absorption REAL NOT NULL,
                      total_experience INTEGER NOT NULL,
                      level INTEGER NOT NULL,
                      exp REAL NOT NULL,
                      inventory BLOB NOT NULL,
                      protected_inventory BLOB NOT NULL,
                      keep_all_inventory INTEGER NOT NULL DEFAULT 0,
                      owner_protected INTEGER NOT NULL DEFAULT 0,
                      grave_texture TEXT NOT NULL DEFAULT '',
                      grave_id TEXT,
                      created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL
                    )
                    """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_logout_body_state ON logout_body_sessions(state)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_logout_body_expires ON logout_body_sessions(expires_at)");
        }
    }

    public List<LogoutBodySession> loadAll() throws Exception {
        List<LogoutBodySession> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM logout_body_sessions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                result.add(read(rs));
        }
        return result;
    }

    public void save(LogoutBodySession session) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO logout_body_sessions(
                  player_uuid,player_name,state,world,x,y,z,yaw,pitch,health,max_health,absorption,
                  total_experience,level,exp,inventory,protected_inventory,keep_all_inventory,
                  owner_protected,grave_texture,grave_id,created_at,expires_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                  player_name=excluded.player_name,
                  state=excluded.state,
                  world=excluded.world,
                  x=excluded.x,
                  y=excluded.y,
                  z=excluded.z,
                  yaw=excluded.yaw,
                  pitch=excluded.pitch,
                  health=excluded.health,
                  max_health=excluded.max_health,
                  absorption=excluded.absorption,
                  total_experience=excluded.total_experience,
                  level=excluded.level,
                  exp=excluded.exp,
                  inventory=excluded.inventory,
                  protected_inventory=excluded.protected_inventory,
                  keep_all_inventory=excluded.keep_all_inventory,
                  owner_protected=excluded.owner_protected,
                  grave_texture=excluded.grave_texture,
                  grave_id=excluded.grave_id,
                  created_at=excluded.created_at,
                  expires_at=excluded.expires_at
                """)) {
            ps.setString(1, session.playerUuid().toString());
            ps.setString(2, session.playerName());
            ps.setString(3, session.state().name());
            ps.setString(4, session.world());
            ps.setDouble(5, session.x());
            ps.setDouble(6, session.y());
            ps.setDouble(7, session.z());
            ps.setFloat(8, session.yaw());
            ps.setFloat(9, session.pitch());
            ps.setDouble(10, session.health());
            ps.setDouble(11, session.maxHealth());
            ps.setDouble(12, session.absorption());
            ps.setInt(13, session.totalExperience());
            ps.setInt(14, session.level());
            ps.setFloat(15, session.exp());
            ps.setBytes(16, session.inventory().serialize());
            ps.setBytes(17, session.protectedInventory().serialize());
            ps.setInt(18, session.keepAllInventory() ? 1 : 0);
            ps.setInt(19, session.ownerProtected() ? 1 : 0);
            ps.setString(20, session.graveTexture() == null ? "" : session.graveTexture());
            if (session.graveId() == null)
                ps.setNull(21, Types.VARCHAR);
            else
                ps.setString(21, session.graveId().toString());
            ps.setLong(22, session.createdAt());
            ps.setLong(23, session.expiresAt());
            ps.executeUpdate();
        }
    }

    public void delete(UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM logout_body_sessions WHERE player_uuid=?")) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    private LogoutBodySession read(ResultSet rs) throws Exception {
        String rawGrave = rs.getString("grave_id");
        UUID graveId = rawGrave == null || rawGrave.isBlank() ? null : UUID.fromString(rawGrave);
        LogoutBodyState state;
        try {
            state = LogoutBodyState.valueOf(rs.getString("state").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            state = LogoutBodyState.BODY_SAFE;
        }
        return new LogoutBodySession(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                state,
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch"),
                rs.getDouble("health"),
                rs.getDouble("max_health"),
                rs.getDouble("absorption"),
                rs.getInt("total_experience"),
                rs.getInt("level"),
                rs.getFloat("exp"),
                PlayerInventorySnapshot.deserialize(rs.getBytes("inventory")),
                PlayerInventorySnapshot.deserialize(rs.getBytes("protected_inventory")),
                rs.getInt("keep_all_inventory") != 0,
                rs.getInt("owner_protected") != 0,
                rs.getString("grave_texture"),
                graveId,
                rs.getLong("created_at"),
                rs.getLong("expires_at"));
    }
}
