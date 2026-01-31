package com.winterwibe;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class WinterWibe extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private boolean isWinterEnabled = false;
    private final int DEFAULT_TICK_SPEED = 3;
    
    // --- DelSnow Variables ---
    private boolean isDelSnowEnabled = false;

    // --- Nether Fix Variables ---
    private boolean isNetherFixModeEnabled = false;
    private int fixedChunksSessionCount = 0;
    private BukkitTask netherLogTask;

    // --- Timer Variables ---
    private boolean isTimerEnabled = false;
    private BukkitTask timerTask;

    // --- AnotherBiome Tool Variables ---
    private final Map<UUID, Location[]> playerSelections = new HashMap<>();
    private final String TOOL_NAME = ChatColor.LIGHT_PURPLE + "AnotherBiome Tool";

    // Namespaced Keys for PDC
    private NamespacedKey ORIGINAL_BIOME_KEY;
    private NamespacedKey MODIFIED_FLAG_KEY;
    
    // Biomes used
    private final Biome WINTER_BIOME = Biome.SNOWY_PLAINS;

    @Override
    public void onEnable() {
        // Initialize PDC keys
        ORIGINAL_BIOME_KEY = new NamespacedKey(this, "original_biome");
        MODIFIED_FLAG_KEY = new NamespacedKey(this, "was_modified");

        saveDefaultConfig();
        
        // Register commands and tab completer
        if (getCommand("ww") != null) {
            getCommand("ww").setExecutor(this);
            getCommand("ww").setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("WinterWibe loaded. Nether Fix, Timer, DelSnow & BiomeTool systems ready.");
    }

    @Override
    public void onDisable() {
        disableWinter();
        stopNetherFixMode(Bukkit.getConsoleSender(), false);
        if (timerTask != null && !timerTask.isCancelled()) {
            timerTask.cancel();
        }
        isDelSnowEnabled = false;
        getLogger().info("WinterWibe disabled.");
    }

    // --- Command Handling ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /ww <true|false|ficks|timer|delsnow|anotherbiometool|anotherbiome>");
            return true;
        }

        String arg = args[0].toLowerCase();

        switch (arg) {
            case "true":
                if (isTimerEnabled) {
                    sender.sendMessage(ChatColor.RED + "Disable the timer first with /ww timer false");
                    return true;
                }
                if (isDelSnowEnabled) {
                    sender.sendMessage(ChatColor.RED + "Sorry, please disable DelSnow first with /ww delsnow false");
                    return true;
                }
                enableWinter();
                double speed = getConfig().getDouble("snow-speed", 1.0);
                sender.sendMessage(ChatColor.AQUA + "❄ WinterWibe enabled manually! Speed: " + speed);
                break;

            case "false":
                if (isTimerEnabled) {
                    sender.sendMessage(ChatColor.RED + "Disable the timer first with /ww timer false");
                    return true;
                }
                disableWinter();
                sender.sendMessage(ChatColor.GOLD + "☀ WinterWibe disabled manually.");
                break;

            case "ficks":
                handleFicksCommand(sender, args);
                break;

            case "timer":
                if (isDelSnowEnabled) {
                    sender.sendMessage(ChatColor.RED + "Sorry, please disable DelSnow first with /ww delsnow false");
                    return true;
                }
                handleTimerCommand(sender, args);
                break;
                
            case "delsnow":
                handleDelSnowCommand(sender, args);
                break;

            case "anotherbiometool":
                handleBiomeToolCommand(sender);
                break;

            case "anotherbiome":
                handleSetBiomeCommand(sender, args);
                break;

            default:
                sender.sendMessage(ChatColor.RED + "Unknown argument.");
                break;
        }

        return true;
    }

    // --- Tab Completer ---
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        
        if (args.length == 1) {
            suggestions.add("true");
            suggestions.add("false");
            suggestions.add("ficks");
            suggestions.add("timer");
            suggestions.add("delsnow");
            suggestions.add("anotherbiometool");
            suggestions.add("anotherbiome");
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("ficks") || args[0].equalsIgnoreCase("timer") || args[0].equalsIgnoreCase("delsnow")) {
                suggestions.add("true");
                suggestions.add("false");
            } else if (args[0].equalsIgnoreCase("anotherbiome")) {
                for (Biome biome : Biome.values()) {
                    suggestions.add(biome.name().toLowerCase());
                }
            }
        }
        
        return suggestions;
    }

    // --- AnotherBiome Tool Logic ---

    private void handleBiomeToolCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player.");
            return;
        }

        Player player = (Player) sender;
        ItemStack tool = new ItemStack(Material.STICK);
        ItemMeta meta = tool.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(TOOL_NAME);
            // FIXED: DURABILITY -> UNBREAKING for 1.21
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Left-Click to set Pos 1");
            lore.add(ChatColor.GRAY + "Right-Click to set Pos 2");
            meta.setLore(lore);
            tool.setItemMeta(meta);
        }

        player.getInventory().addItem(tool);
        player.sendMessage(ChatColor.LIGHT_PURPLE + "You received the AnotherBiome Tool!");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasDisplayName() || !meta.getDisplayName().equals(TOOL_NAME)) return;

        event.setCancelled(true); 

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return; 

        playerSelections.putIfAbsent(player.getUniqueId(), new Location[2]);
        Location[] selection = playerSelections.get(player.getUniqueId());

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            selection[0] = clickedBlock.getLocation();
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Position 1 set at " + formatLoc(selection[0]));
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            selection[1] = clickedBlock.getLocation();
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Position 2 set at " + formatLoc(selection[1]));
        }
    }

    private void handleSetBiomeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return;
        }
        
        Player player = (Player) sender;

        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /ww anotherbiome <BiomeName>");
            return;
        }

        Location[] selection = playerSelections.get(player.getUniqueId());
        if (selection == null || selection[0] == null || selection[1] == null) {
            player.sendMessage(ChatColor.RED + "You must select both Position 1 and Position 2 using the tool (/ww anotherbiometool).");
            return;
        }

        if (selection[0].getWorld() != selection[1].getWorld()) {
            player.sendMessage(ChatColor.RED + "Selection points must be in the same world!");
            return;
        }

        String biomeName = args[1].toUpperCase();
        Biome biome;
        try {
            biome = Biome.valueOf(biomeName);
        } catch (IllegalArgumentException e) {
            player.sendMessage(ChatColor.RED + "Invalid biome name: " + args[1]);
            return;
        }

        World world = selection[0].getWorld();
        int minX = Math.min(selection[0].getBlockX(), selection[1].getBlockX());
        int minY = Math.min(selection[0].getBlockY(), selection[1].getBlockY());
        int minZ = Math.min(selection[0].getBlockZ(), selection[1].getBlockZ());
        
        int maxX = Math.max(selection[0].getBlockX(), selection[1].getBlockX());
        int maxY = Math.max(selection[0].getBlockY(), selection[1].getBlockY());
        int maxZ = Math.max(selection[0].getBlockZ(), selection[1].getBlockZ());

        player.sendMessage(ChatColor.YELLOW + "Setting biome to " + biome.name() + "...");

        Set<Long> chunksToRefresh = new HashSet<>();

        for (int x = minX; x <= maxX; x += 4) { 
            for (int z = minZ; z <= maxZ; z += 4) {
                // FIXED: Manual bit shifting for chunk key
                long chunkKey = (long) (x >> 4) & 0xffffffffL | ((long) (z >> 4) & 0xffffffffL) << 32;
                chunksToRefresh.add(chunkKey);
                for (int y = minY; y <= maxY; y += 4) {
                    world.setBiome(x, y, z, biome);
                }
            }
        }

        for (Long chunkKey : chunksToRefresh) {
            int cx = (int) (chunkKey.longValue());
            int cz = (int) (chunkKey >> 32);
            world.refreshChunk(cx, cz);
        }

        player.sendMessage(ChatColor.GREEN + "Biome changed successfully!");
    }

    private String formatLoc(Location loc) {
        return String.format("[%d, %d, %d]", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    // --- DelSnow Logic ---

    private void handleDelSnowCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /ww delsnow <true|false>");
            return;
        }

        boolean enable = Boolean.parseBoolean(args[1]);

        if (enable) {
            if (isWinterEnabled || isTimerEnabled || isDelSnowEnabled) {
                sender.sendMessage(ChatColor.RED + "Conflicts detected (Winter/Timer/DelSnow is active).");
                return;
            }
            enableDelSnow(sender);
        } else {
            if (!isDelSnowEnabled) {
                sender.sendMessage(ChatColor.RED + "DelSnow is not running.");
                return;
            }
            isDelSnowEnabled = false;
            sender.sendMessage(ChatColor.YELLOW + "DelSnow mode DISABLED.");
        }
    }

    private void enableDelSnow(CommandSender sender) {
        isDelSnowEnabled = true;
        sender.sendMessage(ChatColor.GREEN + "DelSnow mode ENABLED.");
        sender.sendMessage(ChatColor.GRAY + "Removing all snow from non-snowy biomes...");
        
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    Bukkit.getScheduler().runTask(this, () -> cleanSnowInChunk(chunk));
                }
            }
        }
    }

    private void cleanSnowInChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int chunkX = chunk.getX() * 16;
        int chunkZ = chunk.getZ() * 16;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int globalX = chunkX + x;
                int globalZ = chunkZ + z;
                int highestY = world.getHighestBlockYAt(globalX, globalZ);

                for (int y = highestY + 1; y >= highestY - 5; y--) {
                    if (y < world.getMinHeight()) continue;
                    Block targetBlock = world.getBlockAt(globalX, y, globalZ);
                    if (isSnowBlock(targetBlock.getType()) && !isNaturalSnowBiome(world.getBiome(globalX, y, globalZ))) {
                        targetBlock.setType(Material.AIR);
                    }
                }
            }
        }
    }
    
    private boolean isSnowBlock(Material mat) {
        return mat == Material.SNOW || mat == Material.SNOW_BLOCK || mat == Material.POWDER_SNOW;
    }

    private boolean isNaturalSnowBiome(Biome biome) {
        return biome == Biome.SNOWY_PLAINS ||
               biome == Biome.ICE_SPIKES ||
               biome == Biome.SNOWY_TAIGA ||
               biome == Biome.SNOWY_BEACH ||
               biome == Biome.GROVE ||
               biome == Biome.SNOWY_SLOPES ||
               biome == Biome.JAGGED_PEAKS ||
               biome == Biome.FROZEN_PEAKS ||
               biome == Biome.FROZEN_RIVER ||
               biome == Biome.FROZEN_OCEAN ||
               biome == Biome.DEEP_FROZEN_OCEAN;
    }

    // --- Timer Logic ---

    private void handleTimerCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /ww timer <true|false>");
            return;
        }
        
        boolean enable = Boolean.parseBoolean(args[1]);
        
        if (enable) {
            if (isTimerEnabled || isDelSnowEnabled) {
                sender.sendMessage(ChatColor.RED + "Timer already enabled or DelSnow active.");
                return;
            }
            startWinterTimer();
            sender.sendMessage(ChatColor.GREEN + "Winter Timer STARTED.");
        } else {
            if (!isTimerEnabled) {
                sender.sendMessage(ChatColor.RED + "Timer is not running.");
                return;
            }
            stopWinterTimer();
            sender.sendMessage(ChatColor.RED + "Timer STOPPED.");
        }
    }

    private void startWinterTimer() {
        isTimerEnabled = true;
        long pauseTicks = getConfig().getInt("timer.interval-minutes", 60) * 1200L; 
        long durationTicks = getConfig().getInt("timer.duration-minutes", 10) * 1200L; 
        long totalCycleTicks = pauseTicks + durationTicks; 

        timerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            getLogger().info("Timer: Starting winter storm...");
            enableWinter();
            Bukkit.broadcastMessage(ChatColor.AQUA + "❄ A winter storm has started!");

            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (isWinterEnabled && isTimerEnabled) {
                    disableWinter();
                    Bukkit.broadcastMessage(ChatColor.GOLD + "☀ The winter storm has passed.");
                }
            }, durationTicks);

        }, 0L, totalCycleTicks); 
    }

    private void stopWinterTimer() {
        isTimerEnabled = false;
        if (timerTask != null && !timerTask.isCancelled()) {
            timerTask.cancel();
        }
        disableWinter(); 
    }

    // --- Nether Fix "Ficks" Logic ---

    private void handleFicksCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /ww ficks <true|false>");
            return;
        }
        if (args[1].equalsIgnoreCase("true")) {
            startNetherFixMode(sender);
        } else {
            stopNetherFixMode(sender, true);
        }
    }

    private void startNetherFixMode(CommandSender sender) {
        if (isNetherFixModeEnabled) return;
        isNetherFixModeEnabled = true;
        fixedChunksSessionCount = 0;
        sender.sendMessage(ChatColor.GREEN + "✔ Nether Fix Mode ENABLED.");
        
        if (getConfig().getBoolean("nether-fix.log-to-console", true)) {
            netherLogTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                if (fixedChunksSessionCount > 0) {
                    getLogger().info("[WinterWibe Auto-Fix] Fixed " + fixedChunksSessionCount + " chunks.");
                    fixedChunksSessionCount = 0; 
                }
            }, 12000L, 12000L); 
        }
    }

    private void stopNetherFixMode(CommandSender sender, boolean notify) {
        isNetherFixModeEnabled = false;
        if (netherLogTask != null && !netherLogTask.isCancelled()) {
            netherLogTask.cancel();
        }
        if (notify) sender.sendMessage(ChatColor.RED + "✖ Nether Fix Mode DISABLED.");
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        Chunk chunk = event.getChunk();

        if (isWinterEnabled && world.getEnvironment() == World.Environment.NORMAL) {
            Bukkit.getScheduler().runTask(this, () -> storeAndChangeBiome(chunk, WINTER_BIOME));
        }
        if (isDelSnowEnabled && world.getEnvironment() == World.Environment.NORMAL) {
            Bukkit.getScheduler().runTask(this, () -> cleanSnowInChunk(chunk));
        }
        if (isNetherFixModeEnabled && world.getEnvironment() == World.Environment.NETHER) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (processNetherChunk(chunk)) fixedChunksSessionCount++;
            });
        }
    }

    private void enableWinter() {
        isWinterEnabled = true;
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                world.setStorm(true);
                world.setThundering(false);
                world.setGameRule(GameRule.RANDOM_TICK_SPEED, (int) (DEFAULT_TICK_SPEED * getConfig().getDouble("snow-speed", 1.0)));
                for (Chunk chunk : world.getLoadedChunks()) {
                    storeAndChangeBiome(chunk, WINTER_BIOME);
                }
            }
        }
    }

    private void disableWinter() {
        isWinterEnabled = false;
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                world.setStorm(false);
                world.setGameRule(GameRule.RANDOM_TICK_SPEED, DEFAULT_TICK_SPEED);
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                restoreBiome(chunk);
            }
        }
    }

    private void storeAndChangeBiome(Chunk chunk, Biome newBiome) {
        if (chunk.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        if (!chunk.getPersistentDataContainer().has(MODIFIED_FLAG_KEY)) {
            chunk.getPersistentDataContainer().set(ORIGINAL_BIOME_KEY, PersistentDataType.STRING, chunk.getWorld().getBiome(chunk.getX()*16+8, 64, chunk.getZ()*16+8).name());
            chunk.getPersistentDataContainer().set(MODIFIED_FLAG_KEY, PersistentDataType.BYTE, (byte) 1);
        }
        changeChunkBiome(chunk, newBiome);
    }
    
    private void restoreBiome(Chunk chunk) {
        if (chunk.getPersistentDataContainer().has(MODIFIED_FLAG_KEY)) {
            try {
                Biome original = Biome.valueOf(chunk.getPersistentDataContainer().get(ORIGINAL_BIOME_KEY, PersistentDataType.STRING));
                changeChunkBiome(chunk, original);
                chunk.getPersistentDataContainer().remove(ORIGINAL_BIOME_KEY);
                chunk.getPersistentDataContainer().remove(MODIFIED_FLAG_KEY);
            } catch (Exception ignored) {}
        }
    }

    private void changeChunkBiome(Chunk chunk, Biome biome) {
        World world = chunk.getWorld();
        int chunkX = chunk.getX() * 16;
        int chunkZ = chunk.getZ() * 16;
        boolean changed = false;

        for (int x = 0; x < 16; x += 4) {
            for (int z = 0; z < 16; z += 4) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y += 4) {
                    if (world.getBiome(chunkX + x, y, chunkZ + z) != biome) {
                        world.setBiome(chunkX + x, y, chunkZ + z, biome);
                        changed = true;
                    }
                }
            }
        }
        if (changed) world.refreshChunk(chunk.getX(), chunk.getZ());
    }

    private boolean processNetherChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int chunkX = chunk.getX() * 16;
        int chunkZ = chunk.getZ() * 16;
        boolean changed = false;

        for (int x = 0; x < 16; x += 4) {
            for (int z = 0; z < 16; z += 4) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y += 4) {
                    if (!isNetherBiome(world.getBiome(chunkX + x, y, chunkZ + z))) {
                        world.setBiome(chunkX + x, y, chunkZ + z, guessNetherBiome(chunk.getBlock(x, y, z).getType()));
                        changed = true;
                    }
                }
            }
        }
        if (changed) world.refreshChunk(chunk.getX(), chunk.getZ());
        return changed;
    }

    private boolean isNetherBiome(Biome biome) {
        return biome == Biome.NETHER_WASTES || biome == Biome.SOUL_SAND_VALLEY || biome == Biome.CRIMSON_FOREST || biome == Biome.WARPED_FOREST || biome == Biome.BASALT_DELTAS;
    }

    private Biome guessNetherBiome(Material material) {
        switch (material) {
            case SOUL_SAND: case SOUL_SOIL: case BONE_BLOCK: return Biome.SOUL_SAND_VALLEY;
            case CRIMSON_NYLIUM: case NETHER_WART_BLOCK: return Biome.CRIMSON_FOREST;
            case WARPED_NYLIUM: case WARPED_WART_BLOCK: return Biome.WARPED_FOREST;
            case BASALT: case BLACKSTONE: case MAGMA_BLOCK: return Biome.BASALT_DELTAS;
            default: return Biome.NETHER_WASTES;
        }
    }
}