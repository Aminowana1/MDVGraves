package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import xyz.mdvcraft.mdvgraves.MDVGravesPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.DriverManager;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProtectedReconnectTest {
    @Test void disableDeathDropPolicyDistinguishesTrueFalseAndMissingNbt() throws Exception {
        try (Harness h = new Harness()) {
            assertTrue(h.policy.isMmoItemsDisableDeathDrop(item("sword", 1, true)));
            assertFalse(h.policy.isMmoItemsDisableDeathDrop(item("sword", 1, false)));
            assertFalse(h.policy.isMmoItemsDisableDeathDrop(item("sword", 1, null)));
            assertFalse(h.policy.isMmoItemsDisableDeathDrop(null));
            ItemStack airItem = mock(ItemStack.class);
            Material air = mock(Material.class);
            when(air.isAir()).thenReturn(true);
            when(airItem.getType()).thenReturn(air);
            assertFalse(h.policy.isMmoItemsDisableDeathDrop(airItem));
        }
    }

    @Test void lowestListenerMovesOnlyTrueMmoFlagsFromDropsToNativeKeepsWithExactMetadata() throws Exception {
        try (Harness h = new Harness()) {
            h.sampleInventory();
            h.storage[11] = item("false-flag", 8, false);
            PlayerDeathEvent event = h.death(false);
            h.manager.keepMmoItemsOnDeath(event);
            assertEquals(3, event.getItemsToKeep().size());
            assertEquals(6, total(event.getItemsToKeep()));
            assertListItem(event.getItemsToKeep(), "sword", 3);
            assertListItem(event.getItemsToKeep(), "helmet", 1);
            assertListItem(event.getItemsToKeep(), "totem", 2);
            assertEquals(Set.of("ordinary", "false-flag"),
                    new HashSet<>(event.getDrops().stream().map(ProtectedReconnectTest::id).toList()));
            assertEquals(24, total(event.getDrops()));
            assertEquals(30, h.inventoryUnits(), "The listener leaves the live inventory to native death handling");
            verify(h.inventory, never()).clear();
            verify(h.inventory, never()).addItem(any(ItemStack.class));
            verify(h.player, never()).getInventory();
            EventHandler annotation = LogoutBodyManager.class
                    .getMethod("keepMmoItemsOnDeath", PlayerDeathEvent.class).getAnnotation(EventHandler.class);
            assertEquals(EventPriority.LOWEST, annotation.priority());
            assertTrue(annotation.ignoreCancelled());
        }
    }

    @Test void nativeKeepBudgetPreservesAnExactMultisetWithoutDuplicatingPreexistingKeeps() throws Exception {
        try (Harness h = new Harness()) {
            h.storage[2] = item("sword", 3, true);
            h.storage[5] = item("sword", 3, true);
            PlayerDeathEvent event = h.death(false);
            event.getItemsToKeep().add(item("sword", 3, true));
            h.manager.keepMmoItemsOnDeath(event);
            assertTrue(event.getDrops().isEmpty());
            assertEquals(2, event.getItemsToKeep().size());
            assertEquals(6, total(event.getItemsToKeep()));
            h.manager.keepMmoItemsOnDeath(event);
            assertEquals(2, event.getItemsToKeep().size());
        }
    }

    @Test void sameItemWithDifferentMetadataOrStackAmountsRemainsSeparateNativeKeepEntries() throws Exception {
        try (Harness h = new Harness()) {
            h.storage[2] = item("sword", 3, true);
            h.storage[5] = new FixtureItemStack("sword", 2, true, "reforged:damage=19:enchant=5");
            PlayerDeathEvent event = h.death(false);
            event.getItemsToKeep().add(item("sword", 1, true));
            h.manager.keepMmoItemsOnDeath(event);
            assertEquals(3, event.getItemsToKeep().size(),
                    "A preexisting one-item stack cannot stand in for an exact three-item drop");
            assertTrue(event.getItemsToKeep().stream().anyMatch(i ->
                    ((FixtureItemStack) i).metadata.equals("reforged:damage=19:enchant=5") && i.getAmount() == 2));
            assertTrue(event.getDrops().isEmpty());
        }
    }

    @Test void mmoItemsHighListenerCannotCacheNativeRetainedDropsForAnOldPlayerConnection() throws Exception {
        try (Harness h = new Harness()) {
            h.sampleInventory();
            PlayerDeathEvent event = h.death(false);
            h.manager.keepMmoItemsOnDeath(event);
            // MMOItems HIGH examines the remaining drops after our LOWEST handler.
            List<ItemStack> oldConnectionCache = event.getDrops().stream()
                    .filter(h.policy::isMmoItemsDisableDeathDrop).toList();
            assertTrue(oldConnectionCache.isEmpty());
            h.applyNativeDeathKeeps(event);
            assertItem(h.storage[2], "sword", 3);
            assertItem(h.armor[1], "helmet", 1);
            assertItem(h.offhand, "totem", 2);
            assertNull(h.storage[7]);
            assertEquals(6, h.inventoryUnits());
        }
    }

    @Test void nativeRetainedInventorySurvivesDeathScreenQuitJoinAndRespawnWithoutPluginRestoration() throws Exception {
        try (Harness h = new Harness()) {
            h.sampleInventory();
            h.nativeDeathAndQuit();
            assertEquals(6, h.inventoryUnits());
            assertTrue(h.sessions.get(h.uuid).inventory().isEmpty());
            assertTrue(h.sessions.get(h.uuid).protectedInventory().isEmpty());
            ItemStack[] persistedStorage = h.storage.clone();
            ItemStack[] persistedArmor = h.armor.clone();
            ItemStack persistedOffhand = h.offhand;
            h.clearContents();
            h.storage = persistedStorage;
            h.armor = persistedArmor;
            h.offhand = persistedOffhand; // Model loading native playerdata on the new connection.
            when(h.player.isDead()).thenReturn(true);
            when(h.player.getHealth()).thenReturn(0.0);
            h.installNativeRespawn();
            h.manager.onJoin(h.join());
            h.runNext();
            verify(h.spigot).respawn();
            assertEquals(6, h.inventoryUnits());
            h.runNext();
            h.runNext();
            assertFalse(h.manager.isPendingRespawn(h.uuid));
            assertItem(h.storage[2], "sword", 3);
            assertItem(h.armor[1], "helmet", 1);
            assertItem(h.offhand, "totem", 2);
            verify(h.inventory, never()).clear();
            verify(h.inventory, never()).setStorageContents(any());
            verify(h.inventory, never()).setArmorContents(any());
            verify(h.inventory, never()).setItemInOffHand(nullable(ItemStack.class));
            verify(h.inventory, never()).addItem(any(ItemStack.class));
        }
    }

    @Test void authenticationAndPostLoginHoldDoNotAddOrOverwriteNativeKeptItems() throws Exception {
        try (Harness h = new Harness()) {
            h.sampleInventory();
            h.nativeDeathAndQuit();
            h.reviveAtOldLocation();
            when(h.nlogin.isNLoginEnabled()).thenReturn(true);
            when(h.nlogin.isAuthenticated(h.player)).thenReturn(false);
            h.manager.onJoin(h.join());
            h.authPoll.run();
            assertTrue(h.tasks.isEmpty());
            assertEquals(6, h.inventoryUnits());
            when(h.nlogin.isAuthenticated(h.player)).thenReturn(true);
            h.authPoll.run();
            h.runNext();
            h.storage[9] = item("login-gift", 4, null);
            h.runNext();
            assertFalse(h.manager.isPendingRespawn(h.uuid));
            assertEquals(10, h.inventoryUnits());
            assertItem(h.storage[9], "login-gift", 4);
            verify(h.inventory, never()).clear();
            verify(h.inventory, never()).setStorageContents(any());
            verify(h.inventory, never()).addItem(any(ItemStack.class));
        }
    }

    @Test void manualRespawnKeepsTheNativeItemsAndDoesNotCreateAnOfflineRecoverySnapshot() throws Exception {
        try (Harness h = new Harness()) {
            h.sampleInventory();
            PlayerDeathEvent event = h.death(false);
            h.manager.keepMmoItemsOnDeath(event);
            h.applyNativeDeathKeeps(event);
            h.manager.onOnlineDeath(event);
            h.manager.onRespawn(h.respawn());
            assertFalse(h.manager.isPendingRespawn(h.uuid));
            assertEquals(6, h.inventoryUnits());
            assertTrue(h.tasks.isEmpty());
            verify(h.inventory, never()).addItem(any(ItemStack.class));
        }
    }

    @Test void keepInventoryFlagLeavesDropsAndNativeKeepsUnderNormalServerControl() throws Exception {
        try (Harness h = new Harness()) {
            h.sampleInventory();
            PlayerDeathEvent event = h.death(true);
            List<ItemStack> original = new ArrayList<>(event.getDrops());
            h.manager.keepMmoItemsOnDeath(event);
            assertEquals(original, event.getDrops());
            assertTrue(event.getItemsToKeep().isEmpty());
            assertEquals(22, h.inventoryUnits());
        }
    }

    @Test void keepInventoryPermissionAppliedLaterKeepsTheFullInventoryWithoutAddingNativeKeepCopies() throws Exception {
        try (Harness h = new Harness()) {
            h.sampleInventory();
            when(h.player.hasPermission("mdvgraves.keepinventory")).thenReturn(true);
            PlayerDeathEvent event = h.death(false);
            h.manager.keepMmoItemsOnDeath(event);
            assertEquals(3, event.getItemsToKeep().size());
            assertEquals(6, total(event.getItemsToKeep()));
            // MDVGraves sets the final keepInventory flag at HIGHEST. Paper then
            // ignores getItemsToKeep because it retains the existing full inventory.
            when(event.getKeepInventory()).thenReturn(true);
            event.getDrops().clear();
            h.applyNativeDeathKeeps(event);
            assertEquals(22, h.inventoryUnits());
            assertItem(h.storage[7], "ordinary", 16);
        }
    }

    @Test void cancelledDisabledUntrackedAndShutdownDeathsAreNotModified() throws Exception {
        try (Harness h = new Harness()) {
            h.sampleInventory();
            PlayerDeathEvent cancelled = h.death(false);
            when(cancelled.isCancelled()).thenReturn(true);
            h.dispatchNativeHandler(cancelled);
            assertEquals(4, cancelled.getDrops().size());
            assertTrue(cancelled.getItemsToKeep().isEmpty());

            configNoop(h, "settings.enabled", false);
            h.config.set("settings.enabled", true);
            when(h.survival.getName()).thenReturn("untracked");
            PlayerDeathEvent untracked = h.death(false);
            h.manager.keepMmoItemsOnDeath(untracked);
            assertEquals(4, untracked.getDrops().size());
            assertTrue(untracked.getItemsToKeep().isEmpty());
            when(h.survival.getName()).thenReturn("world");
            set(h.manager, "shuttingDown", true);
            PlayerDeathEvent shutdown = h.death(false);
            h.manager.keepMmoItemsOnDeath(shutdown);
            assertEquals(4, shutdown.getDrops().size());
            assertTrue(shutdown.getItemsToKeep().isEmpty());
        }
    }

    @Test void unavailableMythicBridgeNeverMistakesAnOrdinaryDropForNativeRetention() throws Exception {
        try (Harness h = new Harness()) {
            h.sampleInventory();
            h.setPolicy("mythicBridgeReady", false);
            PlayerDeathEvent event = h.death(false);
            h.manager.keepMmoItemsOnDeath(event);
            assertEquals(4, event.getDrops().size());
            assertTrue(event.getItemsToKeep().isEmpty());
        }
    }

    @Test void refundedProtectedFruitAddsAnExtraNativeKeepEntryEvenWhenAnIdenticalUnitAlreadyExists() throws Exception {
        try (Harness h = new Harness()) {
            h.storage[2] = item("death-fruit", 1, true);
            PlayerDeathEvent event = h.death(false);
            h.manager.keepMmoItemsOnDeath(event);
            assertEquals(1, event.getItemsToKeep().size());
            assertTrue(event.getDrops().isEmpty());

            // The second unit was reserved outside the inventory before death.
            // It is an additional refund, rather than a duplicate of the first keep.
            FixtureItemStack refund = item("death-fruit", 1, true);
            assertTrue(h.manager.keepRefundedMmoItemOnDeath(event, refund));
            assertEquals(2, event.getItemsToKeep().size());
            assertEquals(2, total(event.getItemsToKeep()));
            assertEquals(event.getItemsToKeep().get(0), event.getItemsToKeep().get(1));
            assertNotSame(refund, event.getItemsToKeep().get(1), "The keeper must own an independent item copy");
            assertItem(event.getItemsToKeep().get(1), "death-fruit", 1);
            List<ItemStack> keptBeforeUnprotectedRefunds = new ArrayList<>(event.getItemsToKeep());
            assertFalse(h.manager.keepRefundedMmoItemOnDeath(event, item("death-fruit", 1, false)));
            assertFalse(h.manager.keepRefundedMmoItemOnDeath(event, item("death-fruit", 1, null)));
            assertEquals(keptBeforeUnprotectedRefunds, event.getItemsToKeep());
            assertTrue(event.getDrops().isEmpty());
        }
    }

    private static void configNoop(Harness h, String path, Object value) {
        h.config.set(path, value);
        PlayerDeathEvent event = h.death(false);
        h.manager.keepMmoItemsOnDeath(event);
        assertEquals(4, event.getDrops().size());
        assertTrue(event.getItemsToKeep().isEmpty());
    }
    private static void assertListItem(List<ItemStack> items, String id, int amount) {
        assertItem(items.stream().filter(i -> id(i).equals(id)).findFirst().orElseThrow(), id, amount);
    }
    private static int total(List<ItemStack> items) { return items.stream().mapToInt(ItemStack::getAmount).sum(); }
    private static String id(ItemStack item) { return ((FixtureItemStack) item).identifier; }
    private static FixtureItemStack item(String id, int amount, Boolean keep) {
        return new FixtureItemStack(id, amount, keep, "custom:" + id + ":damage=7:enchant=3");
    }
    private static void assertItem(ItemStack actual, String id, int amount) {
        assertNotNull(actual);
        assertEquals(id, id(actual));
        assertEquals(amount, actual.getAmount());
        assertEquals("custom:" + id + ":damage=7:enchant=3", ((FixtureItemStack) actual).metadata);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
    private static void invoke(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(object);
    }

    /**
     * Concrete test item with real clone/configuration serialization. Only Material
     * and ItemMeta query adapters are mocked because Paper registries need a server.
     */
    @SerializableAs("MDVProtectedReconnectFixture")
    public static final class FixtureItemStack extends ItemStack {
        private static final Material TYPE = mock(Material.class);
        final String identifier;
        final Boolean keep;
        final String metadata;
        private int count;
        FixtureItemStack(String identifier, int count, Boolean keep, String metadata) {
            super();
            this.identifier = identifier;
            this.count = count;
            this.keep = keep;
            this.metadata = metadata;
        }
        @Override public Material getType() { return TYPE; }
        @Override public int getAmount() { return count; }
        @Override public void setAmount(int amount) { count = amount; }
        @Override public int getMaxStackSize() { return 64; }
        @Override public boolean hasItemMeta() { return true; }
        @Override public ItemMeta getItemMeta() {
            ItemMeta meta = mock(ItemMeta.class);
            when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            return meta;
        }
        @Override public FixtureItemStack clone() { return new FixtureItemStack(identifier, count, keep, metadata); }
        @Override public boolean isSimilar(ItemStack other) {
            return other instanceof FixtureItemStack fixture
                    && identifier.equals(fixture.identifier) && Objects.equals(keep, fixture.keep)
                    && metadata.equals(fixture.metadata);
        }
        @Override public boolean equals(Object other) {
            return other instanceof FixtureItemStack fixture && count == fixture.count && isSimilar(fixture);
        }
        @Override public int hashCode() { return Objects.hash(identifier, count, keep, metadata); }
        @Override public Map<String, Object> serialize() {
            Map<String, Object> map = new HashMap<>();
            map.put("identifier", identifier);
            map.put("count", count);
            map.put("metadata", metadata);
            if (keep != null) map.put("keep", keep);
            return map;
        }
        public static FixtureItemStack deserialize(Map<String, Object> map) {
            return new FixtureItemStack((String) map.get("identifier"), ((Number) map.get("count")).intValue(),
                    (Boolean) map.get("keep"), (String) map.get("metadata"));
        }
    }

    public static final class FixtureNbt {
        private final FixtureItemStack item;
        private FixtureNbt(FixtureItemStack item) { this.item = item; }
        public static FixtureNbt get(ItemStack item) { return new FixtureNbt((FixtureItemStack) item); }
        public boolean hasTag(String key) { return key.equals("MMOITEMS_DISABLE_DEATH_DROP") && item.keep != null; }
        public boolean getBoolean(String key) { return Boolean.TRUE.equals(item.keep); }
        public String getString(String key) { return ""; }
    }

    private static final class Deferred {
        final Runnable run;
        final long delay;
        boolean cancelled;
        Deferred(Runnable run, long delay) { this.run = run; this.delay = delay; }
    }

    private static final class Harness implements AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final Player player = mock(Player.class);
        final Player.Spigot spigot = mock(Player.Spigot.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final World survival = mock(World.class);
        final World lobby = mock(World.class);
        final MDVGravesPlugin plugin = mock(MDVGravesPlugin.class);
        final LogoutBodyRepository repository = mock(LogoutBodyRepository.class);
        final NLoginBridge nlogin = mock(NLoginBridge.class);
        final YamlConfiguration config = new YamlConfiguration();
        final LogoutBodyManager manager = mock(LogoutBodyManager.class, CALLS_REAL_METHODS);
        final Map<UUID, LogoutBodySession> sessions = new HashMap<>();
        final ArrayDeque<Deferred> tasks = new ArrayDeque<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final ProtectedItemPolicy policy;
        ItemStack[] storage = new ItemStack[36];
        ItemStack[] armor = new ItemStack[4];
        ItemStack offhand;
        int held = 2;
        Runnable authPoll;

        Harness() throws Exception {
            try {
                ConfigurationSerialization.registerClass(FixtureItemStack.class, "MDVProtectedReconnectFixture");
                config.set("settings.enabled-worlds", List.of("world"));
                config.set("logout-body.enabled", false);
                config.set("logout-body.nlogin.post-auth-lock-ticks", 1L);
                when(plugin.getConfig()).thenReturn(config);
                when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
                when(player.getUniqueId()).thenReturn(uuid);
                when(player.getName()).thenReturn("ProtectedDeadPlayer");
                when(player.getWorld()).thenReturn(survival);
                when(player.getLocation()).thenReturn(new Location(survival, 40, 70, 50));
                when(player.isOnline()).thenReturn(true);
                when(player.getHealth()).thenReturn(20.0);
                when(player.getInventory()).thenReturn(inventory);
                when(player.spigot()).thenReturn(spigot);
                when(survival.getName()).thenReturn("world");
                when(lobby.getName()).thenReturn("world5");
                when(lobby.getSpawnLocation()).thenReturn(new Location(lobby, 0.5, 65.0, 0.5));
                when(player.teleport(any(Location.class))).thenReturn(true);
                doReturn(20.0).when(manager).resolveMaxHealth(player);
                when(inventory.getStorageContents()).thenAnswer(call -> storage.clone());
                when(inventory.getArmorContents()).thenAnswer(call -> armor.clone());
                when(inventory.getItemInOffHand()).thenAnswer(call -> offhand);
                when(inventory.getHeldItemSlot()).thenAnswer(call -> held);
                when(inventory.getContents()).thenAnswer(call -> contents());
                when(inventory.getSize()).thenReturn(41);
                when(inventory.getItem(anyInt())).thenAnswer(call -> getSlot(call.getArgument(0)));
                doAnswer(call -> { setSlot(call.getArgument(0), call.getArgument(1)); return null; })
                        .when(inventory).setItem(anyInt(), nullable(ItemStack.class));
                doAnswer(call -> { storage = call.getArgument(0); return null; })
                        .when(inventory).setStorageContents(any());
                doAnswer(call -> { armor = call.getArgument(0); return null; })
                        .when(inventory).setArmorContents(any());
                doAnswer(call -> { offhand = call.getArgument(0); return null; })
                        .when(inventory).setItemInOffHand(nullable(ItemStack.class));
                doAnswer(call -> { held = call.getArgument(0); return null; })
                        .when(inventory).setHeldItemSlot(anyInt());
                doAnswer(call -> { clearContents(); return null; }).when(inventory).clear();
                when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> addItem(call.getArgument(0)));
                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
                bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
                bukkit.when(() -> Bukkit.getWorld("world5")).thenReturn(lobby);
                when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(call -> {
                    Deferred deferred = new Deferred(call.getArgument(1), call.getArgument(2));
                    tasks.add(deferred);
                    BukkitTask task = mock(BukkitTask.class);
                    doAnswer(ignored -> { deferred.cancelled = true; return null; }).when(task).cancel();
                    return task;
                });
                when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
                    authPoll = call.getArgument(1);
                    return mock(BukkitTask.class);
                });
                policy = new ProtectedItemPolicy(plugin);
                setPolicy("mythicBridgeReady", true);
                setPolicy("nbtGet", FixtureNbt.class.getMethod("get", ItemStack.class));
                setPolicy("nbtGetBoolean", FixtureNbt.class.getMethod("getBoolean", String.class));
                setPolicy("nbtGetString", FixtureNbt.class.getMethod("getString", String.class));
                setPolicy("nbtHasTag", FixtureNbt.class.getMethod("hasTag", String.class));
                for (Field field : LogoutBodyManager.class.getDeclaredFields()) {
                    field.setAccessible(true);
                    if (Map.class.isAssignableFrom(field.getType())) field.set(manager, new HashMap<>());
                    if (Set.class.isAssignableFrom(field.getType())) field.set(manager, new HashSet<>());
                }
                set(manager, "plugin", plugin);
                set(manager, "repository", repository);
                set(manager, "nLogin", nlogin);
                set(manager, "protectedItems", policy);
                set(manager, "sessions", sessions);
            } catch (Exception | Error failure) {
                bukkit.close();
                throw failure;
            }
        }

        private void setPolicy(String name, Object value) throws Exception {
            Field field = ProtectedItemPolicy.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(policy, value);
        }
        void sampleInventory() {
            storage[2] = item("sword", 3, true);
            storage[7] = item("ordinary", 16, null);
            armor[1] = item("helmet", 1, true);
            offhand = item("totem", 2, true);
        }
        void clearContents() { storage = new ItemStack[36]; armor = new ItemStack[4]; offhand = null; }
        ItemStack[] contents() {
            ItemStack[] all = Arrays.copyOf(storage, 41);
            System.arraycopy(armor, 0, all, 36, 4);
            all[40] = offhand;
            return all;
        }
        ItemStack getSlot(int slot) { return slot < 36 ? storage[slot] : slot < 40 ? armor[slot - 36] : offhand; }
        void setSlot(int slot, ItemStack item) {
            if (slot < 36) storage[slot] = item;
            else if (slot < 40) armor[slot - 36] = item;
            else offhand = item;
        }
        HashMap<Integer, ItemStack> addItem(ItemStack input) {
            ItemStack remaining = input.clone();
            for (int i = 0; i < storage.length; i++) {
                if (storage[i] == null || storage[i].getAmount() == 0) {
                    storage[i] = remaining;
                    return new HashMap<>();
                }
                if (storage[i].isSimilar(remaining) && storage[i].getAmount() + remaining.getAmount() <= 64) {
                    storage[i].setAmount(storage[i].getAmount() + remaining.getAmount());
                    return new HashMap<>();
                }
            }
            return new HashMap<>(Map.of(0, remaining));
        }
        int inventoryUnits() { return Arrays.stream(contents()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum(); }
        int unitsOf(String identifier) {
            return Arrays.stream(contents()).filter(i -> i instanceof FixtureItemStack && id(i).equals(identifier))
                    .mapToInt(ItemStack::getAmount).sum();
        }
        void nativeDeathAndQuit() {
            PlayerDeathEvent event = death(false);
            manager.keepMmoItemsOnDeath(event);
            applyNativeDeathKeeps(event);
            manager.onOnlineDeath(event);
            manager.onQuit(quit());
        }
        void dispatchNativeHandler(PlayerDeathEvent event) throws Exception {
            EventHandler handler = LogoutBodyManager.class.getMethod("keepMmoItemsOnDeath", PlayerDeathEvent.class).getAnnotation(EventHandler.class);
            if (!event.isCancelled() || !handler.ignoreCancelled()) manager.keepMmoItemsOnDeath(event);
        }
        void applyNativeDeathKeeps(PlayerDeathEvent event) {
            if (event.getKeepInventory()) return;
            List<ItemStack> keepBudget = new ArrayList<>(event.getItemsToKeep());
            for (int slot = 0; slot < 41; slot++) {
                ItemStack current = getSlot(slot);
                if (current == null) continue;
                if (!keepBudget.remove(current)) setSlot(slot, null);
            }
        }
        void reviveAtOldLocation() {
            when(player.isDead()).thenReturn(false);
            when(player.getHealth()).thenReturn(1.0);
        }
        void installNativeRespawn() {
            doAnswer(call -> {
                manager.onRespawn(respawn());
                when(player.isDead()).thenReturn(false);
                when(player.getHealth()).thenReturn(20.0);
                return null;
            }).when(spigot).respawn();
        }
        void runNext() {
            while (!tasks.isEmpty() && tasks.peek().cancelled) tasks.removeFirst();
            assertFalse(tasks.isEmpty(), "Expected deferred respawn/auth/inventory work");
            tasks.removeFirst().run.run();
        }
        long nextDelay() {
            while (!tasks.isEmpty() && tasks.peek().cancelled) tasks.removeFirst();
            assertFalse(tasks.isEmpty());
            return tasks.peek().delay;
        }
        PlayerDeathEvent death(boolean keep) {
            PlayerDeathEvent event = mock(PlayerDeathEvent.class);
            when(event.getEntity()).thenReturn(player);
            when(event.getKeepInventory()).thenReturn(keep);
            when(event.getDrops()).thenReturn(new ArrayList<>(Arrays.stream(contents()).filter(Objects::nonNull).map(ItemStack::clone).toList()));
            when(event.getItemsToKeep()).thenReturn(new ArrayList<>());
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
        @Override public void close() {
            bukkit.close();
            ConfigurationSerialization.unregisterClass(FixtureItemStack.class);
        }
    }
}
