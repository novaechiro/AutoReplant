package com.novaechiro.autoreplant;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public class AutoReplant extends JavaPlugin implements Listener {

    private final Map<UUID, Long> activeUntil = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Set<ReplantData> replantQueue = new HashSet<>();

    private int delayTicks;
    private int replantsPerTick;
    private int activeSeconds;
    private int cooldownSeconds;
    private boolean ignoreCooldown;
    private boolean requireHoe;
    private int maxQueueSize;

    private FileConfiguration lang;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("lang.yml", false);

        loadConfigs();

        activeUntil.clear();
        cooldownUntil.clear();
        replantQueue.clear();

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {

            if (replantQueue.isEmpty()) return;

            Iterator<ReplantData> iterator = replantQueue.iterator();
            int processed = 0;

            while (iterator.hasNext() && processed < replantsPerTick) {
                ReplantData data = iterator.next();

                if (System.currentTimeMillis() >= data.executeAt) {

                    Block block = data.block;

                    if (block.getType() == Material.AIR) {
                        block.setType(data.cropType);

                        if (block.getBlockData() instanceof Ageable ageable) {
                            ageable.setAge(0);
                            block.setBlockData(ageable);
                        }
                    }

                    iterator.remove();
                    processed++;
                }
            }

        }, 1L, 1L);
    }

    @Override
    public void onDisable() {
        activeUntil.clear();
        cooldownUntil.clear();
        replantQueue.clear();
    }

    private void loadConfigs() {
        reloadConfig();

        delayTicks = getConfig().getInt("replant-delay-ticks", 2);
        replantsPerTick = getConfig().getInt("replants-per-tick", 100);
        activeSeconds = getConfig().getInt("active-seconds", 10);
        cooldownSeconds = getConfig().getInt("cooldown-seconds", 30);
        ignoreCooldown = getConfig().getBoolean("ignorecooldown", false);
        requireHoe = getConfig().getBoolean("require-hoe", true);
        maxQueueSize = getConfig().getInt("max-queue-size", 5000);

        lang = YamlConfiguration.loadConfiguration(
                new File(getDataFolder(), "lang.yml")
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // /autoreplant reload
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {

            if (!sender.hasPermission("autoreplant.reload")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }

            loadConfigs();

            // Clear runtime data to prevent exploit
            activeUntil.clear();
            cooldownUntil.clear();
            replantQueue.clear();

            sender.sendMessage(msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("only-player"));
            return true;
        }

        if (!player.hasPermission("autoreplant.use")) {
            player.sendMessage(msg("no-permission"));
            return true;
        }

        UUID uuid = player.getUniqueId();

        if (ignoreCooldown) {
            if (activeUntil.containsKey(uuid)) {
                activeUntil.remove(uuid);
                player.sendMessage(msg("disabled"));
            } else {
                activeUntil.put(uuid, Long.MAX_VALUE);
                player.sendMessage(msg("enabled"));
            }
            return true;
        }

        long now = System.currentTimeMillis();

        if (cooldownUntil.containsKey(uuid)) {
            long cooldownExpire = cooldownUntil.get(uuid);

            if (now < cooldownExpire) {
                long remaining = (cooldownExpire - now) / 1000;
                player.sendMessage(msg("cooldown", "{seconds}", String.valueOf(remaining)));
                return true;
            }
        }

        long activeExpire = now + (activeSeconds * 1000L);
        activeUntil.put(uuid, activeExpire);

        player.sendMessage(msg("enabled-timed", "{seconds}", String.valueOf(activeSeconds)));
        return true;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {

        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!player.hasPermission("autoreplant.use")) return;
        if (!activeUntil.containsKey(uuid)) return;

        long now = System.currentTimeMillis();
        long activeExpire = activeUntil.get(uuid);

        if (!ignoreCooldown && now > activeExpire) {
            activeUntil.remove(uuid);

            long cooldownExpire = now + (cooldownSeconds * 1000L);
            cooldownUntil.put(uuid, cooldownExpire);

            player.sendMessage(msg("expired"));
            return;
        }

        if (requireHoe) {
            Material tool = player.getInventory().getItemInMainHand().getType();
            if (!tool.name().endsWith("_HOE")) return;
        }

        if (replantQueue.size() >= maxQueueSize) {
            player.sendMessage(msg("queue-full"));
            return;
        }

        Block block = event.getBlock();

        if (!(block.getBlockData() instanceof Ageable ageable)) return;
        if (ageable.getAge() < ageable.getMaximumAge()) return;

        Material cropType = block.getType();
        Material seedType = getSeedForCrop(cropType);

        if (seedType == null) return;
        if (!removeOneSeed(player, seedType)) return;

        long executeTime = now + (delayTicks * 50L);
        replantQueue.add(new ReplantData(block, cropType, executeTime));
    }

    private boolean removeOneSeed(Player player, Material seedType) {
        HashMap<Integer, ItemStack> removed =
                player.getInventory().removeItem(new ItemStack(seedType, 1));
        return removed.isEmpty();
    }

    private Material getSeedForCrop(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case COCOA -> Material.COCOA_BEANS;
            case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
    }

    private String msg(String path) {
        String message = lang.getString(path, "&cMissing lang: " + path);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private String msg(String path, String placeholder, String value) {
        String message = lang.getString(path, "&cMissing lang: " + path);
        message = message.replace(placeholder, value);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private static class ReplantData {
        private final Block block;
        private final Material cropType;
        private final long executeAt;

        private ReplantData(Block block, Material cropType, long executeAt) {
            this.block = block;
            this.cropType = cropType;
            this.executeAt = executeAt;
        }
    }
}
