package xyz.mdvcraft.mdvgraves;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import xyz.mdvcraft.mdvgraves.commands.Commands;
import xyz.mdvcraft.mdvgraves.logoutbody.LogoutBodyManager;

import java.io.*;
import java.lang.reflect.Method;
import java.sql.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class MDVGravesPlugin extends JavaPlugin implements Listener {
    private static final Set<String> THIN_REPLACEABLE_MATERIALS = Set.of(
            "SNOW", "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH",
            "VINE", "CAVE_VINES", "CAVE_VINES_PLANT", "WEEPING_VINES",
            "WEEPING_VINES_PLANT", "TWISTING_VINES", "TWISTING_VINES_PLANT",
            "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT", "GLOW_LICHEN",
            "HANGING_ROOTS", "NETHER_SPROUTS", "CRIMSON_ROOTS", "WARPED_ROOTS",

            // Crops
            "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART",
            "COCOA", "MELON_STEM", "PUMPKIN_STEM",
            "ATTACHED_MELON_STEM", "ATTACHED_PUMPKIN_STEM",
            "SWEET_BERRY_BUSH", "TORCHFLOWER_CROP", "PITCHER_CROP",

            // Other plant-like blocks
            "BAMBOO_SAPLING", "LILY_PAD", "PINK_PETALS", "WILDFLOWERS",
            "LEAF_LITTER", "BUSH", "FIREFLY_BUSH", "SHORT_DRY_GRASS",
            "TALL_DRY_GRASS", "PALE_HANGING_MOSS", "PALE_MOSS_CARPET",
            "MOSS_CARPET", "SCULK_VEIN", "COBWEB",

            // Flowers
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET",
            "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE",
            "TORCHFLOWER", "OPEN_EYEBLOSSOM", "CLOSED_EYEBLOSSOM",
            "SPORE_BLOSSOM", "SMALL_DRIPLEAF");

    private NamespacedKey graveKey;
    private Connection connection;
    private final Map<UUID, GraveMeta> graves = new ConcurrentHashMap<>();
    private final Map<BlockKey, UUID> gravesByBlock = new ConcurrentHashMap<>();
    // Cada bolsa abierta utiliza un único inventario canónico compartido. Incluso
    // si
    // single-viewer-lock se desactiva, nunca se crean dos copias visuales del mismo
    // loot.
    private final Map<UUID, Set<UUID>> activeViewers = new HashMap<>();
    private final Map<UUID, Inventory> openGraveInventories = new HashMap<>();
    private final Set<UUID> forcedClosingGraves = new HashSet<>();
    private BukkitTask cleanupTask;
    private BukkitTask integrityTask;
    private final Map<UUID, Long> graveBackCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deathFruitUseLocks = new ConcurrentHashMap<>();
    private final Map<UUID, PendingDeathFruitUse> pendingDeathFruitUses = new ConcurrentHashMap<>();
    private final Map<UUID, GraveBackRequest> activeGraveBacks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> graveBackTransportLocks = new ConcurrentHashMap<>();
    private final Map<Chunk, Integer> graveBackChunkTickets = new HashMap<>();
    private Method nbtItemGetMethod;
    private Method nbtItemGetStringMethod;
    private boolean mmoItemsBridgeReady;
    private boolean mmoItemsBridgeWarningLogged;
    private String deathFruitExpectedType = "CONSUMABLE";
    private String deathFruitExpectedId = "FRUTA_DE_LA_MUERTE";
    private LogoutBodyManager logoutBodyManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        graveKey = new NamespacedKey(this, "grave_id");
        try {
            openDatabase();
            createSchema();
            loadActiveGraves();
            logoutBodyManager = new LogoutBodyManager(this, connection);
            logoutBodyManager.enable();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "No se pudo iniciar SQLite. MDVGraves será desactivado.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        Commands commands = new Commands(this);

        getServer().getPluginManager().registerEvents(commands, this);
        Objects.requireNonNull(getCommand("mdvgraves")).setExecutor(commands);
        Objects.requireNonNull(getCommand("mdvgraves")).setTabCompleter(commands);
        Objects.requireNonNull(getCommand("graveback")).setExecutor(commands);
        Objects.requireNonNull(getCommand("graveback")).setTabCompleter(commands);
        setupMmoItemsBridge();
        scheduleCleanup();
        scheduleOpenGraveIntegrityGuard();
        getLogger().info("MDVGraves " + getDescription().getVersion() + " activo. Bolsas cargadas: " + graves.size() + ", cuerpos activos: "
                + (logoutBodyManager == null ? 0 : logoutBodyManager.getActiveBodyCount()));
    }

    @Override
    public void onDisable() {
        if (logoutBodyManager != null)
            logoutBodyManager.shutdown();
        if (cleanupTask != null)
            cleanupTask.cancel();
        if (integrityTask != null)
            integrityTask.cancel();
        // Cada bolsa abierta posee un único inventario canónico. Se persiste una sola
        // vez,
        // aunque más de un jugador la estuviera observando.
        for (Map.Entry<UUID, Inventory> entry : new ArrayList<>(openGraveInventories.entrySet())) {
            saveInventoryAndMaybeRemove(entry.getKey(), entry.getValue(), false);
        }
        activeViewers.clear();
        openGraveInventories.clear();
        forcedClosingGraves.clear();
        for (GraveBackRequest request : new ArrayList<>(activeGraveBacks.values()))
            finishGraveBack(request, GraveBackResult.TELEPORT_FAILED);
        deathFruitUseLocks.clear();
        graveBackTransportLocks.clear();
        pendingDeathFruitUses.clear();
        try {
            if (connection != null && !connection.isClosed())
                connection.close();
        } catch (SQLException ignored) {
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        setupMmoItemsBridge();
        if (logoutBodyManager != null)
            logoutBodyManager.reload();
        scheduleCleanup();
        scheduleOpenGraveIntegrityGuard();
    }

    public int getActiveGraveCount() {
        return graves.size();
    }

    public int getActiveLogoutBodyCount() {
        return logoutBodyManager == null ? 0 : logoutBodyManager.getActiveBodyCount();
    }

    public int getPendingLogoutBodySessionCount() {
        return logoutBodyManager == null ? 0 : logoutBodyManager.getPendingSessionCount();
    }

    public boolean hasGrave(UUID graveId) {
        return graveId != null && graves.containsKey(graveId);
    }

    public boolean isPrivateGraveOwner(Player player) {
        return player != null
                && getConfig().getBoolean("utilities.private-graves.enabled", true)
                && player.hasPermission("mdvgraves.private");
    }

    public String captureGraveTexture(Player player) {
        return player == null ? getConfig().getString("textures.default", "") : selectTexture(player);
    }

    public void logCommandFailure(String message, Exception exception) {
        getLogger().log(Level.SEVERE, message, exception);
    }

    private void openDatabase() throws Exception {
        File dbFile = new File(getDataFolder(), "graves.db");
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IOException("No se pudo crear " + getDataFolder());
        }
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=3000");
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS graves (
                      grave_id TEXT PRIMARY KEY,
                      owner_uuid TEXT NOT NULL,
                      owner_name TEXT NOT NULL,
                      world TEXT NOT NULL,
                      x INTEGER NOT NULL,
                      y INTEGER NOT NULL,
                      z INTEGER NOT NULL,
                      created_at INTEGER NOT NULL,
                      first_opened_at INTEGER,
                      expires_at INTEGER NOT NULL,
                      items BLOB NOT NULL,
                      owner_protected INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            try {
                st.executeUpdate("ALTER TABLE graves ADD COLUMN owner_protected INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ex) {
                String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
                if (!message.contains("duplicate column"))
                    throw ex;
            }
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_graves_expires ON graves(expires_at)");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_graves_location ON graves(world,x,y,z)");
        }
    }

    private void loadActiveGraves() throws SQLException {
        graves.clear();
        gravesByBlock.clear();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT grave_id,owner_uuid,owner_name,world,x,y,z,created_at,first_opened_at,expires_at,owner_protected FROM graves")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GraveMeta meta = readMeta(rs);
                    graves.put(meta.id(), meta);
                    gravesByBlock.put(meta.blockKey(), meta.id());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GraveBackRequest request = activeGraveBacks.get(player.getUniqueId());
        if (request != null) {
            boolean keepsInventory = event.getKeepInventory()
                    || (getConfig().getBoolean("utilities.keep-inventory.enabled", true)
                            && player.hasPermission("mdvgraves.keepinventory"));
            // La fruta reservada se devuelve mediante esta muerte para que Paper
            // conserve también su unidad si tiene disable-death-drop de MMOItems.
            request.refundDeathEvent = keepsInventory ? null : event;
            finishGraveBack(request, GraveBackResult.TELEPORT_FAILED);
        }
        if (!getConfig().getBoolean("settings.enabled", true))
            return;

        if (getConfig().getBoolean("utilities.keep-inventory.enabled", true)
                && player.hasPermission("mdvgraves.keepinventory")) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            send(player, "messages.keep-inventory", Map.of());
            return;
        }

        if (!enabledWorld(player.getWorld().getName()))
            return;
        if (event.getKeepInventory() || event.getDrops().isEmpty())
            return;

        List<ItemStack> drops = event.getDrops().stream()
                .filter(Objects::nonNull)
                .filter(item -> !item.getType().isAir())
                .map(ItemStack::clone)
                .toList();
        if (drops.isEmpty())
            return;

        Block target = findPlacementBlock(player.getLocation());
        if (target == null) {
            getLogger().warning("No se encontró espacio seguro para la bolsa de " + player.getName()
                    + ". Se conservaron drops vanilla.");
            return;
        }

        UUID id = UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        long expires = created + Math.max(1L, getConfig().getLong("settings.unopened-expire-hours", 168L)) * 3600L;
        boolean ownerProtected = getConfig().getBoolean("utilities.private-graves.enabled", true)
                && player.hasPermission("mdvgraves.private");
        GraveMeta meta = new GraveMeta(id, player.getUniqueId(), player.getName(), target.getWorld().getName(),
                target.getX(), target.getY(), target.getZ(), created, null, expires, ownerProtected);

        SupportPatch supportPatch = stabilizePlacementSupport(target);
        try {
            byte[] blob = serializeItems(drops);
            placeGraveHead(target, id, player);
            insertGrave(meta, blob);
            graves.put(id, meta);
            gravesByBlock.put(meta.blockKey(), id);
            event.getDrops().clear();
            send(player, "messages.grave-created", Map.of(
                    "x", Integer.toString(target.getX()), "y", Integer.toString(target.getY()), "z",
                    Integer.toString(target.getZ()),
                    "owner", player.getName()));
        } catch (Exception ex) {
            // Rollback visual: nunca quitamos drops si la persistencia falló.
            if (isOurHead(target, id))
                target.setType(Material.AIR, false);
            rollbackSupportPatch(supportPatch);
            getLogger().log(Level.SEVERE,
                    "No se pudo crear la bolsa de " + player.getName() + ". Se conservaron drops vanilla.", ex);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        GraveBackRequest request = activeGraveBacks.get(event.getPlayer().getUniqueId());
        if (request != null)
            finishGraveBack(request, GraveBackResult.TELEPORT_FAILED);
        pendingDeathFruitUses.remove(event.getPlayer().getUniqueId());
        deathFruitUseLocks.remove(event.getPlayer().getUniqueId());
        graveBackTransportLocks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDeathFruitUseStart(PlayerInteractEvent event) {
        if (!getConfig().getBoolean("utilities.death-fruit.enabled", true))
            return;
        if (event.getHand() == null)
            return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        ItemStack item = event.getItem();
        if (!isConfiguredDeathFruit(item))
            return;
        if (activeGraveBacks.containsKey(event.getPlayer().getUniqueId())
                || graveBackTransportLocks.getOrDefault(event.getPlayer().getUniqueId(), 0L) > System.currentTimeMillis()
                || deathFruitUseLocks.getOrDefault(event.getPlayer().getUniqueId(), 0L) > System.currentTimeMillis()) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            return;
        }

        pendingDeathFruitUses.put(event.getPlayer().getUniqueId(),
                snapshotDeathFruitUse(event.getPlayer(), event.getHand(), item, System.currentTimeMillis()));
    }

    private boolean isDeathFruitAwaitingEvaluation(UUID playerId) {
        GraveBackRequest request = activeGraveBacks.get(playerId);
        return request != null && request.fruitUse != null && !request.fruitEvaluated;
    }

    // Solo el tick entre el comando de MMOItems y la reserva necesita este bloqueo:
    // un drop/traslado externo no puede confundirse con el descuento del consumible.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPendingDeathFruitDrop(PlayerDropItemEvent event) {
        if (isDeathFruitAwaitingEvaluation(event.getPlayer().getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPendingDeathFruitSwap(PlayerSwapHandItemsEvent event) {
        if (isDeathFruitAwaitingEvaluation(event.getPlayer().getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPendingDeathFruitInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isDeathFruitAwaitingEvaluation(player.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPendingDeathFruitInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isDeathFruitAwaitingEvaluation(player.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPendingDeathFruitPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isDeathFruitAwaitingEvaluation(player.getUniqueId()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        // Bukkit dispara PlayerInteractEvent una vez por cada mano. Solo procesamos la
        // principal
        // para evitar abrir la misma bolsa y enviar mensajes dos veces.
        if (event.getHand() != EquipmentSlot.HAND)
            return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null)
            return;
        UUID id = physicalGraveId(event.getClickedBlock());
        if (id == null)
            return;
        event.setCancelled(true);

        GraveMeta meta = graves.get(id);
        if (meta == null) {
            event.getClickedBlock().setType(Material.AIR, false);
            return;
        }
        Player player = event.getPlayer();
        if (!canAccessGrave(player, meta)) {
            send(player, meta.ownerProtected() ? "messages.grave-private" : "messages.not-owner",
                    Map.of("owner", meta.ownerName()));
            return;
        }
        pruneInactiveViewers(id);
        Set<UUID> viewers = activeViewers.computeIfAbsent(id, ignored -> new LinkedHashSet<>());
        if (getConfig().getBoolean("settings.single-viewer-lock", true)
                && viewers.stream().anyMatch(viewer -> !viewer.equals(player.getUniqueId()))) {
            send(player, "messages.grave-busy", Map.of("owner", meta.ownerName()));
            return;
        }

        try {
            Inventory inventory = openGraveInventories.get(id);
            if (inventory == null) {
                List<ItemStack> items = loadItems(id);
                int size = inventorySize(items.size());
                String title = color(getConfig().getString("inventory.title", "&8Bolsa perdida de &e{owner}"))
                        .replace("{owner}", meta.ownerName());
                GraveHolder holder = new GraveHolder(id);
                inventory = Bukkit.createInventory(holder, size, title);
                holder.inventory = inventory;
                for (ItemStack item : items)
                    inventory.addItem(item.clone());
                openGraveInventories.put(id, inventory);
            }

            viewers.add(player.getUniqueId());
            markFirstOpened(meta);
            player.openInventory(inventory);
            send(player, "messages.grave-opened", Map.of("owner", meta.ownerName()));
        } catch (Exception ex) {
            viewers.remove(player.getUniqueId());
            if (viewers.isEmpty()) {
                activeViewers.remove(id);
                openGraveInventories.remove(id);
            }
            getLogger().log(Level.SEVERE, "No se pudo abrir la bolsa " + id, ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GraveHolder holder))
            return;
        UUID id = holder.graveId();
        GraveMeta meta = graves.get(id);
        Block physicalBlock = loadedGraveBlock(meta);
        boolean physicalMissing = physicalBlock != null && !isOurHead(physicalBlock, id);

        Set<UUID> viewers = activeViewers.get(id);
        if (viewers != null) {
            viewers.remove(event.getPlayer().getUniqueId());
            if (viewers.isEmpty())
                activeViewers.remove(id);
        }

        // Los cierres forzados persisten el inventario exactamente una vez desde
        // closeAllViewersAndPersist(), no una vez por cada espectador.
        if (forcedClosingGraves.contains(id))
            return;
        if (isBeingViewed(id)) {
            if (physicalMissing)
                verifyOpenGraveNextTick(id);
            return;
        }

        openGraveInventories.remove(id);
        saveInventoryAndMaybeRemove(id, event.getInventory(), true);

        // Cubre el caso extremo en que otro plugin cambió el bloque y el último visor
        // cerró la GUI antes de que alcanzara a ejecutarse el guardián periódico.
        if (physicalMissing && physicalBlock != null && graves.containsKey(id)) {
            restoreGraveHead(physicalBlock, meta);
        }
    }

    private void saveInventoryAndMaybeRemove(UUID id, Inventory inventory, boolean notify) {
        GraveMeta meta = graves.get(id);
        if (meta == null)
            return;
        List<ItemStack> remaining = Arrays.stream(inventory.getStorageContents())
                .filter(Objects::nonNull).filter(i -> !i.getType().isAir()).map(ItemStack::clone).toList();
        try {
            if (remaining.isEmpty() && getConfig().getBoolean("settings.remove-when-empty", true)) {
                removeGrave(id, false, List.of());
                if (notify && inventory.getViewers().isEmpty()) {
                    Player owner = Bukkit.getPlayer(meta.ownerUuid());
                    if (owner != null)
                        send(owner, "messages.grave-empty-removed", Map.of("owner", meta.ownerName()));
                }
            } else {
                updateItems(id, serializeItems(remaining));
            }
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "No se pudo guardar la bolsa " + id, ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        UUID id = trackedGraveId(event.getBlock());
        if (id == null)
            return;
        event.setCancelled(true);
        event.setDropItems(false);

        GraveMeta meta = graves.get(id);
        if (meta == null)
            return;
        if (isBeingViewed(id)) {
            send(event.getPlayer(), "messages.grave-in-use-break", Map.of("owner", meta.ownerName()));
            verifyOpenGraveNextTick(id);
            return;
        }
        if (!canAccessGrave(event.getPlayer(), meta)) {
            send(event.getPlayer(), meta.ownerProtected() ? "messages.grave-private" : "messages.not-owner",
                    Map.of("owner", meta.ownerName()));
            return;
        }
        breakGrave(event.getPlayer(), id);
    }

    private void breakGrave(Player breaker, UUID id) {
        GraveMeta meta = graves.get(id);
        if (meta == null)
            return;
        // Regla anti-duplicación: una bolsa abierta nunca entra en la ruta de
        // borrado/drop.
        if (isBeingViewed(id)) {
            if (breaker != null)
                send(breaker, "messages.grave-in-use-break", Map.of("owner", meta.ownerName()));
            verifyOpenGraveNextTick(id);
            return;
        }
        try {
            List<ItemStack> items = getConfig().getBoolean("settings.break-drops-items", true) ? loadItems(id)
                    : List.of();
            removeGrave(id, true, items);
            if (breaker != null)
                send(breaker, "messages.grave-broken", Map.of("owner", meta.ownerName()));
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "No se pudo romper correctamente la bolsa " + id, ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        breakGravesFromExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        breakGravesFromExplosion(event.blockList());
    }

    private void breakGravesFromExplosion(List<Block> affectedBlocks) {
        if (!getConfig().getBoolean("settings.explosions-break-graves", true)) {
            // Modo antiguo opcional: la explosión no destruye bolsas.
            affectedBlocks.removeIf(block -> trackedGraveId(block) != null);
            return;
        }

        Set<UUID> graveIds = new LinkedHashSet<>();
        affectedBlocks.removeIf(block -> {
            UUID id = trackedGraveId(block);
            if (id == null)
                return false;
            if (isBeingViewed(id)) {
                verifyOpenGraveNextTick(id);
                return true;
            }
            GraveMeta meta = graves.get(id);
            if (meta != null && meta.ownerProtected()
                    && getConfig().getBoolean("utilities.private-graves.protect-from-explosions", true)) {
                return true;
            }
            graveIds.add(id);
            // Se quita de la lista vanilla: MDVGraves hace una única eliminación
            // transaccional
            // y suelta exactamente el inventario persistido, no una cabeza adicional.
            return true;
        });
        for (UUID id : graveIds)
            breakGrave(null, id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        // La inmunidad a líquidos es una regla de las bolsas, también cerradas y
        // con configuraciones antiguas. Cancelar antes evita el drop de la cabeza.
        protectTrackedGraveBlock(event.getToBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        // Verter un cubo reemplaza directamente el destino: no siempre provoca
        // BlockFromToEvent. getBlock() es el destino real, no el bloque clicado.
        protectTrackedGraveBlock(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidDispense(BlockDispenseEvent event) {
        String item = event.getItem().getType().name();
        if (!Set.of("WATER_BUCKET", "LAVA_BUCKET", "POWDER_SNOW_BUCKET", "COD_BUCKET",
                "SALMON_BUCKET", "PUFFERFISH_BUCKET", "TROPICAL_FISH_BUCKET", "AXOLOTL_BUCKET",
                "TADPOLE_BUCKET").contains(item))
            return;
        if (event.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional directional)
            protectTrackedGraveBlock(event.getBlock().getRelative(directional.getFacing()), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnvironmentalDestroy(com.destroystokyo.paper.event.block.BlockDestroyEvent event) {
        UUID id = trackedGraveId(event.getBlock());
        if (id == null)
            return;
        // Paper cubre aquí destrucciones indirectas por física, pistones, etc.
        // BlockPhysicsEvent puede emitirse solo para el bloque raíz y omitir la
        // cabeza vecina. No se borra la bolsa ni se carga/dropea su inventario.
        event.setCancelled(true);
        event.setWillDrop(false);
        event.setExpToDrop(0);
        if (isBeingViewed(id))
            verifyOpenGraveNextTick(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        protectTrackedGraveBlock(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        protectTrackedGraveBlock(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        UUID openId = event.getBlocks().stream().map(this::trackedGraveId)
                .filter(Objects::nonNull).filter(this::isBeingViewed).findFirst().orElse(null);
        boolean containsGrave = event.getBlocks().stream().anyMatch(block -> trackedGraveId(block) != null);
        if (openId != null || (getConfig().getBoolean("settings.protect-from-pistons", true) && containsGrave)) {
            event.setCancelled(true);
            if (openId != null)
                verifyOpenGraveNextTick(openId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        UUID openId = event.getBlocks().stream().map(this::trackedGraveId)
                .filter(Objects::nonNull).filter(this::isBeingViewed).findFirst().orElse(null);
        boolean containsGrave = event.getBlocks().stream().anyMatch(block -> trackedGraveId(block) != null);
        if (openId != null || (getConfig().getBoolean("settings.protect-from-pistons", true) && containsGrave)) {
            event.setCancelled(true);
            if (openId != null)
                verifyOpenGraveNextTick(openId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        protectTrackedGraveBlock(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        protectTrackedGraveBlock(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        UUID id = trackedGraveId(event.getBlock());
        if (id == null)
            return;
        event.setCancelled(true);
        if (isBeingViewed(id))
            verifyOpenGraveNextTick(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        UUID id = trackedGraveId(event.getBlock());
        if (id == null)
            return;
        event.setCancelled(true);
        if (isBeingViewed(id))
            verifyOpenGraveNextTick(id);
    }

    private void protectTrackedGraveBlock(Block block, Cancellable event) {
        UUID id = trackedGraveId(block);
        if (id == null)
            return;
        event.setCancelled(true);
        if (isBeingViewed(id))
            verifyOpenGraveNextTick(id);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Limpia cabezas huérfanas sin forzar la carga de chunks durante cleanup.
        for (BlockState state : event.getChunk().getTileEntities()) {
            if (!(state instanceof Skull skull))
                continue;
            String raw = skull.getPersistentDataContainer().get(graveKey, PersistentDataType.STRING);
            if (raw == null)
                continue;
            try {
                UUID id = UUID.fromString(raw);
                if (!graves.containsKey(id))
                    skull.getBlock().setType(Material.AIR, false);
            } catch (IllegalArgumentException ex) {
                skull.getBlock().setType(Material.AIR, false);
            }
        }
    }

    private void scheduleOpenGraveIntegrityGuard() {
        if (integrityTask != null)
            integrityTask.cancel();
        long ticks = Math.max(1L, getConfig().getLong("settings.open-grave-integrity-check-ticks", 2L));
        integrityTask = Bukkit.getScheduler().runTaskTimer(this, this::verifyAllOpenGraves, ticks, ticks);
    }

    private void verifyAllOpenGraves() {
        for (UUID id : new ArrayList<>(activeViewers.keySet())) {
            pruneInactiveViewers(id);
            if (!isBeingViewed(id))
                continue;
            verifyOpenGraveIntegrity(id);
        }
    }

    private void verifyOpenGraveNextTick(UUID id) {
        Bukkit.getScheduler().runTask(this, () -> verifyOpenGraveIntegrity(id));
    }

    private void verifyOpenGraveIntegrity(UUID id) {
        if (!isBeingViewed(id))
            return;
        GraveMeta meta = graves.get(id);
        if (meta == null) {
            closeAllViewersAndPersist(id, false);
            return;
        }
        World world = Bukkit.getWorld(meta.world());
        if (world == null || !world.isChunkLoaded(meta.x() >> 4, meta.z() >> 4))
            return;
        Block block = world.getBlockAt(meta.x(), meta.y(), meta.z());
        if (isOurHead(block, id))
            return;

        getLogger().warning("La bolsa " + id + " fue alterada mientras estaba abierta. "
                + "Se cerrarán sus visores y se restaurará el bloque sin generar drops.");
        closeAllViewersAndPersist(id, true);
        if (!graves.containsKey(id))
            return; // Quedó vacía y fue retirada normalmente.
        restoreGraveHead(block, meta);
    }

    private Block loadedGraveBlock(GraveMeta meta) {
        if (meta == null)
            return null;
        World world = Bukkit.getWorld(meta.world());
        if (world == null || !world.isChunkLoaded(meta.x() >> 4, meta.z() >> 4))
            return null;
        return world.getBlockAt(meta.x(), meta.y(), meta.z());
    }

    private void restoreGraveHead(Block block, GraveMeta meta) {
        block.setType(Material.PLAYER_HEAD, false);
        Skull skull = (Skull) block.getState();
        skull.getPersistentDataContainer().set(graveKey, PersistentDataType.STRING, meta.id().toString());
        Player owner = Bukkit.getPlayer(meta.ownerUuid());
        if (owner != null) {
            applyTexture(skull, owner);
        } else {
            PlayerProfile profile = Bukkit.createPlayerProfile(meta.ownerUuid(), meta.ownerName());
            skull.setOwnerProfile(profile);
        }
        skull.update(true, false);
    }

    private void closeAllViewersAndPersist(UUID id, boolean notify) {
        Inventory inventory = openGraveInventories.get(id);
        Set<UUID> viewers = new LinkedHashSet<>(activeViewers.getOrDefault(id, Set.of()));
        forcedClosingGraves.add(id);
        try {
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null)
                    continue;
                if (notify)
                    send(viewer, "messages.grave-forced-closed", Map.of());
                if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof GraveHolder holder
                        && holder.graveId().equals(id))
                    viewer.closeInventory();
            }
            activeViewers.remove(id);
            openGraveInventories.remove(id);
            if (inventory != null)
                saveInventoryAndMaybeRemove(id, inventory, false);
        } finally {
            forcedClosingGraves.remove(id);
        }
    }

    private boolean isBeingViewed(UUID id) {
        pruneInactiveViewers(id);
        Set<UUID> viewers = activeViewers.get(id);
        return viewers != null && !viewers.isEmpty();
    }

    private void pruneInactiveViewers(UUID id) {
        Set<UUID> viewers = activeViewers.get(id);
        if (viewers == null)
            return;
        viewers.removeIf(viewerId -> {
            Player player = Bukkit.getPlayer(viewerId);
            if (player == null)
                return true;
            Inventory top = player.getOpenInventory().getTopInventory();
            return !(top.getHolder() instanceof GraveHolder holder) || !holder.graveId().equals(id);
        });
        if (viewers.isEmpty())
            activeViewers.remove(id);
    }

    private void scheduleCleanup() {
        if (cleanupTask != null)
            cleanupTask.cancel();
        long seconds = Math.max(60L, getConfig().getLong("settings.cleanup-interval-seconds", 300L));
        cleanupTask = Bukkit.getScheduler().runTaskTimer(this, () -> cleanupExpired(false), seconds * 20L,
                seconds * 20L);
    }

    public int cleanupExpired(boolean manual) {
        long now = Instant.now().getEpochSecond();
        List<UUID> expired = graves.values().stream().filter(g -> g.expiresAt() <= now).map(GraveMeta::id).toList();
        int removed = 0;
        for (UUID id : expired) {
            if (isBeingViewed(id))
                continue;
            GraveMeta meta = graves.get(id);
            if (meta == null)
                continue;
            try {
                removeGrave(id, false, List.of());
                Player owner = Bukkit.getPlayer(meta.ownerUuid());
                if (owner != null)
                    send(owner, "messages.grave-expired-owner", Map.of("owner", meta.ownerName()));
                removed++;
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "No se pudo limpiar la bolsa " + id, ex);
            }
        }
        return removed;
    }

    private void removeGrave(UUID id, boolean dropItems, List<ItemStack> items) throws SQLException {
        GraveMeta meta = graves.get(id);
        if (meta == null)
            return;
        // DB primero: si falla, no dropea ni borra bloque, evitando duplicación.
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM graves WHERE grave_id=?")) {
            ps.setString(1, id.toString());
            if (ps.executeUpdate() == 0)
                return;
        }
        graves.remove(id);
        gravesByBlock.remove(meta.blockKey());
        activeViewers.remove(id);
        openGraveInventories.remove(id);
        forcedClosingGraves.remove(id);

        World world = Bukkit.getWorld(meta.world());
        if (world != null && world.isChunkLoaded(meta.x() >> 4, meta.z() >> 4)) {
            Block block = world.getBlockAt(meta.x(), meta.y(), meta.z());
            if (isOurHead(block, id))
                block.setType(Material.AIR, false);
            if (dropItems) {
                Location dropAt = new Location(world, meta.x() + 0.5, meta.y() + 0.3, meta.z() + 0.5);
                for (ItemStack item : items)
                    world.dropItemNaturally(dropAt, item.clone());
            }
        }
    }

    private void markFirstOpened(GraveMeta meta) throws SQLException {
        if (meta.firstOpenedAt() != null)
            return;
        long opened = Instant.now().getEpochSecond();
        long expires = opened + Math.max(1L, getConfig().getLong("settings.opened-expire-minutes", 20L)) * 60L;
        try (PreparedStatement ps = connection
                .prepareStatement("UPDATE graves SET first_opened_at=?, expires_at=? WHERE grave_id=?")) {
            ps.setLong(1, opened);
            ps.setLong(2, expires);
            ps.setString(3, meta.id().toString());
            ps.executeUpdate();
        }
        GraveMeta updated = meta.withOpened(opened, expires);
        graves.put(meta.id(), updated);
    }

    private void insertGrave(GraveMeta meta, byte[] items) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                        INSERT INTO graves(grave_id,owner_uuid,owner_name,world,x,y,z,created_at,first_opened_at,expires_at,items,owner_protected)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                        """)) {
            ps.setString(1, meta.id().toString());
            ps.setString(2, meta.ownerUuid().toString());
            ps.setString(3, meta.ownerName());
            ps.setString(4, meta.world());
            ps.setInt(5, meta.x());
            ps.setInt(6, meta.y());
            ps.setInt(7, meta.z());
            ps.setLong(8, meta.createdAt());
            ps.setNull(9, Types.BIGINT);
            ps.setLong(10, meta.expiresAt());
            ps.setBytes(11, items);
            ps.setInt(12, meta.ownerProtected() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void updateItems(UUID id, byte[] items) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE graves SET items=? WHERE grave_id=?")) {
            ps.setBytes(1, items);
            ps.setString(2, id.toString());
            ps.executeUpdate();
        }
    }

    private List<ItemStack> loadItems(UUID id) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT items FROM graves WHERE grave_id=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return List.of();
                return deserializeItems(rs.getBytes(1));
            }
        }
    }

    private byte[] serializeItems(List<ItemStack> items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
                BukkitObjectOutputStream out = new BukkitObjectOutputStream(gzip)) {
            out.writeInt(items.size());
            for (ItemStack item : items)
                out.writeObject(item);
        }
        return bytes.toByteArray();
    }

    private List<ItemStack> deserializeItems(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0)
            return List.of();
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(
                new GZIPInputStream(new ByteArrayInputStream(data)))) {
            int size = in.readInt();
            List<ItemStack> items = new ArrayList<>(size);
            for (int i = 0; i < size; i++)
                items.add((ItemStack) in.readObject());
            return items;
        }
    }

    /**
     * Crea una bolsa a partir de un snapshot de un jugador que ya está offline.
     * Se usa exclusivamente por logout-body. La DB de la bolsa sigue siendo la
     * autoridad canónica y la entidad visual nunca entrega items.
     */
    public boolean createOfflineGrave(UUID id, UUID ownerUuid, String ownerName, Location deathLocation,
            List<ItemStack> items, boolean ownerProtected, String capturedTexture) {
        if (id == null || ownerUuid == null || deathLocation == null || deathLocation.getWorld() == null
                || items == null || items.isEmpty())
            return false;
        if (!enabledWorld(deathLocation.getWorld().getName()))
            return false;

        Block target = findPlacementBlock(deathLocation);
        if (target == null) {
            getLogger().warning("No se encontró espacio seguro para la bolsa offline de " + ownerName + ".");
            return false;
        }

        long created = Instant.now().getEpochSecond();
        long expires = created + Math.max(1L, getConfig().getLong("settings.unopened-expire-hours", 168L)) * 3600L;
        GraveMeta meta = new GraveMeta(id, ownerUuid, ownerName, target.getWorld().getName(),
                target.getX(), target.getY(), target.getZ(), created, null, expires, ownerProtected);

        SupportPatch supportPatch = stabilizePlacementSupport(target);
        try {
            byte[] blob = serializeItems(items);
            placeOfflineGraveHead(target, id, ownerUuid, ownerName, capturedTexture);
            insertGrave(meta, blob);
            graves.put(id, meta);
            gravesByBlock.put(meta.blockKey(), id);
            return true;
        } catch (Exception ex) {
            if (isOurHead(target, id))
                target.setType(Material.AIR, false);
            rollbackSupportPatch(supportPatch);
            getLogger().log(Level.SEVERE,
                    "No se pudo crear la bolsa offline de " + ownerName + ".", ex);
            return false;
        }
    }

    private void placeOfflineGraveHead(Block block, UUID id, UUID ownerUuid, String ownerName,
            String capturedTexture) {
        block.setType(Material.PLAYER_HEAD, false);
        Skull skull = (Skull) block.getState();
        skull.getPersistentDataContainer().set(graveKey, PersistentDataType.STRING, id.toString());

        String texture = capturedTexture == null ? "" : capturedTexture.trim();
        if (!texture.isBlank()) {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "MDVGrave");
            String skinUrl = extractTextureUrl(texture);
            if (skinUrl != null) {
                try {
                    PlayerTextures textures = profile.getTextures();
                    textures.setSkin(URI.create(skinUrl).toURL());
                    profile.setTextures(textures);
                    skull.setOwnerProfile(profile);
                } catch (Exception ex) {
                    getLogger().log(Level.WARNING,
                            "No se pudo aplicar la textura capturada a una bolsa offline.", ex);
                    skull.setOwningPlayer(Bukkit.getOfflinePlayer(ownerUuid));
                }
            } else {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(ownerUuid));
            }
        } else {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(ownerUuid));
        }
        skull.update(true, false);
    }

    private void placeGraveHead(Block block, UUID id, Player owner) {
        block.setType(Material.PLAYER_HEAD, false);
        Skull skull = (Skull) block.getState();
        skull.getPersistentDataContainer().set(graveKey, PersistentDataType.STRING, id.toString());
        applyTexture(skull, owner);
        skull.update(true, false);
    }

    private void applyTexture(Skull skull, Player owner) {
        String texture = selectTexture(owner);
        PlayerProfile profile;
        if (texture != null && !texture.isBlank()) {
            profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "MDVGrave");
            String skinUrl = extractTextureUrl(texture.trim());
            if (skinUrl != null) {
                try {
                    PlayerTextures textures = profile.getTextures();
                    textures.setSkin(URI.create(skinUrl).toURL());
                    profile.setTextures(textures);
                } catch (Exception ex) {
                    getLogger().log(Level.WARNING, "Textura Base64 inválida; se usará la cabeza del jugador.", ex);
                    profile = owner.getPlayerProfile().clone();
                }
            } else {
                getLogger().warning(
                        "No se encontró una URL textures.minecraft.net en la textura Base64; se usará la cabeza del jugador.");
                profile = owner.getPlayerProfile().clone();
            }
        } else {
            profile = owner.getPlayerProfile().clone();
        }
        // API Bukkit compatible con Paper/Purpur 1.21.6; evita mezclar los dos
        // PlayerProfile de Paper.
        skull.setOwnerProfile(profile);
    }

    private String extractTextureUrl(String base64Texture) {
        try {
            String json = new String(Base64.getDecoder().decode(base64Texture), StandardCharsets.UTF_8);
            int key = json.indexOf("\"url\"");
            if (key < 0)
                return null;
            int colon = json.indexOf(':', key);
            int firstQuote = json.indexOf('\"', colon + 1);
            int secondQuote = json.indexOf('\"', firstQuote + 1);
            if (colon < 0 || firstQuote < 0 || secondQuote < 0)
                return null;
            return json.substring(firstQuote + 1, secondQuote).replace("\\/", "/");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String selectTexture(Player player) {
        ConfigurationSection ranks = getConfig().getConfigurationSection("textures.ranks");
        if (ranks != null) {
            for (String key : ranks.getKeys(false)) {
                String permission = ranks.getString(key + ".permission", "");
                String texture = ranks.getString(key + ".texture", "");
                if (!permission.isBlank() && player.hasPermission(permission) && !texture.isBlank())
                    return texture;
            }
        }
        return getConfig().getString("textures.default", "");
    }

    int clampWithWorld(World world, int height) {
        return Math.clamp(height, world.getMinHeight() + 1, world.getMaxHeight() - 1);
    }

    private Block findPlacementBlock(Location death) {
        int radius = Math.max(0, getConfig().getInt("settings.placement-search-radius", 2));
        World world = death.getWorld();
        int baseX = death.getBlockX();
        int baseZ = death.getBlockZ();

        // Round up only for fractional feet heights (paths/slabs). Never begin
        // two blocks above the feet: that can place a grave on a low cave roof.
        int deathHeight = (int) Math.ceil(death.getY());
        int startY = clampWithWorld(world, deathHeight);

        // Primero revisa exactamente la columna donde murió el jugador. Si no existe
        // una superficie utilizable, amplía la búsqueda por anillos cercanos.
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r)
                        continue;
                    Block target = findFirstSurfaceBelow(world, baseX + dx, startY, baseZ + dz);
                    if (target != null)
                        return target;
                }
            }
        }
        return null;
    }

    /**
     * Busca hacia abajo desde la altura de muerte y devuelve el bloque reemplazable
     * inmediatamente superior al primer bloque sólido encontrado. Esto evita bolsas
     * flotando cuando el jugador muere en caída, vuelo o sobre un precipicio.
     */
    private Block findFirstSurfaceBelow(World world, int x, int startY, int z) {
        int minY = world.getMinHeight();
        int realStartY = clampWithWorld(world, startY);

        for (int y = realStartY; y > minY; y--) {
            Block support = world.getBlockAt(x, y - 1, z);
            if (canReplace(support))
                continue;

            Block target = world.getBlockAt(x, y, z);
            return canReplace(target) ? target : null;
        }
        return null;
    }

    /**
     * Algunos bloques de suelo parcial (por ejemplo DIRT_PATH y FARMLAND) no son un
     * soporte estable para una PLAYER_HEAD de suelo. La corrección solo se evalúa
     * cuando realmente va a crearse una tumba, por lo que no añade tareas
     * periódicas.
     *
     * Los reemplazos son configurables. Si la creación de la tumba falla, el bloque
     * original se restaura para no modificar el mapa por un error de persistencia.
     */
    private SupportPatch stabilizePlacementSupport(Block target) {
        if (!getConfig().getBoolean("settings.placement-support-fixes.enabled", true))
            return null;
        Block support = target.getRelative(BlockFace.DOWN);
        ConfigurationSection replacements = getConfig()
                .getConfigurationSection("settings.placement-support-fixes.replacements");
        if (replacements == null)
            return null;

        String configured = replacements.getString(support.getType().name());
        if (configured == null || configured.isBlank())
            return null;

        Material replacement = Material.matchMaterial(configured.trim());
        if (replacement == null || replacement.isAir() || !replacement.isBlock()) {
            getLogger().warning("Reemplazo inválido para soporte de tumba " + support.getType()
                    + ": " + configured);
            return null;
        }

        BlockData original = support.getBlockData().clone();
        support.setType(replacement, false);
        return new SupportPatch(support, original);
    }

    private void rollbackSupportPatch(SupportPatch patch) {
        if (patch == null)
            return;
        try {
            patch.block().setBlockData(patch.original(), false);
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "No se pudo restaurar el bloque de soporte tras fallar una tumba.", ex);
        }
    }

    private boolean canReplace(Block block) {
        Material type = block.getType();
        if (type.isAir() || type == Material.WATER || type == Material.LAVA
                || type == Material.FIRE || type == Material.SOUL_FIRE)
            return true;
        if (!getConfig().getBoolean("settings.replace-thin-blocks", true))
            return false;
        return isThinReplaceable(type);
    }

    /**
     * Bloques de superficie, vegetación y cultivos que la bolsa puede sustituir sin
     * generar drops. Se usan nombres para mantener compatibilidad con materiales
     * vegetales añadidos en revisiones 1.21.x sin depender de Tags experimentales.
     */
    private boolean isThinReplaceable(Material type) {
        String name = type.name();

        String[] thinReplaceableSuffixes = {
                "_CARPET",
                "_SAPLING",
                "_TULIP",
                "_FLOWER",
                "_MUSHROOM",
                "_FUNGUS",
                "_ROOTS",
                "_VINES",
                "_VINES_PLANT",
        };

        for (String suffix : thinReplaceableSuffixes) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return THIN_REPLACEABLE_MATERIALS.contains(name);
    }

    /**
     * Devuelve el ID registrado para una ubicación, aunque otro plugin haya
     * cambiado el bloque.
     */
    private UUID trackedGraveId(Block block) {
        BlockKey key = new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        UUID cached = gravesByBlock.get(key);
        return cached != null ? cached : physicalGraveId(block);
    }

    /**
     * Lee exclusivamente la cabeza física y su PDC; no confía en el caché de
     * ubicación.
     */
    private UUID physicalGraveId(Block block) {
        if (!(block.getState() instanceof Skull skull))
            return null;
        String raw = skull.getPersistentDataContainer().get(graveKey, PersistentDataType.STRING);
        if (raw == null)
            return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isOurHead(Block block, UUID id) {
        return id.equals(physicalGraveId(block));
    }

    private boolean canAccessGrave(Player player, GraveMeta meta) {
        if (meta.ownerUuid().equals(player.getUniqueId()) || player.hasPermission("mdvgraves.admin"))
            return true;
        if (getConfig().getBoolean("settings.only-owner-can-open", false))
            return false;
        if (!getConfig().getBoolean("utilities.private-graves.enabled", true))
            return true;

        Player owner = Bukkit.getPlayer(meta.ownerUuid());
        boolean currentlyProtected = owner != null && owner.hasPermission("mdvgraves.private");
        return !meta.ownerProtected() && !currentlyProtected;
    }

    private boolean enabledWorld(String world) {
        return getConfig().getStringList("settings.enabled-worlds").stream()
                .anyMatch(name -> name.equalsIgnoreCase(world));
    }

    private int inventorySize(int itemCount) {
        int min = Math.max(9, Math.min(54, getConfig().getInt("inventory.minimum-size", 27)));
        min = ((min + 8) / 9) * 9;
        int needed = Math.max(min, ((Math.max(1, itemCount) + 8) / 9) * 9);
        return Math.min(54, needed);
    }

    private GraveMeta readMeta(ResultSet rs) throws SQLException {
        long openedRaw = rs.getLong("first_opened_at");
        Long opened = rs.wasNull() ? null : openedRaw;
        return new GraveMeta(UUID.fromString(rs.getString("grave_id")), UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("owner_name"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getLong("created_at"), opened, rs.getLong("expires_at"), rs.getInt("owner_protected") != 0);
    }

    public void send(CommandSender sender, String path, Map<String, String> replacements) {
        String raw = getConfig().getString(path, "");
        if (raw == null || raw.isBlank())
            return;
        String prefix = getConfig().getString("messages.prefix", "");
        String result = prefix + raw;
        for (Map.Entry<String, String> entry : replacements.entrySet())
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        sender.sendMessage(color(result));
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public boolean executeGraveBack(Player player) {
        queueGraveBack(player, false, false, false, true, null, ignored -> {});
        return true;
    }

    /**
     * Devuelve si la petición fue aceptada. El resultado final es asíncrono;
     * los consumidores que lo necesiten deben usar la variante con callback.
     */
    public boolean executeGraveBack(Player player, boolean bypassEnabled, boolean bypassPermission,
            boolean bypassCooldown, boolean sendMessages) {
        return executeGraveBack(player, bypassEnabled, bypassPermission, bypassCooldown, sendMessages, ignored -> {});
    }

    public boolean executeGraveBack(Player player, boolean bypassEnabled, boolean bypassPermission,
            boolean bypassCooldown, boolean sendMessages, Consumer<Boolean> completion) {
        return queueGraveBack(player, bypassEnabled, bypassPermission, bypassCooldown, sendMessages, null,
                result -> completion.accept(result == GraveBackResult.SUCCESS));
    }

    private boolean queueGraveBack(Player player, boolean bypassEnabled, boolean bypassPermission,
            boolean bypassCooldown, boolean sendMessages, PendingDeathFruitUse fruitUse,
            Consumer<GraveBackResult> completion) {
        long now = System.currentTimeMillis();
        long availableAt = graveBackTransportLocks.getOrDefault(player.getUniqueId(), 0L);
        if (availableAt > now) {
            if (sendMessages)
                send(player, "messages.back-cooldown",
                        Map.of("seconds", Long.toString(Math.max(1L, (availableAt - now + 999L) / 1000L))));
            completion.accept(GraveBackResult.COOLDOWN);
            return false;
        }
        GraveBackRequest request = new GraveBackRequest(player, bypassEnabled, bypassPermission,
                bypassCooldown, sendMessages, fruitUse, completion);
        if (activeGraveBacks.putIfAbsent(player.getUniqueId(), request) != null) {
            completion.accept(GraveBackResult.TELEPORT_FAILED);
            return false;
        }
        // También protege acciones antiguas de MMOItems que llaman /graveback
        // desde consola. El bypass de gameplay no permite ráfagas de teleports.
        graveBackTransportLocks.put(player.getUniqueId(), now + 750L);

        // MMOItems ejecuta el comando dentro de su interacción. Esperar al siguiente
        // tick permite terminar su consumo y el procesamiento de ese paquete.
        Bukkit.getScheduler().runTask(this, () -> beginGraveBack(request));
        request.timeoutTask = Bukkit.getScheduler().runTaskLater(this,
                () -> finishGraveBack(request, GraveBackResult.TELEPORT_FAILED), 600L);
        return true;
    }

    private boolean isCurrentGraveBack(GraveBackRequest request) {
        Player player = request.player;
        return activeGraveBacks.get(player.getUniqueId()) == request
                && player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private boolean isPendingRespawn(Player player) {
        return player.isDead() || (logoutBodyManager != null
                && logoutBodyManager.isPendingRespawn(player.getUniqueId()));
    }

    private void beginGraveBack(GraveBackRequest request) {
        if (!isCurrentGraveBack(request)) {
            finishGraveBack(request, GraveBackResult.TELEPORT_FAILED);
            return;
        }
        Player player = request.player;
        // La reserva evita un viaje gratis si se mueve la fruta mientras se cargan
        // chunks. Se devuelve una sola unidad si cualquier fase falla.
        if (request.fruitUse != null) {
            request.fruitEvaluated = true;
            request.fruitCharged = wasFruitAlreadyConsumed(player, request.fruitUse)
                    || consumeDeathFruit(player, request.fruitUse);
            if (!request.fruitCharged) {
                finishGraveBack(request, GraveBackResult.TELEPORT_FAILED);
                return;
            }
        }
        if (isPendingRespawn(player)) {
            finishGraveBack(request, GraveBackResult.TELEPORT_FAILED);
            return;
        }
        if (!request.bypassEnabled && !getConfig().getBoolean("utilities.back-grave.enabled", true)) {
            finishGraveBack(request, GraveBackResult.DISABLED);
            return;
        }
        if (!request.bypassPermission && !player.hasPermission("mdvgraves.back")) {
            finishGraveBack(request, GraveBackResult.NO_PERMISSION);
            return;
        }
        request.cooldownSeconds = Math.max(0L, getConfig().getLong("utilities.back-grave.cooldown-seconds", 30L));
        if (!request.bypassCooldown && request.cooldownSeconds > 0L
                && !player.hasPermission("mdvgraves.back.cooldown.bypass")) {
            long now = System.currentTimeMillis();
            long availableAt = graveBackCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (availableAt > now) {
                if (request.sendMessages)
                    send(player, "messages.back-cooldown",
                            Map.of("seconds", Long.toString(Math.max(1L, (availableAt - now + 999L) / 1000L))));
                finishGraveBack(request, GraveBackResult.COOLDOWN);
                return;
            }
        }

        request.grave = latestGrave(player.getUniqueId());
        if (request.grave == null) {
            finishGraveBack(request, GraveBackResult.NO_GRAVES);
            return;
        }
        request.world = Bukkit.getWorld(request.grave.world());
        if (request.world == null) {
            finishGraveBack(request, GraveBackResult.WORLD_UNAVAILABLE);
            return;
        }
        // Un radio accidentalmente enorme no debe generar miles de chunks.
        request.radius = Math.max(0, Math.min(32, getConfig().getInt("utilities.back-grave.safe-search-radius", 3)));
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (int x = ((request.grave.x() - request.radius) >> 4) - 1;
                x <= ((request.grave.x() + request.radius) >> 4) + 1; x++) {
            for (int z = ((request.grave.z() - request.radius) >> 4) - 1;
                    z <= ((request.grave.z() + request.radius) >> 4) + 1; z++) {
                CompletableFuture<Void> held = new CompletableFuture<>();
                loads.add(held);
                request.world.getChunkAtAsync(x, z, false).whenComplete((chunk, error) -> {
                    runGraveBackContinuation(request, () -> {
                        if (error != null) {
                            held.completeExceptionally(error);
                            return;
                        }
                        if (chunk != null && isCurrentGraveBack(request)
                                && request.world.isChunkLoaded(chunk.getX(), chunk.getZ())) {
                            int users = graveBackChunkTickets.getOrDefault(chunk, 0);
                            if (users == 0)
                                chunk.addPluginChunkTicket(this);
                            graveBackChunkTickets.put(chunk, users + 1);
                            request.chunks.add(chunk);
                        }
                        held.complete(null);
                    });
                });
            }
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                runGraveBackContinuation(request, () -> {
                    if (error != null)
                        finishGraveBack(request, GraveBackResult.TELEPORT_FAILED);
                    else
                        teleportToGrave(request);
                }));
    }

    private void runGraveBackContinuation(GraveBackRequest request, Runnable continuation) {
        if (!isEnabled() || activeGraveBacks.get(request.player.getUniqueId()) != request)
            return;
        if (Bukkit.isPrimaryThread())
            continuation.run();
        else
            Bukkit.getScheduler().runTask(this, () -> {
                if (activeGraveBacks.get(request.player.getUniqueId()) == request)
                    continuation.run();
            });
    }

    private void teleportToGrave(GraveBackRequest request) {
        if (!isCurrentGraveBack(request) || isPendingRespawn(request.player)) {
            finishGraveBack(request, GraveBackResult.TELEPORT_FAILED);
            return;
        }
        if (!graves.containsKey(request.grave.id())) {
            finishGraveBack(request, GraveBackResult.NO_GRAVES);
            return;
        }
        Location destination = findSafeTeleportLocation(request.grave, request.world, request.radius,
                request.player.getLocation());
        if (destination == null) {
            finishGraveBack(request, GraveBackResult.NO_SAFE_LOCATION);
            return;
        }
        // El destino y sus vecinos ya se cargaron asíncronamente y tienen tickets.
        // Se liquida en este mismo tick: un timeout/quit/disable nunca puede devolver
        // la fruta mientras queda un teleport futuro aún pendiente de ejecutarse.
        try {
            boolean success = request.player.teleport(destination, PlayerTeleportEvent.TeleportCause.COMMAND);
            Location actual = request.player.getLocation();
            boolean arrived = success && isCurrentGraveBack(request) && !isPendingRespawn(request.player)
                    && actual.getWorld() == destination.getWorld() && actual.distanceSquared(destination) <= 4.0;
            finishGraveBack(request, arrived ? GraveBackResult.SUCCESS : GraveBackResult.TELEPORT_FAILED);
        } catch (RuntimeException ex) {
            getLogger().log(Level.WARNING, "No se pudo completar graveback para " + request.player.getName(), ex);
            finishGraveBack(request, GraveBackResult.TELEPORT_FAILED);
        }
    }

    private void finishGraveBack(GraveBackRequest request, GraveBackResult result) {
        Player player = request.player;
        if (!activeGraveBacks.remove(player.getUniqueId(), request))
            return; // Timeout, quit y callback del teleport solo pueden liquidar una vez.
        if (request.timeoutTask != null)
            request.timeoutTask.cancel();
        graveBackTransportLocks.put(player.getUniqueId(), System.currentTimeMillis() + 750L);
        for (Chunk chunk : request.chunks) {
            int users = graveBackChunkTickets.getOrDefault(chunk, 0);
            if (users <= 1) {
                graveBackChunkTickets.remove(chunk);
                chunk.removePluginChunkTicket(this);
            } else {
                graveBackChunkTickets.put(chunk, users - 1);
            }
        }
        if (request.fruitUse != null) {
            // También cubre una salida antes de que empiece la tarea del siguiente tick.
            if (!request.fruitEvaluated)
                request.fruitCharged = wasFruitAlreadyConsumed(player, request.fruitUse);
            if (result != GraveBackResult.SUCCESS && request.fruitCharged) {
                if (request.refundDeathEvent == null) {
                    refundDeathFruit(player, request.fruitUse.snapshot());
                } else {
                    ItemStack refund = request.fruitUse.snapshot().clone();
                    refund.setAmount(1);
                    if (logoutBodyManager == null
                            || !logoutBodyManager.keepRefundedMmoItemOnDeath(request.refundDeathEvent, refund))
                        request.refundDeathEvent.getDrops().add(refund);
                }
            }
            long lockMs = Math.max(750L, getConfig().getLong("utilities.death-fruit.use-lock-ms", 750L));
            deathFruitUseLocks.put(player.getUniqueId(), System.currentTimeMillis() + lockMs);
        }
        if (result == GraveBackResult.SUCCESS && !request.bypassCooldown && request.cooldownSeconds > 0L
                && !player.hasPermission("mdvgraves.back.cooldown.bypass")) {
            graveBackCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + request.cooldownSeconds * 1000L);
        }
        if (request.sendMessages && player.isOnline()) {
            switch (result) {
                case SUCCESS -> {
                    playConfiguredSound(player, "utilities.back-grave.sound", "entity.enderman.teleport");
                    send(player, "messages.back-teleported", graveLocationPlaceholders(request.grave));
                }
                case DISABLED -> send(player, "messages.back-disabled", Map.of());
                case NO_PERMISSION -> send(player, "messages.no-permission", Map.of());
                case NO_GRAVES -> send(player, "messages.back-no-graves", Map.of());
                case WORLD_UNAVAILABLE -> send(player, "messages.back-world-unavailable",
                        Map.of("world", request.grave.world()));
                case NO_SAFE_LOCATION -> send(player, "messages.back-no-safe-location",
                        graveLocationPlaceholders(request.grave));
                case TELEPORT_FAILED -> send(player, "messages.back-teleport-failed", Map.of());
                case COOLDOWN -> {} // El tiempo restante se envía al validar.
            }
        }
        request.completion.accept(result);
    }

    private GraveMeta latestGrave(UUID owner) {
        return graves.values().stream()
                .filter(meta -> meta.ownerUuid().equals(owner))
                .max(Comparator.comparingLong(GraveMeta::createdAt))
                .orElse(null);
    }

    private Map<String, String> graveLocationPlaceholders(GraveMeta meta) {
        return Map.of(
                "world", meta.world(),
                "x", Integer.toString(meta.x()),
                "y", Integer.toString(meta.y()),
                "z", Integer.toString(meta.z()));
    }

    private void playConfiguredSound(Player player, String path, String fallback) {
        String sound = getConfig().getString(path, fallback);
        if (sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        }
    }

    /**
     * Comando interno de consola ejecutado por la acción del CONSUMABLE de MMOItems.
     * Espera al siguiente tick antes de reservar la fruta y empezar el viaje.
     */
    public boolean executeDeathFruit(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            send(sender, "messages.death-fruit-console-only", Map.of());
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(color("&cUso: /mdvgraves deathfruit <jugador>"));
            return true;
        }
        if (!getConfig().getBoolean("utilities.death-fruit.enabled", true))
            return true;

        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            send(sender, "messages.player-not-found", Map.of("player", args[1]));
            return true;
        }
        long now = System.currentTimeMillis();
        if (activeGraveBacks.containsKey(player.getUniqueId())
                || graveBackTransportLocks.getOrDefault(player.getUniqueId(), 0L) > now
                || deathFruitUseLocks.getOrDefault(player.getUniqueId(), 0L) > now)
            return true;
        PendingDeathFruitUse pending = resolveDeathFruitUse(player, now);
        if (pending == null) {
            send(player, "messages.death-fruit-not-held", Map.of());
            return true;
        }
        deathFruitUseLocks.put(player.getUniqueId(), now
                + Math.max(750L, getConfig().getLong("utilities.death-fruit.use-lock-ms", 750L)));
        queueGraveBack(player, true, true, true, false, pending, result -> {
            if (!player.isOnline())
                return;
            if (result == GraveBackResult.SUCCESS) {
                playConfiguredSound(player, "utilities.death-fruit.sound", "entity.enderman.teleport");
                send(player, "messages.death-fruit-success", Map.of());
            } else {
                sendDeathFruitFailure(player, result);
            }
        });
        return true;
    }

    private void sendDeathFruitFailure(Player player, GraveBackResult result) {
        switch (result) {
            case NO_GRAVES -> send(player, "messages.death-fruit-no-graves", Map.of());
            case WORLD_UNAVAILABLE -> {
                GraveMeta latest = latestGrave(player.getUniqueId());
                send(player, "messages.back-world-unavailable",
                        latest == null ? Map.of("world", "?") : Map.of("world", latest.world()));
            }
            case NO_SAFE_LOCATION -> {
                GraveMeta latest = latestGrave(player.getUniqueId());
                if (latest == null)
                    send(player, "messages.death-fruit-no-graves", Map.of());
                else
                    send(player, "messages.back-no-safe-location", graveLocationPlaceholders(latest));
            }
            case TELEPORT_FAILED -> send(player, "messages.back-teleport-failed", Map.of());
            default -> send(player, "messages.death-fruit-failed", Map.of());
        }
    }

    private PendingDeathFruitUse snapshotDeathFruitUse(Player player, EquipmentSlot hand, ItemStack item, long now) {
        int slot = hand == EquipmentSlot.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        return new PendingDeathFruitUse(hand, slot, item.clone(), item.getAmount(), countSimilarFruit(player, item), now);
    }

    private PendingDeathFruitUse resolveDeathFruitUse(Player player, long now) {
        PendingDeathFruitUse pending = pendingDeathFruitUses.remove(player.getUniqueId());
        long maxAge = Math.max(250L, getConfig().getLong("utilities.death-fruit.pending-use-max-age-ms", 2000L));
        if (pending != null && now - pending.createdAt() <= maxAge && isConfiguredDeathFruit(pending.snapshot()))
            return pending;
        EquipmentSlot hand = findDeathFruitHand(player);
        if (hand == null)
            return null;
        ItemStack stack = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        return snapshotDeathFruitUse(player, hand, stack, now);
    }

    private int countSimilarFruit(Player player, ItemStack snapshot) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && snapshot.isSimilar(item))
                count += item.getAmount();
        }
        return count;
    }

    private boolean wasFruitAlreadyConsumed(Player player, PendingDeathFruitUse pending) {
        ItemStack current = player.getInventory().getItem(pending.slot());
        int remainingInSlot = current != null && pending.snapshot().isSimilar(current) ? current.getAmount() : 0;
        // Mover el stack a otra mano/slot no cuenta como consumo. Solo se reconoce
        // un descuento de una unidad en el slot usado y en el total del inventario.
        return pending.originalAmount() - remainingInSlot == 1
                && pending.originalTotal() - countSimilarFruit(player, pending.snapshot()) == 1;
    }

    private void refundDeathFruit(Player player, ItemStack snapshot) {
        ItemStack refund = snapshot.clone();
        refund.setAmount(1);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(refund);
        if (!leftovers.isEmpty()) {
            for (ItemStack item : leftovers.values())
                player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
    }

    private EquipmentSlot findDeathFruitHand(Player player) {
        if (isConfiguredDeathFruit(player.getInventory().getItemInMainHand()))
            return EquipmentSlot.HAND;
        if (isConfiguredDeathFruit(player.getInventory().getItemInOffHand()))
            return EquipmentSlot.OFF_HAND;
        return null;
    }

    private boolean consumeDeathFruit(Player player, PendingDeathFruitUse pending) {
        ItemStack preferred = player.getInventory().getItem(pending.slot());
        if (preferred != null && pending.snapshot().isSimilar(preferred))
            return consumeDeathFruitSlot(player, pending.slot(), preferred);
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && pending.snapshot().isSimilar(contents[slot]))
                return consumeDeathFruitSlot(player, slot, contents[slot]);
        }
        return false;
    }

    private boolean consumeDeathFruitSlot(Player player, int slot, ItemStack stack) {
        if (stack.getAmount() <= 0)
            return false;
        ItemStack remaining = stack.getAmount() <= 1 ? null : stack.clone();
        if (remaining != null)
            remaining.setAmount(stack.getAmount() - 1);
        player.getInventory().setItem(slot, remaining);
        return true;
    }

    private void setupMmoItemsBridge() {
        mmoItemsBridgeReady = false;
        mmoItemsBridgeWarningLogged = false;
        nbtItemGetMethod = null;
        nbtItemGetStringMethod = null;
        deathFruitExpectedType = Optional
                .ofNullable(getConfig().getString("utilities.death-fruit.mmoitems-type", "CONSUMABLE"))
                .orElse("CONSUMABLE");
        deathFruitExpectedId = Optional
                .ofNullable(getConfig().getString("utilities.death-fruit.mmoitems-id", "FRUTA_DE_LA_MUERTE"))
                .orElse("FRUTA_DE_LA_MUERTE");
        if (!getConfig().getBoolean("utilities.death-fruit.enabled", true))
            return;
        if (Bukkit.getPluginManager().getPlugin("MMOItems") == null
                || Bukkit.getPluginManager().getPlugin("MythicLib") == null) {
            getLogger().warning("Fruta de la Muerte activada, pero MMOItems/MythicLib no están disponibles.");
            return;
        }
        try {
            Class<?> nbtItemClass = Class.forName("io.lumine.mythic.lib.api.item.NBTItem");
            nbtItemGetMethod = nbtItemClass.getMethod("get", ItemStack.class);
            nbtItemGetStringMethod = nbtItemClass.getMethod("getString", String.class);
            mmoItemsBridgeReady = true;
        } catch (ReflectiveOperationException ex) {
            getLogger().log(Level.WARNING,
                    "No se pudo inicializar el puente MMOItems/MythicLib para la Fruta de la Muerte.", ex);
        }
    }

    private boolean isConfiguredDeathFruit(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta() || !mmoItemsBridgeReady)
            return false;
        try {
            Object nbt = nbtItemGetMethod.invoke(null, stack);
            String type = String.valueOf(nbtItemGetStringMethod.invoke(nbt, "MMOITEMS_ITEM_TYPE"));
            String id = String.valueOf(nbtItemGetStringMethod.invoke(nbt, "MMOITEMS_ITEM_ID"));
            return deathFruitExpectedType.equalsIgnoreCase(type)
                    && deathFruitExpectedId.equalsIgnoreCase(id);
        } catch (ReflectiveOperationException ex) {
            if (!mmoItemsBridgeWarningLogged) {
                mmoItemsBridgeWarningLogged = true;
                getLogger().log(Level.WARNING, "No se pudo leer un MMOItem para la Fruta de la Muerte.", ex);
            }
            return false;
        }
    }

    private Location findSafeTeleportLocation(GraveMeta meta, World world, int radius, Location facing) {
        int baseX = meta.x();
        int baseY = meta.y() + 1;
        int baseZ = meta.z();

        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r)
                        continue;
                    for (int dy = 0; dy <= 3; dy++) {
                        int x = baseX + dx;
                        int y = baseY + dy;
                        int z = baseZ + dz;
                        if (isSafeStandingSpot(world, x, y, z, meta.id())) {
                            return new Location(world, x + 0.5, y, z + 0.5, facing.getYaw(), facing.getPitch());
                        }
                    }
                    for (int dy = -1; dy >= -3; dy--) {
                        int x = baseX + dx;
                        int y = baseY + dy;
                        int z = baseZ + dz;
                        if (isSafeStandingSpot(world, x, y, z, meta.id())) {
                            return new Location(world, x + 0.5, y, z + 0.5, facing.getYaw(), facing.getPitch());
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isSafeStandingSpot(World world, int x, int y, int z, UUID graveId) {
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight())
            return false;
        if (!world.isChunkLoaded(x >> 4, z >> 4))
            return false;
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block floor = world.getBlockAt(x, y - 1, z);
        if (!feet.isPassable() || !head.isPassable())
            return false;
        return floor.getType().isSolid() || graveId.equals(trackedGraveId(floor));
    }

    public int deleteAllGraves() throws SQLException {
        // Cierra primero cualquier bolsa abierta. InventoryCloseEvent persiste su
        // estado
        // antes del borrado global, evitando carreras o inventarios visuales obsoletos.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof GraveHolder) {
                player.closeInventory();
            }
        }

        List<GraveMeta> snapshot = new ArrayList<>(graves.values());
        int deleted;
        // Una sola operación SQLite: más barata que borrar tumba por tumba.
        try (Statement st = connection.createStatement()) {
            deleted = st.executeUpdate("DELETE FROM graves");
        }

        graves.clear();
        gravesByBlock.clear();
        activeViewers.clear();
        openGraveInventories.clear();
        forcedClosingGraves.clear();

        // Solo toca bloques en chunks ya cargados. Los chunks descargados no se
        // fuerzan;
        // sus cabezas huérfanas serán retiradas por ChunkLoadEvent cuando vuelvan a
        // cargar.
        for (GraveMeta meta : snapshot) {
            World world = Bukkit.getWorld(meta.world());
            if (world == null || !world.isChunkLoaded(meta.x() >> 4, meta.z() >> 4))
                continue;
            Block block = world.getBlockAt(meta.x(), meta.y(), meta.z());
            if (isOurHead(block, meta.id()))
                block.setType(Material.AIR, false);
        }
        return deleted;
    }

    private enum GraveBackResult {
        SUCCESS,
        DISABLED,
        NO_PERMISSION,
        COOLDOWN,
        NO_GRAVES,
        WORLD_UNAVAILABLE,
        NO_SAFE_LOCATION,
        TELEPORT_FAILED
    }

    private record SupportPatch(Block block, BlockData original) {
    }

    private record PendingDeathFruitUse(EquipmentSlot hand, int slot, ItemStack snapshot,
            int originalAmount, int originalTotal, long createdAt) {
    }

    private static final class GraveBackRequest {
        private final Player player;
        private final boolean bypassEnabled;
        private final boolean bypassPermission;
        private final boolean bypassCooldown;
        private final boolean sendMessages;
        private final PendingDeathFruitUse fruitUse;
        private final Consumer<GraveBackResult> completion;
        private final Set<Chunk> chunks = new HashSet<>();
        private BukkitTask timeoutTask;
        private GraveMeta grave;
        private World world;
        private int radius;
        private long cooldownSeconds;
        private boolean fruitEvaluated;
        private boolean fruitCharged;
        private PlayerDeathEvent refundDeathEvent;

        private GraveBackRequest(Player player, boolean bypassEnabled, boolean bypassPermission,
                boolean bypassCooldown, boolean sendMessages, PendingDeathFruitUse fruitUse,
                Consumer<GraveBackResult> completion) {
            this.player = player;
            this.bypassEnabled = bypassEnabled;
            this.bypassPermission = bypassPermission;
            this.bypassCooldown = bypassCooldown;
            this.sendMessages = sendMessages;
            this.fruitUse = fruitUse;
            this.completion = completion;
        }
    }

    private record BlockKey(String world, int x, int y, int z) {
    }

    private record GraveMeta(UUID id, UUID ownerUuid, String ownerName, String world, int x, int y, int z,
            long createdAt, Long firstOpenedAt, long expiresAt, boolean ownerProtected) {
        BlockKey blockKey() {
            return new BlockKey(world, x, y, z);
        }

        GraveMeta withOpened(long opened, long expires) {
            return new GraveMeta(id, ownerUuid, ownerName, world, x, y, z, createdAt, opened, expires, ownerProtected);
        }
    }

    private static final class GraveHolder implements InventoryHolder {
        private final UUID graveId;
        private Inventory inventory;

        private GraveHolder(UUID graveId) {
            this.graveId = graveId;
        }

        UUID graveId() {
            return graveId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
