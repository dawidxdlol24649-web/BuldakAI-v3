package pl.prepsmp.buldakai;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public class BuldakInventory {

    private final JavaPlugin plugin;
    private final NamespacedKey key;

    private final ItemStack[] items = new ItemStack[36];
    private final ItemStack[] armor = new ItemStack[4];
    private ItemStack offhand;

    public BuldakInventory(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "buldak_slot");

        load();
    }

    public void open(Player player) {
        Inventory gui = Bukkit.createInventory(
            null, 54, "Ekwipunek Buldaka"
        );

        gui.setItem(4, named(Material.PLAYER_HEAD, "§6Buldak"));

        // Zbroja: hełm, klata, nogi, buty
        gui.setItem(10, slot(0, armor[3], Material.IRON_HELMET, "§fHełm"));
        gui.setItem(19, slot(1, armor[2], Material.IRON_CHESTPLATE, "§fNapierśnik"));
        gui.setItem(28, slot(2, armor[1], Material.IRON_LEGGINGS, "§fSpodnie"));
        gui.setItem(37, slot(3, armor[0], Material.IRON_BOOTS, "§fButy"));

        // Ręka pomocnicza
        gui.setItem(16, slot(4, offhand, Material.SHIELD, "§fDruga ręka"));

        // 27 głównych slotów
        for (int i = 0; i < 27; i++) {
            gui.setItem(18 + i, slot(5 + i, items[i + 9],
                Material.GRAY_STAINED_GLASS_PANE, "§7Slot " + (i + 9)));
        }

        // Hotbar
        for (int i = 0; i < 9; i++) {
            gui.setItem(45 + i, slot(5 + i, items[i],
                Material.GRAY_STAINED_GLASS_PANE, "§7Hotbar " + i));
        }

        player.openInventory(gui);
    }

    private ItemStack slot(int id, ItemStack content, Material placeholder,
                           String name) {
        ItemStack item = content == null
            ? new ItemStack(placeholder)
            : content.clone();

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (content == null) {
                meta.setDisplayName(name);
            }
            meta.getPersistentDataContainer().set(
                key, PersistentDataType.INTEGER, id
            );
            item.setItemMeta(meta);
        }
        return item;
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

    public void load() {
        // Tutaj później dodamy odczyt ekwipunku z pliku.
    }

    public void save() {
        // Tutaj później dodamy zapis ekwipunku do pliku.
    }
}
