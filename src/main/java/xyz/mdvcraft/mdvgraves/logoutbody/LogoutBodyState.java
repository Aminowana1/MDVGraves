package xyz.mdvcraft.mdvgraves.logoutbody;

public enum LogoutBodyState {
    BODY_ACTIVE,
    RESTORE_PENDING,
    DEATH_PROCESSING,
    GRAVE_CREATED,
    // Muerte Player real: el inventario ya lo resuelve Minecraft/PlayerDeathEvent.
    ONLINE_DEATH,
    RESPAWN_PENDING,
    BODY_SAFE,
    RESTORED
}
