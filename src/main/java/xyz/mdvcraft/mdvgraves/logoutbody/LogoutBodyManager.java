package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import xyz.mdvcraft.mdvgraves.MDVGravesPlugin;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class LogoutBodyManager implements Listener {
    private final MDVGravesPlugin plugin;
    private final LogoutBodyRepository repository;
    private final ProtectedItemPolicy protectedItems;
    private final LibsDisguisesBridge disguises;
    private final MDVAspectosAppearanceHint appearanceHint;
    private final NLoginBridge nLogin;

    private final Map<UUID, LogoutBodySession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveLogoutBody> activeByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> ownerByEntity = new ConcurrentHashMap<>();
    private final Map<UUID, DamageAttribution> lastDamage = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> authFallbackTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> postAuthDeathTasks = new ConcurrentHashMap<>();

    private final NamespacedKey bodyOwnerKey;
    private volatile boolean shuttingDown;

    public LogoutBodyManager(MDVGravesPlugin plugin, Connection connection) {
        this.plugin = plugin;
        this.repository = new LogoutBodyRepository(connection);
        this.protectedItems = new ProtectedItemPolicy(plugin);
        this.disguises = new LibsDisguisesBridge(plugin);
        this.appearanceHint = new MDVAspectosAppearanceHint(plugin);
        this.nLogin = new NLoginBridge(plugin);
        this.bodyOwnerKey = new NamespacedKey(plugin, "logout_body_owner");
    }

    public void enable() throws Exception {
        repository.createSchema();
        recoverPersistedSessions();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        cleanupLoadedOrphanBodies();
        nLogin.register(this::onNLoginAuthenticated);

        if (isEnabled() && !disguises.isAvailable()) {
            plugin.getLogger().warning(
                    "logout-body está activado pero LibsDisguises no está disponible. "
                            + "La mecánica funcionará, pero el cuerpo se verá como la entidad base.");
        }
    }

    public void reload() {
        protectedItems.reload();
    }

    public void shutdown() {
        shuttingDown = true;
        for (BukkitTask task : authFallbackTasks.values())
            task.cancel();
        authFallbackTasks.clear();
        for (BukkitTask task : postAuthDeathTasks.values())
            task.cancel();
        postAuthDeathTasks.clear();

        // Un apagado/reload nunca puede contar como una muerte offline. Todo cuerpo
        // todavía vivo se resuelve de forma segura y el playerdata normal conserva
        // el inventario.
        for (ActiveLogoutBody active : new ArrayList<>(activeByPlayer.values())) {
            removeRuntimeBody(active);
            LogoutBodySession session = sessions.get(active.playerUuid());
            if (session != null && session.state() == LogoutBodyState.BODY_ACTIVE) {
                try {
                    repository.delete(session.playerUuid());
                    sessions.remove(session.playerUuid());
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "No se pudo cerrar de forma segura una sesión de cuerpo durante el apagado.", ex);
                }
            }
        }
        activeByPlayer.clear();
        ownerByEntity.clear();
        lastDamage.clear();
    }

    public int getActiveBodyCount() {
        return activeByPlayer.size();
    }

    public int getPendingSessionCount() {
        return sessions.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!shouldCreateBody(player))
            return;

        UUID uuid = player.getUniqueId();
        // Si ya existe cualquier sesión, no se crea otra. Esto cubre reintentos de
        // autenticación y elimina una vía de duplicación.
        if (sessions.containsKey(uuid) || activeByPlayer.containsKey(uuid))
            return;

        try {
            createBodyFor(player);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "No se pudo crear el cuerpo desconectado de " + player.getName()
                            + ". El jugador conservará su inventario normal.", ex);
            try {
                repository.delete(uuid);
            } catch (Exception ignored) {
            }
            sessions.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LogoutBodySession session = sessions.get(player.getUniqueId());
        if (session == null)
            return;

        if (session.state() == LogoutBodyState.BODY_ACTIVE) {
            reclaimActiveBody(player, session);
            session = sessions.get(player.getUniqueId());
        }

        // Si el cuerpo ya murió, nunca dejamos que el playerdata antiguo permanezca
        // utilizable durante la pantalla de login. nLogin puede ocultar/restaurar stats
        // temporalmente, por eso se vuelve a aplicar el snapshot protegido tras autenticar.
        if (session != null && session.state() == LogoutBodyState.GRAVE_CREATED) {
            prepareOfflineDeathInventory(player, session);
        }

        // Si nLogin está presente, esperamos a que termine la autenticación para
        // ganar a su teleport de "última posición". El fallback consulta la propia API
        // para cubrir configuraciones donde el evento no pudiera registrarse.
        scheduleAuthenticationFallback(player);
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingDeathTeleport(PlayerTeleportEvent event) {
        if (!plugin.getConfig().getBoolean("logout-body.nlogin.lock-to-lobby-until-applied", true))
            return;

        LogoutBodySession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.state() != LogoutBodyState.GRAVE_CREATED)
            return;

        Location to = event.getTo();
        String lobbyName = plugin.getConfig().getString("logout-body.death.lobby-world", "world5");
        if (to == null || to.getWorld() == null || lobbyName == null)
            return;

        // Se permite entrar al lobby (incluido el teleport de autenticación), pero
        // mientras la muerte no se haya aplicado no se permite volver al survival.
        if (!to.getWorld().getName().equalsIgnoreCase(lobbyName))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingDeathInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && hasPendingOfflineDeath(player.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingDeathInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && hasPendingOfflineDeath(player.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingDeathDrop(PlayerDropItemEvent event) {
        if (hasPendingOfflineDeath(event.getPlayer().getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingDeathSwap(PlayerSwapHandItemsEvent event) {
        if (hasPendingOfflineDeath(event.getPlayer().getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPendingDeathPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && hasPendingOfflineDeath(player.getUniqueId()))
            event.setCancelled(true);
    }

    /**
     * Un cuerpo de desconexión nunca puede adquirir un objetivo.
     * La IA permanece encendida únicamente para conservar la física vanilla.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBodyTarget(EntityTargetLivingEntityEvent event) {
        if (!ownerByEntity.containsKey(event.getEntity().getUniqueId()))
            return;

        event.setCancelled(true);
        event.setTarget(null);

        if (event.getEntity() instanceof Mob mob)
            mob.setTarget(null);
    }

    /**
     * Defensa adicional: aunque otro plugin vuelva a darle un objetivo al Husk,
     * el cuerpo jamás puede infligir daño a ninguna entidad.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBodyOutgoingDamage(EntityDamageByEntityEvent event) {
        if (ownerByEntity.containsKey(event.getDamager().getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBodyFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL)
            return;
        if (!ownerByEntity.containsKey(event.getEntity().getUniqueId()))
            return;
        if (!plugin.getConfig().getBoolean("logout-body.entity.fall-damage", true))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBodyDamage(EntityDamageEvent event) {
        UUID owner = ownerByEntity.get(event.getEntity().getUniqueId());
        if (owner == null)
            return;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            lastDamage.put(owner, resolveAttribution(byEntity.getDamager()));
            return;
        }

        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing != null && !causing.getUniqueId().equals(event.getEntity().getUniqueId()))
            lastDamage.put(owner, resolveAttribution(causing));
        else
            lastDamage.remove(owner);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBodyDeath(EntityDeathEvent event) {
        UUID owner = ownerByEntity.get(event.getEntity().getUniqueId());
        if (owner == null)
            return;

        event.getDrops().clear();
        event.setDroppedExp(0);

        ActiveLogoutBody active = activeByPlayer.remove(owner);
        ownerByEntity.remove(event.getEntity().getUniqueId());
        if (active != null) {
            if (active.expiryTask() != null)
                active.expiryTask().cancel();
            releaseChunk(active.chunk());
        }

        LogoutBodySession session = sessions.get(owner);
        if (session == null || session.state() != LogoutBodyState.BODY_ACTIVE)
            return;

        Location deathLocation = event.getEntity().getLocation().clone();
        processBodyDeath(session, deathLocation, lastDamage.remove(owner));
    }

    private void cleanupLoadedOrphanBodies() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    String raw = entity.getPersistentDataContainer().get(bodyOwnerKey, PersistentDataType.STRING);
                    if (raw != null && !raw.isBlank())
                        entity.remove();
                }
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Limpia cuerpos huérfanos que pudieran haber quedado guardados por un crash.
        for (Entity entity : event.getChunk().getEntities()) {
            String raw = entity.getPersistentDataContainer().get(bodyOwnerKey, PersistentDataType.STRING);
            if (raw == null || raw.isBlank())
                continue;
            UUID owner;
            try {
                owner = UUID.fromString(raw);
            } catch (IllegalArgumentException ex) {
                entity.remove();
                continue;
            }
            ActiveLogoutBody active = activeByPlayer.get(owner);
            if (active == null || !active.entity().getUniqueId().equals(entity.getUniqueId()))
                entity.remove();
        }
    }

    private boolean shouldCreateBody(Player player) {
        if (!isEnabled() || shuttingDown || isServerStopping())
            return false;
        if (player == null || !player.isOnline() || player.isDead() || player.getHealth() <= 0.0)
            return false;

        if (plugin.getConfig().getBoolean("logout-body.safety.ignore-creative-mode", true)
                && player.getGameMode() == GameMode.CREATIVE)
            return false;

        String bypassPermission = plugin.getConfig().getString(
                "logout-body.safety.bypass-permission", "mdvgraves.logoutbody.bypass");
        if (bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission))
            return false;

        String world = player.getWorld().getName();
        return plugin.getConfig().getStringList("logout-body.allowed-worlds").stream()
                .anyMatch(name -> name.equalsIgnoreCase(world));
    }

    private boolean hasPendingOfflineDeath(UUID playerUuid) {
        LogoutBodySession session = sessions.get(playerUuid);
        return session != null && (session.state() == LogoutBodyState.GRAVE_CREATED
                || session.state() == LogoutBodyState.DEATH_PROCESSING);
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("logout-body.enabled", true)
                && plugin.getConfig().getBoolean("settings.enabled", true);
    }

    private void createBodyFor(Player player) throws Exception {
        UUID uuid = player.getUniqueId();
        PlayerInventorySnapshot inventory = PlayerInventorySnapshot.capture(player);
        boolean keepAll = protectedItems.keepsWholeInventory(player);
        PlayerInventorySnapshot protectedInventory = keepAll
                ? inventory
                : inventory.filtered(item -> protectedItems.isProtected(item, uuid));

        Location location = player.getLocation().clone();
        double maxHealth = resolveMaxHealth(player);
        long now = Instant.now().getEpochSecond();
        long duration = Math.max(1L, plugin.getConfig().getLong("logout-body.duration-seconds", 10L));

        LogoutBodySession session = new LogoutBodySession(
                uuid,
                player.getName(),
                LogoutBodyState.BODY_ACTIVE,
                location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(),
                player.getHealth(),
                maxHealth,
                player.getAbsorptionAmount(),
                player.getTotalExperience(),
                player.getLevel(),
                player.getExp(),
                inventory,
                protectedInventory,
                keepAll,
                plugin.isPrivateGraveOwner(player),
                plugin.captureGraveTexture(player),
                null,
                false,
                now,
                now + duration);

        // Persistencia antes de exponer una entidad atacable.
        saveSession(session);

        Mob body = spawnBody(player, session);
        if (body == null)
            throw new IllegalStateException("No se pudo crear la entidad base del cuerpo.");

        Chunk chunk = body.getLocation().getChunk();
        try {
            chunk.addPluginChunkTicket(plugin);
        } catch (Throwable ignored) {
        }

        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(plugin,
                () -> expireBody(uuid), duration * 20L);
        ActiveLogoutBody active = new ActiveLogoutBody(uuid, body, expiry, chunk);
        activeByPlayer.put(uuid, active);
        ownerByEntity.put(body.getUniqueId(), uuid);
    }

    private Mob spawnBody(Player player, LogoutBodySession session) {
        EntityType requested;
        try {
            requested = EntityType.valueOf(
                    plugin.getConfig().getString("logout-body.entity.base-type", "HUSK")
                            .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            requested = EntityType.HUSK;
        }

        Entity spawned = player.getWorld().spawnEntity(player.getLocation(), requested);
        Mob body;
        if (spawned instanceof Mob mob) {
            body = mob;
        } else {
            spawned.remove();
            spawned = player.getWorld().spawnEntity(player.getLocation(), EntityType.HUSK);
            if (!(spawned instanceof Mob fallback)) {
                spawned.remove();
                return null;
            }
            body = fallback;
        }

        body.getPersistentDataContainer().set(bodyOwnerKey, PersistentDataType.STRING,
                player.getUniqueId().toString());
        // La IA debe permanecer activada para conservar la física vanilla del mob
        // (gravedad/caída en este servidor). Sin embargo el cuerpo no debe comportarse
        // como un Husk real: se deja sin awareness, sin target y se bloquea cualquier
        // ataque saliente mediante eventos.
        boolean bodyAi = plugin.getConfig().getBoolean("logout-body.entity.ai", true);
        body.setAI(bodyAi);
        if (bodyAi) {
            body.setAware(false);
            body.setTarget(null);
        }

        body.setCollidable(true);
        body.setSilent(false);
        body.setPersistent(true);
        body.setRemoveWhenFarAway(false);
        body.setCanPickupItems(false);
        body.setGravity(plugin.getConfig().getBoolean("logout-body.entity.gravity", true));
        body.setCustomNameVisible(false);

        if (plugin.getConfig().getBoolean("logout-body.entity.copy-health", true)) {
            AttributeInstance maxHealth = body.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null)
                maxHealth.setBaseValue(Math.max(1.0, session.maxHealth()));
            body.setHealth(Math.max(0.1, Math.min(session.health(), resolveMaxHealth(body))));
        }

        if (plugin.getConfig().getBoolean("logout-body.entity.copy-absorption", true)) {
            AttributeInstance maxAbsorption = body.getAttribute(Attribute.MAX_ABSORPTION);
            if (maxAbsorption != null && session.absorption() > maxAbsorption.getValue())
                maxAbsorption.setBaseValue(session.absorption());
            body.setAbsorptionAmount(Math.max(0.0, session.absorption()));
        }

        if (!plugin.getConfig().getBoolean("logout-body.entity.allow-knockback", true)) {
            AttributeInstance knockbackResistance = body.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (knockbackResistance != null)
                knockbackResistance.setBaseValue(1.0);
        }

        if (plugin.getConfig().getBoolean("logout-body.entity.copy-equipment", true))
            copyVisualEquipment(body, session.inventory());

        boolean showName = plugin.getConfig().getBoolean("logout-body.appearance.show-player-name", true);
        boolean disguised = false;
        if (plugin.getConfig().getBoolean("logout-body.appearance.use-libsdisguises", true)) {
            String fallbackSkin = appearanceHint.resolveSkinName(player);
            disguised = disguises.disguiseAsPlayer(body, player, fallbackSkin, showName);
        }
        if (!disguised && showName) {
            body.setCustomName(player.getName());
            body.setCustomNameVisible(true);
        }
        return body;
    }

    private void copyVisualEquipment(Mob body, PlayerInventorySnapshot inventory) {
        EntityEquipment equipment = body.getEquipment();
        if (equipment == null)
            return;

        ItemStack[] armor = inventory.getArmorVisual();
        equipment.setArmorContents(armor);
        equipment.setItemInMainHand(orAir(inventory.getMainHandVisual()));
        equipment.setItemInOffHand(orAir(inventory.getOffHandVisual()));

        try {
            equipment.setHelmetDropChance(0.0f);
            equipment.setChestplateDropChance(0.0f);
            equipment.setLeggingsDropChance(0.0f);
            equipment.setBootsDropChance(0.0f);
            equipment.setItemInMainHandDropChance(0.0f);
            equipment.setItemInOffHandDropChance(0.0f);
        } catch (Throwable ignored) {
        }
    }

    private void expireBody(UUID playerUuid) {
        ActiveLogoutBody active = activeByPlayer.remove(playerUuid);
        if (active == null)
            return;
        ownerByEntity.remove(active.entity().getUniqueId());
        lastDamage.remove(playerUuid);
        removeRuntimeBody(active);

        LogoutBodySession session = sessions.get(playerUuid);
        if (session == null || session.state() != LogoutBodyState.BODY_ACTIVE)
            return;
        try {
            // BODY_SAFE se persiste antes de borrar; si el delete falla, el siguiente
            // arranque sabe que no debe castigar al jugador.
            saveSession(session.withState(LogoutBodyState.BODY_SAFE));
            deleteSession(playerUuid);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "No se pudo cerrar la sesión segura del cuerpo de " + session.playerName(), ex);
        }
    }

    private void reclaimActiveBody(Player player, LogoutBodySession session) {
        ActiveLogoutBody active = activeByPlayer.remove(player.getUniqueId());
        if (active == null) {
            try {
                saveSession(session.withState(LogoutBodyState.RESTORE_PENDING));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "No se pudo preparar la restauración de " + player.getName(), ex);
            }
            return;
        }

        Mob body = active.entity();
        Location location = body.getLocation().clone();
        double health = body.isDead() ? 0.1 : Math.max(0.1, body.getHealth());
        double maxHealth = resolveMaxHealth(body);
        double absorption = Math.max(0.0, body.getAbsorptionAmount());

        ownerByEntity.remove(body.getUniqueId());
        lastDamage.remove(player.getUniqueId());
        removeRuntimeBody(active);

        try {
            saveSession(session.withReconnectData(location, health, maxHealth, absorption));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "No se pudo persistir la reconexión segura de " + player.getName(), ex);
        }
    }

    private void processBodyDeath(LogoutBodySession session, Location deathLocation, DamageAttribution attribution) {
        try {
            List<ItemStack> graveItems;
            if (session.keepAllInventory()) {
                graveItems = List.of();
            } else {
                graveItems = session.inventory().itemsExcluding(
                        item -> protectedItems.isProtected(item, session.playerUuid()));
            }

            UUID graveId = null;
            boolean needsGrave = plugin.getConfig().getBoolean("logout-body.death.create-grave", true)
                    && !graveItems.isEmpty();

            if (needsGrave) {
                graveId = UUID.randomUUID();
                // El ID queda persistido ANTES de crear la tumba. Si el servidor cae
                // justo después del INSERT de la tumba, el recovery puede comprobarla
                // y no restaurará el snapshot completo.
                saveSession(session.withStateAndGrave(LogoutBodyState.DEATH_PROCESSING, graveId));
                boolean created = plugin.createOfflineGrave(
                        graveId,
                        session.playerUuid(),
                        session.playerName(),
                        deathLocation,
                        graveItems,
                        session.ownerProtected(),
                        session.graveTexture());
                if (!created) {
                    plugin.getLogger().warning("Falló la tumba del cuerpo de " + session.playerName()
                            + "; por seguridad se restaurará el inventario completo al volver.");
                    saveSession(session.withStateAndGrave(LogoutBodyState.RESTORE_PENDING, null));
                    return;
                }
            }

            saveSession(session.withStateAndGrave(LogoutBodyState.GRAVE_CREATED, graveId));
            broadcastOfflineDeath(session.playerName(), attribution);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "No se pudo procesar la muerte offline de " + session.playerName()
                            + ". Se restaurará el inventario para evitar pérdidas/duplicaciones.", ex);
            try {
                saveSession(session.withStateAndGrave(LogoutBodyState.RESTORE_PENDING, null));
            } catch (Exception nested) {
                plugin.getLogger().log(Level.SEVERE,
                        "Tampoco se pudo persistir el rollback seguro de la sesión.", nested);
            }
        }
    }

    private void onNLoginAuthenticated(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> scheduleFinalization(player));
    }

    private void scheduleFinalization(Player player) {
        if (player == null || !player.isOnline() || !sessions.containsKey(player.getUniqueId()))
            return;
        cancelAuthFallback(player.getUniqueId());
        long delay = Math.max(0L,
                plugin.getConfig().getLong("logout-body.nlogin.after-auth-delay-ticks", 2L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> finalizePendingSession(player.getUniqueId()), delay);
    }

    private void scheduleAuthenticationFallback(Player player) {
        UUID uuid = player.getUniqueId();
        cancelAuthFallback(uuid);

        if (!nLogin.isNLoginEnabled()) {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin,
                    () -> finalizePendingSession(uuid), 1L);
            authFallbackTasks.put(uuid, task);
            return;
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline() || !sessions.containsKey(uuid)) {
                cancelAuthFallback(uuid);
                return;
            }
            if (nLogin.isAuthenticated(online))
                scheduleFinalization(online);
        }, 2L, 2L);
        authFallbackTasks.put(uuid, task);
    }

    private void finalizePendingSession(UUID uuid) {
        cancelAuthFallback(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline())
            return;
        LogoutBodySession session = sessions.get(uuid);
        if (session == null)
            return;

        switch (session.state()) {
            case RESTORE_PENDING -> restoreSurvivor(player, session);
            case GRAVE_CREATED -> applyOfflineDeath(player, session);
            case BODY_SAFE, RESTORED -> {
                try {
                    deleteSession(uuid);
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "No se pudo limpiar una sesión resuelta.", ex);
                }
            }
            default -> {
                // BODY_ACTIVE se resuelve en onJoin. DEATH_PROCESSING solo debería
                // existir tras un crash y se normaliza al iniciar.
            }
        }
    }

    private void restoreSurvivor(Player player, LogoutBodySession session) {
        try {
            session.inventory().restore(player);
            player.setTotalExperience(Math.max(0, session.totalExperience()));
            player.setLevel(Math.max(0, session.level()));
            player.setExp(Math.max(0.0f, Math.min(1.0f, session.exp())));

            double max = resolveMaxHealth(player);
            player.setHealth(Math.max(0.1, Math.min(session.health(), max)));
            try {
                AttributeInstance maxAbsorption = player.getAttribute(Attribute.MAX_ABSORPTION);
                if (maxAbsorption != null && session.absorption() > maxAbsorption.getValue())
                    maxAbsorption.setBaseValue(session.absorption());
                player.setAbsorptionAmount(Math.max(0.0, session.absorption()));
            } catch (Throwable ignored) {
            }

            Location target = session.location();
            if (target != null)
                player.teleport(target);

            deleteSession(player.getUniqueId());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "No se pudo restaurar el cuerpo superviviente de " + player.getName(), ex);
        }
    }


    private void prepareOfflineDeathInventory(Player player, LogoutBodySession session) {
        try {
            player.closeInventory();
            session.protectedInventory().restore(player);
            if (plugin.getConfig().getBoolean("logout-body.death.reset-experience", true)) {
                player.setTotalExperience(0);
                player.setLevel(0);
                player.setExp(0.0f);
            }
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING,
                    "No se pudo sanear inmediatamente el inventario de " + player.getName()
                            + " tras una muerte offline; se reintentará después de nLogin.", ex);
        }
    }

    private void applyOfflineDeath(Player player, LogoutBodySession session) {
        try {
            player.closeInventory();
            session.protectedInventory().restore(player);

            if (plugin.getConfig().getBoolean("logout-body.death.reset-experience", true)) {
                player.setTotalExperience(0);
                player.setLevel(0);
                player.setExp(0.0f);
            }

            player.setFireTicks(0);
            player.setFallDistance(0.0f);
            try {
                player.setAbsorptionAmount(0.0);
            } catch (Throwable ignored) {
            }
            double max = resolveMaxHealth(player);
            player.setHealth(Math.max(1.0, max));

            World lobby = Bukkit.getWorld(
                    plugin.getConfig().getString("logout-body.death.lobby-world", "world5"));
            if (lobby == null) {
                plugin.getLogger().severe("No existe el mundo lobby configurado para logout-body: "
                        + plugin.getConfig().getString("logout-body.death.lobby-world", "world5"));
                // No borramos la sesión: en el siguiente login se vuelve a intentar.
                return;
            }

            Location target = lobby.getSpawnLocation();
            boolean teleported = player.teleport(target);
            if (!teleported) {
                plugin.getLogger().warning("No se pudo dejar a " + player.getName()
                        + " en el lobby después de su muerte offline.");
                return;
            }

            sendOfflineDeathNoticeOnce(player, session);

            // No se elimina la sesión en este instante. nLogin y otros plugins de
            // autenticación pueden tener tareas diferidas que restauran ubicación o
            // estado algunos ticks DESPUÉS de AuthenticateEvent. Durante esta pequeña
            // ventana GRAVE_CREATED sigue bloqueando teleports fuera del lobby y las
            // operaciones de inventario. Al final se reaplica el resultado de muerte
            // una última vez y recién entonces se elimina la sesión persistente.
            schedulePostAuthDeathCompletion(player.getUniqueId());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "No se pudo aplicar la muerte offline de " + player.getName(), ex);
        }
    }


    private void sendOfflineDeathNoticeOnce(Player player, LogoutBodySession session) throws Exception {
        if (session.deathNoticeSent())
            return;

        // Se persiste ANTES de enviar el chat. Así, múltiples callbacks de nLogin
        // durante la misma autenticación nunca pueden duplicar el aviso.
        LogoutBodySession marked = session.withDeathNoticeSent(true);
        saveSession(marked);

        String messagePath = marked.graveId() == null
                ? "messages.logout-body-died-no-grave"
                : "messages.logout-body-died";
        plugin.send(player, messagePath,
                Map.of("owner", player.getName(),
                        "grave", marked.graveId() == null ? "no" : "sí"));
    }

    private void schedulePostAuthDeathCompletion(UUID uuid) {
        cancelPostAuthDeath(uuid);
        long holdTicks = Math.max(1L,
                plugin.getConfig().getLong("logout-body.nlogin.post-auth-lock-ticks", 60L));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin,
                () -> completeOfflineDeath(uuid), holdTicks);
        postAuthDeathTasks.put(uuid, task);
    }

    private void completeOfflineDeath(UUID uuid) {
        postAuthDeathTasks.remove(uuid);
        LogoutBodySession session = sessions.get(uuid);
        if (session == null || session.state() != LogoutBodyState.GRAVE_CREATED)
            return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            // Se conserva GRAVE_CREATED: en el próximo login se reaplica de forma
            // idempotente antes de permitir el inventario o salir del lobby.
            return;
        }

        try {
            player.closeInventory();
            session.protectedInventory().restore(player);
            if (plugin.getConfig().getBoolean("logout-body.death.reset-experience", true)) {
                player.setTotalExperience(0);
                player.setLevel(0);
                player.setExp(0.0f);
            }
            player.setFireTicks(0);
            player.setFallDistance(0.0f);
            try {
                player.setAbsorptionAmount(0.0);
            } catch (Throwable ignored) {
            }
            player.setHealth(Math.max(1.0, resolveMaxHealth(player)));

            World lobby = Bukkit.getWorld(
                    plugin.getConfig().getString("logout-body.death.lobby-world", "world5"));
            if (lobby == null) {
                plugin.getLogger().severe("No existe el mundo lobby configurado para logout-body: "
                        + plugin.getConfig().getString("logout-body.death.lobby-world", "world5"));
                return;
            }
            if (!player.teleport(lobby.getSpawnLocation())) {
                plugin.getLogger().warning("No se pudo reafirmar el lobby de " + player.getName()
                        + " al cerrar su muerte offline; la sesión seguirá persistida.");
                return;
            }
            deleteSession(uuid);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "No se pudo cerrar el bloqueo post-login de la muerte offline de " + player.getName()
                            + ". La sesión seguirá persistida para reintentarlo.", ex);
        }
    }

    private void recoverPersistedSessions() throws Exception {
        sessions.clear();
        for (LogoutBodySession session : repository.loadAll()) {
            switch (session.state()) {
                case BODY_ACTIVE, BODY_SAFE -> {
                    // No hay entidad fiable tras un restart: se considera superviviente
                    // y se deja que el playerdata normal conserve todo.
                    repository.delete(session.playerUuid());
                }
                case DEATH_PROCESSING -> {
                    if (session.graveId() != null && plugin.hasGrave(session.graveId())) {
                        LogoutBodySession recovered = session.withState(LogoutBodyState.GRAVE_CREATED);
                        repository.save(recovered);
                        sessions.put(recovered.playerUuid(), recovered);
                    } else {
                        LogoutBodySession recovered = session.withStateAndGrave(
                                LogoutBodyState.RESTORE_PENDING, null);
                        repository.save(recovered);
                        sessions.put(recovered.playerUuid(), recovered);
                    }
                }
                case RESTORED -> repository.delete(session.playerUuid());
                case RESTORE_PENDING, GRAVE_CREATED -> sessions.put(session.playerUuid(), session);
            }
        }
    }

    private void saveSession(LogoutBodySession session) throws Exception {
        repository.save(session);
        sessions.put(session.playerUuid(), session);
    }

    private void deleteSession(UUID uuid) throws Exception {
        repository.delete(uuid);
        sessions.remove(uuid);
    }

    private void removeRuntimeBody(ActiveLogoutBody active) {
        if (active == null)
            return;
        if (active.expiryTask() != null)
            active.expiryTask().cancel();
        Mob body = active.entity();
        if (body != null) {
            disguises.removeDisguise(body);
            if (body.isValid())
                body.remove();
        }
        releaseChunk(active.chunk());
    }

    private void releaseChunk(Chunk chunk) {
        if (chunk == null)
            return;
        try {
            chunk.removePluginChunkTicket(plugin);
        } catch (Throwable ignored) {
        }
    }

    private void cancelAuthFallback(UUID uuid) {
        BukkitTask task = authFallbackTasks.remove(uuid);
        if (task != null)
            task.cancel();
    }

    private void cancelPostAuthDeath(UUID uuid) {
        BukkitTask task = postAuthDeathTasks.remove(uuid);
        if (task != null)
            task.cancel();
    }

    private void broadcastOfflineDeath(String playerName, DamageAttribution attribution) {
        if (!plugin.getConfig().getBoolean("logout-body.death.broadcast-death-message", true))
            return;

        String raw;
        Map<String, String> replacements = new HashMap<>();
        replacements.put("player", playerName);

        if (attribution != null && attribution.kind() == DamageKind.PLAYER) {
            raw = plugin.getConfig().getString("logout-body.death.messages.player",
                    "&c{player} fue asesinado por {killer}.");
            replacements.put("killer", attribution.name());
        } else if (attribution != null && attribution.kind() == DamageKind.MOB) {
            raw = plugin.getConfig().getString("logout-body.death.messages.mob",
                    "&c{player} fue abatido por {killer}.");
            replacements.put("killer", attribution.name());
        } else {
            raw = plugin.getConfig().getString("logout-body.death.messages.environment",
                    "&c{player} murió mientras estaba desconectado.");
        }

        String message = raw == null ? "" : raw;
        for (Map.Entry<String, String> entry : replacements.entrySet())
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        if (!message.isBlank())
            Bukkit.broadcastMessage(plugin.color(message));
    }

    private DamageAttribution resolveAttribution(Entity damager) {
        if (damager instanceof Player player)
            return new DamageAttribution(DamageKind.PLAYER, player.getName());

        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player)
                return new DamageAttribution(DamageKind.PLAYER, player.getName());
            if (shooter instanceof Entity entity)
                return new DamageAttribution(DamageKind.MOB, displayEntityName(entity));
        }

        if (damager instanceof TNTPrimed tnt && tnt.getSource() != null) {
            Entity source = tnt.getSource();
            if (source instanceof Player player)
                return new DamageAttribution(DamageKind.PLAYER, player.getName());
            return new DamageAttribution(DamageKind.MOB, displayEntityName(source));
        }

        if (damager instanceof LivingEntity living)
            return new DamageAttribution(DamageKind.MOB, displayEntityName(living));

        return new DamageAttribution(DamageKind.OTHER, displayEntityName(damager));
    }

    private String displayEntityName(Entity entity) {
        String custom = entity.getCustomName();
        if (custom != null && !custom.isBlank())
            return ChatColor.stripColor(custom);
        String name = entity.getName();
        if (name != null && !name.isBlank())
            return name;
        return entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private double resolveMaxHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? Math.max(1.0, entity.getHealth()) : Math.max(1.0, attribute.getValue());
    }

    private ItemStack orAir(ItemStack item) {
        return item == null ? new ItemStack(Material.AIR) : item.clone();
    }

    private boolean isServerStopping() {
        try {
            Method method = Bukkit.class.getMethod("isStopping");
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private enum DamageKind {
        PLAYER,
        MOB,
        OTHER
    }

    private record DamageAttribution(DamageKind kind, String name) {
    }
}
