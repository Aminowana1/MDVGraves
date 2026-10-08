package xyz.mdvcraft.mdvgraves;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import xyz.mdvcraft.mdvgraves.logoutbody.LogoutBodyManager;
import xyz.mdvcraft.mdvgraves.commands.Commands;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraveBackTest {
    private MDVGravesPlugin plugin;
    private YamlConfiguration config;
    private Player player;
    private PlayerInventory inventory;
    private World world;
    private BukkitScheduler scheduler;
    private MockedStatic<Bukkit> bukkit;
    private final UUID owner = UUID.randomUUID();
    private final ItemStack[] contents = new ItemStack[41];
    private final Set<ItemStack> fruits = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, CompletableFuture<Chunk>> loads = new LinkedHashMap<>();
    private final Map<String, Chunk> chunks = new HashMap<>();
    private final Set<String> loaded = new HashSet<>();
    private final Deque<Runnable> ticks = new ArrayDeque<>();
    private final List<Runnable> timeouts = new ArrayList<>();
    private boolean teleportSucceeds = true;
    private Location teleportRedirect;
    private Location current;
    private Location destination;

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = MDVGravesPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach void setup() throws Exception {
        plugin = mock(MDVGravesPlugin.class, CALLS_REAL_METHODS);
        config = new YamlConfiguration();
        config.set("utilities.back-grave.sound", "");
        config.set("utilities.death-fruit.sound", "");
        doReturn(config).when(plugin).getConfig();
        doReturn(true).when(plugin).isEnabled();
        for (String name : List.of("graves", "gravesByBlock", "graveBackCooldowns", "deathFruitUseLocks",
                "pendingDeathFruitUses", "activeGraveBacks", "graveBackChunkTickets", "graveBackTransportLocks"))
            field(plugin, name, new HashMap<>());
        field(plugin, "mmoItemsBridgeReady", true);
        field(plugin, "deathFruitExpectedType", "CONSUMABLE");
        field(plugin, "deathFruitExpectedId", "FRUTA_DE_LA_MUERTE");
        field(plugin, "nbtItemGetMethod", FruitNbt.class.getMethod("get", ItemStack.class));
        field(plugin, "nbtItemGetStringMethod", FruitNbt.class.getMethod("getString", String.class));

        world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call ->
                loaded.contains(call.getArgument(0) + ":" + call.getArgument(1)));
        when(world.getChunkAtAsync(anyInt(), anyInt(), eq(false))).thenAnswer(call ->
                loads.computeIfAbsent(call.getArgument(0) + ":" + call.getArgument(1),
                        ignored -> new CompletableFuture<>()));
        Material floor = mock(Material.class);
        when(floor.isSolid()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Block block = mock(Block.class);
            when(block.isPassable()).thenReturn(call.<Integer>getArgument(1) > 64);
            when(block.getType()).thenReturn(floor);
            return block;
        });
        current = new Location(world, 0, 70, 0);
        inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenAnswer(ignored -> contents.clone());
        when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventory.getItem(anyInt())).thenAnswer(call -> contents[call.getArgument(0)]);
        when(inventory.getItemInMainHand()).thenAnswer(ignored -> contents[0]);
        when(inventory.getItemInOffHand()).thenAnswer(ignored -> contents[40]);
        doAnswer(call -> {
            contents[call.getArgument(0)] = call.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> {
            ItemStack refund = call.getArgument(0);
            int old = contents[0] == null ? 0 : contents[0].getAmount();
            contents[0] = fruit(old + refund.getAmount());
            return new HashMap<Integer, ItemStack>();
        });
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getName()).thenReturn("TestPlayer");
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("mdvgraves.back")).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getLocation()).thenAnswer(ignored -> current.clone());
        when(player.getWorld()).thenAnswer(ignored -> current.getWorld());
        when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.COMMAND)))
                .thenAnswer(call -> {
                    destination = call.getArgument(0);
                    if (teleportSucceeds)
                        current = teleportRedirect == null ? destination : teleportRedirect;
                    return teleportSucceeds;
                });

        scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            ticks.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(call -> {
            timeouts.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player);
        bukkit.when(() -> Bukkit.getPlayerExact("TestPlayer")).thenReturn(player);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        addGrave();
    }

    @AfterEach void close() { bukkit.close(); }

    @SuppressWarnings("unchecked")
    private void addGrave() throws Exception {
        Class<?> meta = Class.forName(MDVGravesPlugin.class.getName() + "$GraveMeta");
        Constructor<?> ctor = meta.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        UUID id = UUID.randomUUID();
        Field field = MDVGravesPlugin.class.getDeclaredField("graves");
        field.setAccessible(true);
        ((Map<UUID, Object>) field.get(plugin)).put(id,
                ctor.newInstance(id, owner, "TestPlayer", "world", 15, 64, 15, 1L, null, Long.MAX_VALUE, false));
    }

    private ItemStack fruit(int amount) {
        ItemStack stack = mock(ItemStack.class);
        fruits.add(stack);
        int[] count = {amount};
        when(stack.getType()).thenReturn(mock(Material.class));
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getAmount()).thenAnswer(ignored -> count[0]);
        doAnswer(call -> { count[0] = call.getArgument(0); return null; }).when(stack).setAmount(anyInt());
        when(stack.clone()).thenAnswer(ignored -> fruit(count[0]));
        when(stack.isSimilar(any(ItemStack.class))).thenAnswer(call -> fruits.contains(call.getArgument(0)));
        return stack;
    }

    public static final class FruitNbt {
        public static FruitNbt get(ItemStack item) { return new FruitNbt(); }
        public String getString(String key) {
            return key.equals("MMOITEMS_ITEM_TYPE") ? "CONSUMABLE" : "FRUTA_DE_LA_MUERTE";
        }
    }

    private void useFruit(EquipmentSlot hand) {
        PlayerInteractEvent click = mock(PlayerInteractEvent.class);
        when(click.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(click.getHand()).thenReturn(hand);
        when(click.getPlayer()).thenReturn(player);
        when(click.getItem()).thenReturn(contents[hand == EquipmentSlot.OFF_HAND ? 40 : 0]);
        plugin.onDeathFruitUseStart(click);
        plugin.executeDeathFruit(mock(ConsoleCommandSender.class), new String[]{"deathfruit", "TestPlayer"});
    }

    private void tick() { assertFalse(ticks.isEmpty()); ticks.removeFirst().run(); }

    private void completeChunks() {
        for (String key : new ArrayList<>(loads.keySet())) {
            String[] coords = key.split(":");
            Chunk chunk = mock(Chunk.class);
            when(chunk.getX()).thenReturn(Integer.parseInt(coords[0]));
            when(chunk.getZ()).thenReturn(Integer.parseInt(coords[1]));
            loaded.add(key);
            chunks.put(key, chunk);
            loads.get(key).complete(chunk);
        }
    }

    @Test void waitsForNextTickAndAllSearchChunksThenUsesOneTeleportToAlreadyLoadedDestination() {
        AtomicReference<Boolean> result = new AtomicReference<>();
        assertTrue(plugin.executeGraveBack(player, false, false, false, false, result::set));
        assertTrue(loads.isEmpty());
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        tick();
        assertEquals(16, loads.size());
        assertTrue(loads.keySet().containsAll(Set.of("0:0", "0:1", "1:0", "1:1")));
        assertNull(result.get());
        completeChunks();
        verify(player, times(1)).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.COMMAND));
        verify(player, never()).teleportAsync(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        assertEquals(Boolean.TRUE, result.get());
        for (Chunk chunk : chunks.values())
            verify(chunk).removePluginChunkTicket(plugin);
    }

    @Test void duplicateCommandsCannotStartAnotherPendingTravel() {
        AtomicReference<Boolean> rejected = new AtomicReference<>();
        plugin.executeGraveBack(player);
        assertFalse(plugin.executeGraveBack(player, true, true, true, false, rejected::set));
        assertEquals(Boolean.FALSE, rejected.get());
        assertEquals(1, ticks.size());
        tick();
        completeChunks();
        verify(player, times(1)).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test void mmoItemsConsumptionAfterConsoleCommandIsNotChargedTwice() {
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        assertEquals(3, contents[0].getAmount());
        contents[0] = fruit(2); // MMOItems termina su evento después de ejecutar el comando.
        tick();
        verify(inventory, never()).setItem(anyInt(), nullable(ItemStack.class));
        completeChunks();
        assertEquals(2, contents[0].getAmount());
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test void reservationRefundsExactlyOnceWhenTeleportIsCancelled() {
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        tick();
        assertEquals(2, contents[0].getAmount());
        teleportSucceeds = false;
        completeChunks();
        assertEquals(3, contents[0].getAmount());
        timeouts.forEach(Runnable::run);
        verify(inventory, times(1)).addItem(any(ItemStack.class));
    }

    @Test void movingTheStackBeforeTheNextTickIsNotMistakenForMmoItemsConsumption() {
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        contents[5] = contents[0];
        contents[0] = null;
        tick();
        assertEquals(2, contents[5].getAmount());
        completeChunks();
        assertEquals(2, contents[5].getAmount());
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test void offhandMmoItemsConsumptionIsDetectedWithoutChargingMainHand() {
        contents[40] = fruit(2);
        useFruit(EquipmentSlot.OFF_HAND);
        contents[40] = fruit(1);
        tick();
        verify(inventory, never()).setItem(anyInt(), nullable(ItemStack.class));
        completeChunks();
        assertEquals(1, contents[40].getAmount());
    }

    @Test void quittingDuringChunkLoadingRefundsAndDiscardsLateCompletions() {
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        tick();
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        plugin.onPlayerQuit(quit);
        assertEquals(3, contents[0].getAmount());
        completeChunks();
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        timeouts.forEach(Runnable::run);
        verify(inventory, times(1)).addItem(any(ItemStack.class));
    }

    @Test void pendingRespawnDoesNotConsumeFruitOrStartAWorldLoad() throws Exception {
        LogoutBodyManager manager = mock(LogoutBodyManager.class);
        when(manager.isPendingRespawn(owner)).thenReturn(true);
        field(plugin, "logoutBodyManager", manager);
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        tick();
        assertEquals(3, contents[0].getAmount());
        assertTrue(loads.isEmpty());
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test void redirectToLobbyIsFailureAndRefundsEvenIfTeleportReturnsTrue() {
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        tick();
        teleportRedirect = new Location(mock(World.class), 0, 80, 0);
        completeChunks();
        assertEquals(3, contents[0].getAmount());
        verify(inventory, times(1)).addItem(any(ItemStack.class));
    }

    @Test void failedChunkLoadRefundsFruitAndTimeoutCannotRefundItAgain() {
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        tick();
        for (CompletableFuture<Chunk> load : loads.values())
            load.completeExceptionally(new IllegalStateException("failed load"));
        assertEquals(3, contents[0].getAmount());
        timeouts.forEach(Runnable::run);
        verify(inventory, times(1)).addItem(any(ItemStack.class));
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test void noGraveRefundsMmoItemsFruitConsumedAfterTheCommand() throws Exception {
        field(plugin, "graves", new HashMap<>());
        contents[0] = fruit(1);
        useFruit(EquipmentSlot.HAND);
        contents[0] = null;
        tick();
        assertEquals(1, contents[0].getAmount());
        assertTrue(loads.isEmpty());
    }

    @Test void repeatedFruitClickIsDeniedWhileLoadingOrImmediatelyAfterArrival() {
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        tick();
        PlayerInteractEvent extra = mock(PlayerInteractEvent.class);
        when(extra.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(extra.getHand()).thenReturn(EquipmentSlot.HAND);
        when(extra.getPlayer()).thenReturn(player);
        when(extra.getItem()).thenReturn(contents[0]);
        plugin.onDeathFruitUseStart(extra);
        verify(extra).setCancelled(true);
        verify(extra).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        clearInvocations(extra);
        completeChunks();
        plugin.onDeathFruitUseStart(extra);
        verify(extra).setCancelled(true);
        assertEquals(2, contents[0].getAmount());
    }

    @Test void deathDuringLoadingReturnsReservedFruitToDeathDrops() {
        config.set("settings.enabled", false);
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        tick();
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        List<ItemStack> drops = new ArrayList<>();
        when(death.getEntity()).thenReturn(player);
        when(death.getDrops()).thenReturn(drops);
        plugin.onDeath(death);
        assertEquals(1, drops.size());
        assertEquals(1, drops.getFirst().getAmount());
        verify(inventory, never()).addItem(any(ItemStack.class));
        completeChunks();
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        timeouts.forEach(Runnable::run);
        assertEquals(1, drops.size());
    }

    @Test void keepInventoryDeathRefundsIntoInventoryInsteadOfDeathDrops() {
        config.set("settings.enabled", false);
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        tick();
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        when(death.getKeepInventory()).thenReturn(true);
        plugin.onDeath(death);
        assertEquals(3, contents[0].getAmount());
        verify(death, never()).getDrops();
        verify(inventory, times(1)).addItem(any(ItemStack.class));
    }

    @Test void timeoutRefundsDuringLoadingAndCannotTeleportAfterChunksEventuallyArrive() {
        contents[0] = fruit(3);
        useFruit(EquipmentSlot.HAND);
        tick();
        timeouts.forEach(Runnable::run);
        completeChunks();
        assertEquals(3, contents[0].getAmount());
        verify(inventory, times(1)).addItem(any(ItemStack.class));
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test void cooldownStartsOnlyAfterArrivalAndRejectsTheNextCommand() throws Exception {
        plugin.executeGraveBack(player);
        tick();
        completeChunks();
        field(plugin, "graveBackTransportLocks", new HashMap<>()); // Simula fin del lock breve de transporte.
        AtomicReference<Boolean> result = new AtomicReference<>();
        plugin.executeGraveBack(player, false, false, false, false, result::set);
        tick();
        assertEquals(Boolean.FALSE, result.get());
        verify(player, times(1)).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test void pendingEvaluationBlocksExternalInventoryChangesOnlyUntilReservation() {
        contents[0] = fruit(1);
        useFruit(EquipmentSlot.HAND);
        PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
        when(drop.getPlayer()).thenReturn(player);
        PlayerSwapHandItemsEvent swap = mock(PlayerSwapHandItemsEvent.class);
        when(swap.getPlayer()).thenReturn(player);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getWhoClicked()).thenReturn(player);
        plugin.onPendingDeathFruitDrop(drop);
        plugin.onPendingDeathFruitSwap(swap);
        plugin.onPendingDeathFruitInventoryClick(click);
        plugin.onPendingDeathFruitInventoryDrag(drag);
        verify(drop).setCancelled(true);
        verify(swap).setCancelled(true);
        verify(click).setCancelled(true);
        verify(drag).setCancelled(true);
        clearInvocations(drop, swap, click, drag);
        tick();
        assertNull(contents[0]); // La única fruta ya está reservada por el plugin.
        plugin.onPendingDeathFruitDrop(drop);
        plugin.onPendingDeathFruitSwap(swap);
        plugin.onPendingDeathFruitInventoryClick(click);
        plugin.onPendingDeathFruitInventoryDrag(drag);
        verify(drop, never()).setCancelled(anyBoolean());
        verify(swap, never()).setCancelled(anyBoolean());
        verify(click, never()).setCancelled(anyBoolean());
        verify(drag, never()).setCancelled(anyBoolean());
    }

    @Test void legacyConsoleGraveBackCannotTeleportRepeatedlyInsideTheTransportLock() throws Exception {
        when(player.hasPermission("mdvgraves.back")).thenReturn(false);
        field(plugin, "graveBackCooldowns", new HashMap<>(Map.of(owner, System.currentTimeMillis() + 30_000L)));
        Commands commands = new Commands(plugin);
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("graveback");
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        assertTrue(commands.onCommand(console, command, "graveback", new String[]{"TestPlayer"}));
        tick();
        completeChunks();
        for (int packet = 0; packet < 10; packet++)
            assertTrue(commands.onCommand(console, command, "graveback", new String[]{"TestPlayer"}));
        assertTrue(ticks.isEmpty());
        verify(player, times(1)).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));

        contents[0] = fruit(3);
        PlayerInteractEvent click = mock(PlayerInteractEvent.class);
        when(click.getPlayer()).thenReturn(player);
        when(click.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(click.getHand()).thenReturn(EquipmentSlot.HAND);
        when(click.getItem()).thenReturn(contents[0]);
        plugin.onDeathFruitUseStart(click);
        verify(click).setCancelled(true); // La ruta legacy también evita que MMOItems descuente otro clic.

        field(plugin, "graveBackTransportLocks", new HashMap<>()); // Simula que ya transcurrieron 750 ms.
        assertTrue(commands.onCommand(console, command, "graveback", new String[]{"TestPlayer"}));
        tick();
        verify(player, times(2)).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        // La consola sigue saltando permisos y los 30 segundos de cooldown de gameplay.
    }

    @Test void pickupIsBlockedOnlyUntilMmoItemsConsumptionHasBeenEvaluated() {
        contents[0] = fruit(1);
        useFruit(EquipmentSlot.HAND);
        contents[0] = null; // MMOItems ya descontó la fruta; un pickup igual ocultaría ese descuento.
        EntityPickupItemEvent pickup = mock(EntityPickupItemEvent.class);
        when(pickup.getEntity()).thenReturn(player);
        plugin.onPendingDeathFruitPickup(pickup);
        verify(pickup).setCancelled(true);
        tick();
        verify(inventory, never()).setItem(anyInt(), nullable(ItemStack.class));
        clearInvocations(pickup);
        plugin.onPendingDeathFruitPickup(pickup);
        verify(pickup, never()).setCancelled(anyBoolean());
        completeChunks();
        verify(inventory, never()).addItem(any(ItemStack.class));
    }
}
