
package pl.prepsmp.buldakai;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class BuldakInventory implements Listener {

    private static final String TITLE = "Ekwipunek Buldaka";

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    private final ItemStack[] items = new ItemStack[36];
    // Kolejność Bukkit: buty, spodnie, napierśnik, hełm
    private final ItemStack[] armor = new ItemStack[4];
    private ItemStack offhand;

    public BuldakInventory(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "inventory.yml");
        this.data = YamlConfiguration.loadConfiguration(file);

        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, TITLE);

        // Zbroja: buty, spodnie, napierśnik, hełm
        gui.setItem(0, display(armor[0], Material.IRON_BOOTS, "§fButy"));
        gui.setItem(1, display(armor[1], Material.IRON_LEGGINGS, "§fSpodnie"));
        gui.setItem(2, display(armor[2], Material.IRON_CHESTPLATE, "§fNapierśnik"));
        gui.setItem(3, display(armor[3], Material.IRON_HELMET, "§fHełm"));
        gui.setItem(4, display(offhand, Material.SHIELD, "§fDruga ręka"));
        gui.setItem(5, named(Material.PLAYER_HEAD, "§6Buldak"));

        // Główne 27 slotów: GUI 9–35
        for (int i = 9; i < 36; i++) {
            gui.setItem(i, display(items[i - 9],
                    Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                    "§7Slot ekwipunku"));
        }

        // Hotbar: GUI 36–44
        for (int i = 0; i < 9; i++) {
            gui.setItem(36 + i, display(items[27 + i],
                    Material.GRAY_STAINED_GLASS_PANE,
                    "§7Hotbar"));
        }

        player.openInventory(gui);
    }

    private ItemStack display(ItemStack content, Material placeholder,
                              String name) {
        if (content != null && content.getType() != Material.AIR) {
            return content.clone();
        }
        return named(placeholder, name);
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private int logicalSlot(int guiSlot) {
        if (guiSlot >= 9 && guiSlot <= 35) {
            return guiSlot - 9;
        }
        if (guiSlot >= 36 && guiSlot <= 44) {
            return 27 + guiSlot - 36;
        }
        if (guiSlot >= 0 && guiSlot <= 3) {
            return 36 + guiSlot;
        }
        if (guiSlot == 4) {
            return 40;
        }
        return -1;
    }

    private ItemStack getSlot(int id) {
        if (id < 36) return items[id];
        if (id < 40) return armor[id - 36];
        if (id == 40) return offhand;
        return null;
    }

    private void setSlot(int id, ItemStack item) {
        ItemStack copy = item == null || item.getType() == Material.AIR
                ? null : item.clone();

        if (id < 36) {
            items[id] = copy;
        } else if (id < 40) {
            armor[id - 36] = copy;
        } else if (id == 40) {
            offhand = copy;
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(TITLE)) return;

        // Nie pozwalaj na domyślne przenoszenie między ekwipunkiem gracza i GUI.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        int id = logicalSlot(rawSlot);
        if (id < 0) return;

        // Zamiana przedmiotu w slocie z przedmiotem trzymanym kursorem.
        ItemStack cursor = event.getCursor();
        ItemStack current = getSlot(id);

        setSlot(id, cursor);
        event.setCursor(current == null ? null : current.clone());

        refresh(player);
        save();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().equals(TITLE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(TITLE)) {
            save();
        }
    }

    private void refresh(Player player) {
        Inventory gui = player.getOpenInventory().getTopInventory();

        gui.setItem(0, display(armor[0], Material.IRON_BOOTS, "§fButy"));
        gui.setItem(1, display(armor[1], Material.IRON_LEGGINGS, "§fSpodnie"));
        gui.setItem(2, display(armor[2], Material.IRON_CHESTPLATE, "§fNapierśnik"));
        gui.setItem(3, display(armor[3], Material.IRON_HELMET, "§fHełm"));
        gui.setItem(4, display(offhand, Material.SHIELD, "§fDruga ręka"));

        for (int i = 0; i < 27; i++) {
            gui.setItem(9 + i, display(items[i],
                    Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7Slot ekwipunku"));
        }

        for (int i = 0; i < 9; i++) {
            gui.setItem(36 + i, display(items[27 + i],
                    Material.GRAY_STAINED_GLASS_PANE, "§7Hotbar"));
        }
    }

    public void load() {
        for (int i = 0; i < items.length; i++) {
            items[i] = data.getItemStack("items." + i);
        }
        for (int i = 0; i < armor.length; i++) {
            armor[i] = data.getItemStack("armor." + i);
        }
        offhand = data.getItemStack("offhand");
    }

    public void save() {
        for (int i = 0; i < items.length; i++) {
            data.set("items." + i, items[i]);
        }
        for (int i = 0; i < armor.length; i++) {
            data.set("armor." + i, armor[i]);
        }
        data.set("offhand", offhand);

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe(
                    "Nie udalo sie zapisac inventory.yml: " + e.getMessage());
        }
    }
        }
    
