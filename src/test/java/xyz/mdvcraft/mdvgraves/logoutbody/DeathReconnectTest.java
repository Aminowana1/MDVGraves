package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import xyz.mdvcraft.mdvgraves.MDVGravesPlugin;

import java.lang.reflect.Method;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeathReconnectTest {
    @Test
    void deathScreenDisconnectWaitsForAuthenticationAndKeepsVanillaInventory() throws Exception {
        try (Harness h = new Harness()) {
            h.dieAndQuit();
            LogoutBodySession saved = h.sessions.get(h.uuid);
            assertEquals(LogoutBodyState.RESPAWN_PENDING, saved.state());
            assertTrue(saved.inventory().isEmpty());
            assertTrue(saved.protectedInventory().isEmpty());

            // nLogin has revived the player to half a heart at the old location.
            when(h.player.isDead()).thenReturn(false);
            when(h.player.getHealth()).thenReturn(1.0);
            when(h.nlogin.isNLoginEnabled()).thenReturn(true);
            when(h.nlogin.isAuthenticated(h.player)).thenReturn(false);
            h.manager.onJoin(h.join());
            h.authPoll.run();
            assertTrue(h.later.isEmpty());
            verify(h.player, never()).teleport(any(Location.class));

            PlayerTeleportEvent returnToDeath = mock(PlayerTeleportEvent.class);
            when(returnToDeath.getPlayer()).thenReturn(h.player);
            when(returnToDeath.getTo()).thenReturn(new Location(h.survival, 40, 70, 50));
            h.manager.onPendingDeathTeleport(returnToDeath);
            verify(returnToDeath).setCancelled(true);

            when(h.nlogin.isAuthenticated(h.player)).thenReturn(true);
            h.authPoll.run();
            h.runLater();
            verify(h.player).setHealth(20.0);
            verify(h.player).teleport(h.lobbySpawn);
            verify(h.player).setFoodLevel(20);
            assertTrue(h.manager.isPendingRespawn(h.uuid));

            // Delayed nLogin restoration cannot permanently leave half a heart.
            h.runLater();
            verify(h.player, times(2)).setHealth(20.0);
            assertFalse(h.manager.isPendingRespawn(h.uuid));
            verify(h.repository).delete(h.uuid);
            verify(h.player, never()).getInventory();
            verify(h.player, never()).setTotalExperience(anyInt());
            verify(h.player, never()).setLevel(anyInt());
            verify(h.player, never()).setExp(anyFloat());
            verify(h.plugin, never()).createOfflineGrave(any(), any(), anyString(), any(), anyList(), anyBoolean(), anyString());
        }
    }

    @Test
    void stillDeadOnReconnectUsesRealRespawnBeforeRestoringVitals() throws Exception {
        try (Harness h = new Harness()) {
            h.dieAndQuit();
            when(h.player.isDead()).thenReturn(true);
            when(h.player.getHealth()).thenReturn(0.0);
            Player.Spigot spigot = mock(Player.Spigot.class);
            when(h.player.spigot()).thenReturn(spigot);
            PlayerRespawnEvent respawn = h.respawn();
            doAnswer(invocation -> {
                h.manager.onRespawn(respawn);
                when(h.player.isDead()).thenReturn(false);
                when(h.player.getHealth()).thenReturn(20.0);
                return null;
            }).when(spigot).respawn();

            h.manager.onJoin(h.join());
            h.runLater();
            verify(spigot).respawn();
            verify(respawn).setRespawnLocation(h.lobbySpawn);
            verify(h.player, never()).setHealth(anyDouble());
            assertTrue(h.manager.isPendingRespawn(h.uuid));
            h.runLater();
            verify(h.player).setHealth(20.0);
            h.runLater();
            assertFalse(h.manager.isPendingRespawn(h.uuid));
        }
    }

    @Test
    void deadPlayerCanLeaveDeathScreenBeforeNloginWithoutFinalizingTheirSession() throws Exception {
        try (Harness h = new Harness()) {
            h.dieAndQuit();
            when(h.player.isDead()).thenReturn(true);
            when(h.player.getHealth()).thenReturn(0.0);
            when(h.nlogin.isNLoginEnabled()).thenReturn(true);
            when(h.nlogin.isAuthenticated(h.player)).thenReturn(false);
            Player.Spigot spigot = mock(Player.Spigot.class);
            when(h.player.spigot()).thenReturn(spigot);
            PlayerRespawnEvent respawn = h.respawn();
            doAnswer(invocation -> {
                h.manager.onRespawn(respawn);
                when(h.player.isDead()).thenReturn(false);
                when(h.player.getHealth()).thenReturn(20.0);
                return null;
            }).when(spigot).respawn();

            h.manager.onJoin(h.join());
            h.runLater();
            verify(spigot).respawn();
            verify(respawn).setRespawnLocation(h.lobbySpawn);
            assertTrue(h.manager.isPendingRespawn(h.uuid));
            h.authPoll.run();
            assertTrue(h.later.isEmpty());
            verify(h.player, never()).setHealth(anyDouble());
            verify(h.player, never()).teleport(any(Location.class));
            verify(h.player, never()).getInventory();
            verify(h.repository, never()).delete(h.uuid);

            when(h.nlogin.isAuthenticated(h.player)).thenReturn(true);
            h.authPoll.run();
            h.runLater();
            verify(h.player).setHealth(20.0);
            h.runLater();
            assertFalse(h.manager.isPendingRespawn(h.uuid));
        }
    }

    @Test
    void failedNativeRespawnCanBeRetriedWithoutReconnectingOrLosingTheMarker() throws Exception {
        try (Harness h = new Harness()) {
            h.dieAndQuit();
            when(h.player.isDead()).thenReturn(true);
            when(h.player.getHealth()).thenReturn(0.0);
            Player.Spigot spigot = mock(Player.Spigot.class);
            when(h.player.spigot()).thenReturn(spigot);
            doThrow(new IllegalStateException("Temporary respawn failure")).doAnswer(invocation -> {
                when(h.player.isDead()).thenReturn(false);
                when(h.player.getHealth()).thenReturn(20.0);
                return null;
            }).when(spigot).respawn();

            h.manager.onJoin(h.join());
            h.runLater();
            assertTrue(h.manager.isPendingRespawn(h.uuid));
            h.runLater();
            verify(spigot, times(2)).respawn();
            h.runLater();
            verify(h.player).setHealth(20.0);
            h.runLater();
            assertFalse(h.manager.isPendingRespawn(h.uuid));
        }
    }

    @Test
    void delayedAuthenticationCallbackCannotFinalizeANewUnauthenticatedConnection() throws Exception {
        try (Harness h = new Harness()) {
            h.dieAndQuit();
            when(h.player.getHealth()).thenReturn(1.0);
            when(h.nlogin.isNLoginEnabled()).thenReturn(true);
            when(h.nlogin.isAuthenticated(h.player)).thenReturn(true);
            h.manager.onJoin(h.join());
            h.authPoll.run();
            h.manager.onQuit(h.quit());

            Player newConnection = mock(Player.class);
            when(newConnection.getUniqueId()).thenReturn(h.uuid);
            when(newConnection.isOnline()).thenReturn(true);
            h.bukkit.when(() -> Bukkit.getPlayer(h.uuid)).thenReturn(newConnection);
            h.runLater();
            assertTrue(h.manager.isPendingRespawn(h.uuid));
            verify(h.player, never()).teleport(any(Location.class));
            verify(newConnection, never()).teleport(any(Location.class));
            verify(h.repository, never()).delete(h.uuid);
        }
    }

    @Test
    void pressingRespawnBeforeQuitDoesNotTurnIntoAnOfflineDeath() throws Exception {
        try (Harness h = new Harness()) {
            h.manager.onOnlineDeath(h.death());
            assertEquals(LogoutBodyState.ONLINE_DEATH, h.sessions.get(h.uuid).state());
            PlayerRespawnEvent respawn = h.respawn();
            h.manager.onRespawn(respawn);
            assertFalse(h.manager.isPendingRespawn(h.uuid));
            verify(respawn, never()).setRespawnLocation(any());
            h.config.set("logout-body.enabled", false);
            h.manager.onQuit(h.quit());
            h.manager.onJoin(h.join());
            assertTrue(h.later.isEmpty());
            verify(h.player, never()).teleport(any(Location.class));
        }
    }

    @Test
    void deadPlayerdataFromBeforeTheUpdateGetsARecoveryMarker() throws Exception {
        try (Harness h = new Harness()) {
            when(h.player.isDead()).thenReturn(true);
            when(h.player.getHealth()).thenReturn(0.0);
            h.manager.onJoin(h.join());
            assertEquals(LogoutBodyState.RESPAWN_PENDING, h.sessions.get(h.uuid).state());
            assertTrue(h.manager.isPendingRespawn(h.uuid));
            verify(h.player, never()).getInventory();
        }
    }

    @Test
    void disconnectingAgainDuringPostLoginHoldKeepsRespawnPending() throws Exception {
        try (Harness h = new Harness()) {
            h.dieAndQuit();
            when(h.player.isDead()).thenReturn(false);
            when(h.player.getHealth()).thenReturn(1.0);
            h.manager.onJoin(h.join());
            h.runLater();
            h.manager.onQuit(h.quit());
            when(h.player.isOnline()).thenReturn(false);
            h.runLater();
            assertTrue(h.manager.isPendingRespawn(h.uuid));
            verify(h.repository, never()).delete(h.uuid);
        }
    }

    @Test
    void onlineDeathMarkerSurvivesSqliteReloadWithoutInventorySnapshot() throws Exception {
        try (Harness h = new Harness(); var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var repository = new LogoutBodyRepository(connection);
            repository.createSchema();
            h.manager.onOnlineDeath(h.death());
            repository.save(h.sessions.get(h.uuid));
            assertEquals(LogoutBodyState.ONLINE_DEATH, repository.loadAll().getFirst().state());
            set(h.manager, "repository", repository);
            invoke(h.manager, "recoverPersistedSessions");
            assertEquals(LogoutBodyState.RESPAWN_PENDING, h.sessions.get(h.uuid).state());
            LogoutBodySession afterRestart = repository.loadAll().getFirst();
            assertEquals(LogoutBodyState.RESPAWN_PENDING, afterRestart.state());
            assertTrue(afterRestart.inventory().isEmpty());
            assertTrue(afterRestart.protectedInventory().isEmpty());
        }
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void invoke(Object object, String method) throws Exception {
        Method target = object.getClass().getDeclaredMethod(method);
        target.setAccessible(true);
        target.invoke(object);
    }

    private static final class Harness implements AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final Player player = mock(Player.class);
        final World survival = mock(World.class);
        final World lobby = mock(World.class);
        final Location lobbySpawn = new Location(lobby, 0.5, 65.0, 0.5);
        final MDVGravesPlugin plugin = mock(MDVGravesPlugin.class);
        final LogoutBodyRepository repository = mock(LogoutBodyRepository.class);
        final NLoginBridge nlogin = mock(NLoginBridge.class);
        final YamlConfiguration config = new YamlConfiguration();
        final LogoutBodyManager manager = mock(LogoutBodyManager.class, CALLS_REAL_METHODS);
        final Map<UUID, LogoutBodySession> sessions = new HashMap<>();
        final ArrayDeque<Runnable> later = new ArrayDeque<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        Runnable authPoll;

        Harness() throws Exception {
            try {
            config.set("settings.enabled-worlds", java.util.List.of("world"));
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(player.getUniqueId()).thenReturn(uuid);
            when(player.getName()).thenReturn("DeadPlayer");
            when(player.getWorld()).thenReturn(survival);
            when(player.getLocation()).thenReturn(new Location(survival, 40, 70, 50));
            when(player.isOnline()).thenReturn(true);
            when(player.getHealth()).thenReturn(20.0);
            when(survival.getName()).thenReturn("world");
            when(lobby.getName()).thenReturn("world5");
            when(lobby.getSpawnLocation()).thenReturn(lobbySpawn);
            when(player.teleport(any(Location.class))).thenReturn(true);
            // Reading Paper's static Attribute registry needs a running server.
            // The player stats query is isolated; respawn/auth/DB decisions run real methods.
            doReturn(20.0).when(manager).resolveMaxHealth(player);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            bukkit.when(() -> Bukkit.getWorld("world5")).thenReturn(lobby);
            when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(call -> {
                later.add(call.getArgument(1));
                return mock(BukkitTask.class);
            });
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
                authPoll = call.getArgument(1);
                return mock(BukkitTask.class);
            });
            set(manager, "plugin", plugin);
            set(manager, "repository", repository);
            set(manager, "nLogin", nlogin);
            set(manager, "sessions", sessions);
            set(manager, "activeByPlayer", new HashMap<>());
            set(manager, "authFallbackTasks", new HashMap<>());
            set(manager, "postAuthDeathTasks", new HashMap<>());
            set(manager, "requestedRespawns", new java.util.HashSet<>());
            } catch (Exception | Error failure) {
                bukkit.close();
                throw failure;
            }
        }

        void dieAndQuit() {
            manager.onOnlineDeath(death());
            manager.onQuit(quit());
        }

        void runLater() {
            assertFalse(later.isEmpty(), "Expected a deferred respawn/lobby completion");
            later.removeFirst().run();
        }

        PlayerDeathEvent death() {
            PlayerDeathEvent event = mock(PlayerDeathEvent.class);
            when(event.getEntity()).thenReturn(player);
            return event;
        }

        PlayerQuitEvent quit() {
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        PlayerJoinEvent join() {
            PlayerJoinEvent event = mock(PlayerJoinEvent.class);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        PlayerRespawnEvent respawn() {
            PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
            when(event.getPlayer()).thenReturn(player);
            return event;
        }

        @Override
        public void close() {
            bukkit.close();
        }
    }
}
