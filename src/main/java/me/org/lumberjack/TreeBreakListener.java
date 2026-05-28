package me.org.lumberjack;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class TreeBreakListener implements Listener {

    private static final Set<Material> LOGS = EnumSet.of(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
            Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG,
            Material.MANGROVE_LOG, Material.CHERRY_LOG, Material.PALE_OAK_LOG
    );

    private static final int MAX_TREE_SIZE = 256;

    private final Set<UUID> allowed = new HashSet<>();
    private final Set<UUID> enabled = new HashSet<>();

    private final Plugin plugin;
    private final File dataFile;
    private final Object saveLock = new Object();

    public TreeBreakListener() {
        plugin = Lumberjack.getPlugin(Lumberjack.class);
        File folder = plugin.getDataFolder();
        folder.mkdirs();
        dataFile = new File(folder, "players.yml");
        loadAllData();
    }

    private void loadAllData() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : data.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                if (data.getBoolean(key + ".allowed", false)) {
                    allowed.add(id);
                }
                if (data.getBoolean(key + ".enabled", false)) {
                    enabled.add(id);
                }
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /* ==================== TOGGLE ==================== */

    @EventHandler
    public void onToggle(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && 
            event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        if (!isAxe(player.getInventory().getItemInMainHand().getType())) return;

        UUID id = player.getUniqueId();

        if (!allowed.contains(id)) {
            player.sendActionBar(Component.text("§cLumberjack disabled"));
            return;
        }

        if (enabled.remove(id)) {
            player.sendActionBar(Component.text("§cLumberjack OFF"));
        } else {
            enabled.add(id);
            player.sendActionBar(Component.text("§aLumberjack ON"));
        }

        savePlayer(id);
        event.setCancelled(true);
    }

    /* ==================== PERSISTENCE ==================== */

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        synchronized (saveLock) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            String key = id.toString();
            if (data.getBoolean(key + ".allowed", false)) {
                allowed.add(id);
            }
            if (data.getBoolean(key + ".enabled", false)) {
                enabled.add(id);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        savePlayer(id);
        allowed.remove(id);
        enabled.remove(id);
    }

    private void savePlayer(UUID id) {
        synchronized (saveLock) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
            String key = id.toString();
            data.set(key + ".allowed", allowed.contains(id));
            data.set(key + ".enabled", enabled.contains(id));
            try {
                data.save(dataFile);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save player data: " + e.getMessage());
            }
        }
    }

    /* ==================== BLOCK BREAK ==================== */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (!allowed.contains(id)) return;
        if (!enabled.contains(id)) return;
        if (!player.isSneaking()) return;

        Block block = event.getBlock();
        if (!LOGS.contains(block.getType())) return;
        if (!isAxe(player.getInventory().getItemInMainHand().getType())) return;

        // Check if this log has adjacent support logs (horizontal neighbors)
        if (hasAdjacentSupport(block)) {
            spawnSmokeEffect(block);
            // After this block breaks, check if any adjacent logs become unsupported
            scheduleUnsupportedCheck(player, block);
            return; // Let vanilla break happen
        }

        // This log has no support - trigger tree felling
        event.setCancelled(true);
        
        List<Block> toBreak = collectTree(block);
        fellTree(player, toBreak);
    }

    /**
     * After a support log breaks, check adjacent logs on next tick.
     * If any are now unsupported, trigger felling for them.
     */
    private void scheduleUnsupportedCheck(Player player, Block brokenBlock) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                if (!isAxe(player.getInventory().getItemInMainHand().getType())) return;

                // Check all adjacent logs (including vertical) for newly unsupported ones
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        for (int z = -1; z <= 1; z++) {
                            if (x == 0 && y == 0 && z == 0) continue;
                            Block adjacent = brokenBlock.getRelative(x, y, z);
                            if (LOGS.contains(adjacent.getType()) && !hasAdjacentSupport(adjacent)) {
                                // This log is now unsupported - trigger felling
                                List<Block> toBreak = collectTree(adjacent);
                                fellTree(player, toBreak);
                                return; // Only trigger one felling per break
                            }
                        }
                    }
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    /* ==================== TREE COLLECTION ==================== */

    private List<Block> collectTree(Block origin) {
        List<Block> result = new ArrayList<>();
        Deque<Block> queue = new ArrayDeque<>();
        Set<Block> visited = new HashSet<>();

        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty() && result.size() < MAX_TREE_SIZE) {
            Block current = queue.poll();
            result.add(current);

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        Block rel = current.getRelative(x, y, z);
                        if (visited.add(rel) && LOGS.contains(rel.getType())) {
                            queue.add(rel);
                        }
                    }
                }
            }
        }

        return result;
    }

    /* ==================== TREE FELLING ==================== */

    private void fellTree(Player player, List<Block> blocks) {
        Iterator<Block> iterator = blocks.iterator();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!iterator.hasNext()) {
                    cancel();
                    return;
                }

                Block current = iterator.next();

                // Skip if block was already broken
                if (!LOGS.contains(current.getType())) {
                    return; // Continue to next block on next tick
                }

                spawnSmokeEffect(current);
                
                // Break with player's tool if online, otherwise just break naturally
                if (player.isOnline() && isAxe(player.getInventory().getItemInMainHand().getType())) {
                    current.breakNaturally(player.getInventory().getItemInMainHand());
                    player.damageItemStack(EquipmentSlot.HAND, 1);
                } else {
                    current.breakNaturally();
                }
            }
        }.runTaskTimer(plugin, 1L, 4L); // 1 tick delay to prevent jitter, then 4 ticks between blocks
    }

    /* ==================== EFFECTS ==================== */

    private void spawnSmokeEffect(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);

        block.getWorld().spawnParticle(
                Particle.CAMPFIRE_COSY_SMOKE, center,
                6, 0.3, 0.1, 0.3, 0.01
        );

        block.getWorld().spawnParticle(
                Particle.SMOKE, center,
                10, 0.4, 0.4, 0.4, 0.03
        );
    }

    /* ==================== HELPERS ==================== */

    private boolean hasAdjacentSupport(Block origin) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                if (LOGS.contains(origin.getRelative(x, 0, z).getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAxe(Material mat) {
        return mat.name().endsWith("_AXE");
    }

    /* ==================== COMMAND HOOKS ==================== */

    public void allow(Player player) {
        allowed.add(player.getUniqueId());
        savePlayer(player.getUniqueId());
    }

    public void disallow(Player player) {
        UUID id = player.getUniqueId();
        allowed.remove(id);
        enabled.remove(id);
        savePlayer(id);
    }
}
