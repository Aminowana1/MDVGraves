package xyz.mdvcraft.mdvgraves.logoutbody;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class PlayerInventorySnapshot {
    private final ItemStack[] storage;
    private final ItemStack[] armor;
    private final ItemStack offHand;
    private final int heldSlot;

    public PlayerInventorySnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack offHand, int heldSlot) {
        this.storage = cloneArray(storage);
        this.armor = cloneArray(armor);
        this.offHand = cloneItem(offHand);
        this.heldSlot = Math.max(0, Math.min(8, heldSlot));
    }

    public static PlayerInventorySnapshot capture(Player player) {
        PlayerInventory inventory = player.getInventory();
        return new PlayerInventorySnapshot(
                inventory.getStorageContents(),
                inventory.getArmorContents(),
                inventory.getItemInOffHand(),
                inventory.getHeldItemSlot());
    }

    public PlayerInventorySnapshot filtered(Predicate<ItemStack> keepPredicate) {
        ItemStack[] keptStorage = filterArray(storage, keepPredicate);
        ItemStack[] keptArmor = filterArray(armor, keepPredicate);
        ItemStack keptOffHand = isRealItem(offHand) && keepPredicate.test(offHand) ? offHand.clone() : null;
        return new PlayerInventorySnapshot(keptStorage, keptArmor, keptOffHand, heldSlot);
    }

    public List<ItemStack> itemsExcluding(Predicate<ItemStack> excludePredicate) {
        List<ItemStack> result = new ArrayList<>();
        addItems(result, storage, excludePredicate);
        addItems(result, armor, excludePredicate);
        if (isRealItem(offHand) && !excludePredicate.test(offHand))
            result.add(offHand.clone());
        return result;
    }

    public List<ItemStack> allItems() {
        List<ItemStack> result = new ArrayList<>();
        addItems(result, storage, ignored -> false);
        addItems(result, armor, ignored -> false);
        if (isRealItem(offHand))
            result.add(offHand.clone());
        return result;
    }

    public boolean isEmpty() {
        for (ItemStack item : storage)
            if (isRealItem(item))
                return false;
        for (ItemStack item : armor)
            if (isRealItem(item))
                return false;
        return !isRealItem(offHand);
    }

    public void restore(Player player) {
        PlayerInventory inventory = player.getInventory();
        clearPlayer(player);

        ItemStack[] targetStorage = new ItemStack[inventory.getStorageContents().length];
        for (int i = 0; i < Math.min(targetStorage.length, storage.length); i++)
            targetStorage[i] = cloneItem(storage[i]);
        inventory.setStorageContents(targetStorage);

        ItemStack[] targetArmor = new ItemStack[inventory.getArmorContents().length];
        for (int i = 0; i < Math.min(targetArmor.length, armor.length); i++)
            targetArmor[i] = cloneItem(armor[i]);
        inventory.setArmorContents(targetArmor);

        inventory.setItemInOffHand(isRealItem(offHand) ? offHand.clone() : new ItemStack(Material.AIR));
        inventory.setHeldItemSlot(heldSlot);
        player.updateInventory();
    }

    public static void clearPlayer(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
        inventory.setItemInOffHand(new ItemStack(Material.AIR));
        player.setItemOnCursor(new ItemStack(Material.AIR));
        player.updateInventory();
    }

    public ItemStack getMainHandVisual() {
        if (heldSlot < 0 || heldSlot >= storage.length)
            return null;
        return cloneItem(storage[heldSlot]);
    }

    public ItemStack getOffHandVisual() {
        return cloneItem(offHand);
    }

    public ItemStack[] getArmorVisual() {
        return cloneArray(armor);
    }

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(gzip)) {
            out.writeInt(storage.length);
            for (ItemStack item : storage)
                out.writeObject(item);
            out.writeInt(armor.length);
            for (ItemStack item : armor)
                out.writeObject(item);
            out.writeObject(offHand);
            out.writeInt(heldSlot);
        }
        return bytes.toByteArray();
    }

    public static PlayerInventorySnapshot deserialize(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0)
            return new PlayerInventorySnapshot(new ItemStack[36], new ItemStack[4], null, 0);
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(
                new GZIPInputStream(new ByteArrayInputStream(data)))) {
            int storageSize = in.readInt();
            ItemStack[] storage = new ItemStack[storageSize];
            for (int i = 0; i < storageSize; i++)
                storage[i] = (ItemStack) in.readObject();

            int armorSize = in.readInt();
            ItemStack[] armor = new ItemStack[armorSize];
            for (int i = 0; i < armorSize; i++)
                armor[i] = (ItemStack) in.readObject();

            ItemStack offHand = (ItemStack) in.readObject();
            int heldSlot = in.readInt();
            return new PlayerInventorySnapshot(storage, armor, offHand, heldSlot);
        }
    }

    private static ItemStack[] filterArray(ItemStack[] source, Predicate<ItemStack> keepPredicate) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            ItemStack item = source[i];
            if (isRealItem(item) && keepPredicate.test(item))
                result[i] = item.clone();
        }
        return result;
    }

    private static void addItems(List<ItemStack> output, ItemStack[] source, Predicate<ItemStack> excludePredicate) {
        for (ItemStack item : source) {
            if (!isRealItem(item) || excludePredicate.test(item))
                continue;
            output.add(item.clone());
        }
    }

    private static ItemStack[] cloneArray(ItemStack[] source) {
        if (source == null)
            return new ItemStack[0];
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++)
            result[i] = cloneItem(source[i]);
        return result;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return isRealItem(item) ? item.clone() : null;
    }

    private static boolean isRealItem(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }
}
