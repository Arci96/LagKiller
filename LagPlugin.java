package de.lagkiller;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LagPlugin extends JavaPlugin implements Listener, TabExecutor {

    /** Eindeutiger Schluessel fuer einen Chunk. */
    record ChunkKey(String world, int x, int z) {
        static ChunkKey of(Chunk c) {
            return new ChunkKey(c.getWorld().getName(), c.getX(), c.getZ());
        }

        String serialize() {
            return world + ";" + x + ";" + z;
        }

        static ChunkKey parse(String s) {
            try {
                String[] p = s.split(";");
                return new ChunkKey(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return world + " [" + x + ", " + z + "]";
        }
    }

    /** Statistik pro Chunk. */
    static class Stats {
        int items, xp, mobs, projectiles, vehicles, other, hoppers, redstone;

        int entities() {
            return items + xp + mobs + projectiles + vehicles + other;
        }

        double score() {
            return items + xp * 0.5 + mobs * 2 + projectiles * 0.5 + vehicles * 2 + other
                    + hoppers * 3 + redstone / 20.0;
        }

        void add(Stats o) {
            items += o.items;
            xp += o.xp;
            mobs += o.mobs;
            projectiles += o.projectiles;
            vehicles += o.vehicles;
            other += o.other;
            hoppers += o.hoppers;
            redstone += o.redstone;
        }
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<ChunkKey, Integer> redstoneCounter = new HashMap<>();
    private Map<ChunkKey, Integer> redstoneLast = new HashMap<>();
    private final Set<ChunkKey> excluded = new HashSet<>();
    private final Set<EntityType> protectedTypes = new HashSet<>();
    private long lastWarn = 0;

    // ------------------------------------------------------------------ Lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("lag");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        startDetection();
        getLogger().info("LagKiller aktiviert.");
    }

    private void loadSettings() {
        reloadConfig();
        excluded.clear();
        for (String s : getConfig().getStringList("excluded-chunks")) {
            ChunkKey k = ChunkKey.parse(s);
            if (k != null) excluded.add(k);
        }
        protectedTypes.clear();
        for (String s : getConfig().getStringList("clear.protected-entity-types")) {
            try {
                protectedTypes.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Unbekannter Entity-Typ in der Config: " + s);
            }
        }
    }

    private void saveExcluded() {
        List<String> list = new ArrayList<>();
        for (ChunkKey k : excluded) list.add(k.serialize());
        getConfig().set("excluded-chunks", list);
        saveConfig();
    }

    // ------------------------------------------------------------------ Erkennung

    @EventHandler(ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        Block b = e.getBlock();
        ChunkKey key = new ChunkKey(b.getWorld().getName(), b.getX() >> 4, b.getZ() >> 4);
        redstoneCounter.merge(key, 1, Integer::sum);
    }

    private void startDetection() {
        long interval = Math.max(1, getConfig().getLong("detection.check-interval-seconds", 5)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            // Redstone-Fenster abschliessen
            redstoneLast = new HashMap<>(redstoneCounter);
            redstoneCounter.clear();

            double tps = Bukkit.getTPS()[0];
            double threshold = getConfig().getDouble("detection.tps-threshold", 17.0);
            if (tps >= threshold) return;

            long cooldown = getConfig().getLong("detection.warn-cooldown-seconds", 60) * 1000L;
            long now = System.currentTimeMillis();
            if (now - lastWarn < cooldown) return;
            lastWarn = now;

            Stats total = new Stats();
            Map<ChunkKey, Stats> all = scanAll();
            all.values().forEach(total::add);

            String cause = mainCause(total);
            Component msg = prefix().append(LEGACY.deserialize(String.format(Locale.ROOT,
                    "&cLag erkannt! &7TPS: &e%.1f &7| Hauptursache: &e%s &7| Nutze &e/lag info &7oder &e/lag", tps, cause)));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("lag.notify")) p.sendMessage(msg);
            }
            Bukkit.getConsoleSender().sendMessage(msg);
        }, interval, interval);
    }

    private String mainCause(Stats s) {
        Map<String, Double> w = new HashMap<>();
        w.put("Items (" + s.items + ")", (double) s.items);
        w.put("Mobs (" + s.mobs + ")", s.mobs * 2.0);
        w.put("XP-Orbs (" + s.xp + ")", s.xp * 0.5);
        w.put("Projektile (" + s.projectiles + ")", s.projectiles * 0.5);
        w.put("Fahrzeuge (" + s.vehicles + ")", s.vehicles * 2.0);
        w.put("Hopper (" + s.hoppers + ")", s.hoppers * 3.0);
        w.put("Redstone (" + s.redstone + " Updates)", s.redstone / 20.0);
        return w.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
    }

    // ------------------------------------------------------------------ Scan

    private Map<ChunkKey, Stats> scanAll() {
        Map<ChunkKey, Stats> result = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                ChunkKey key = ChunkKey.of(chunk);
                Stats s = new Stats();
                for (Entity e : chunk.getEntities()) {
                    if (e instanceof Player) continue;
                    if (e instanceof Item) s.items++;
                    else if (e instanceof ExperienceOrb) s.xp++;
                    else if (e instanceof Projectile) s.projectiles++;
                    else if (e instanceof Vehicle) s.vehicles++;
                    else if (e instanceof LivingEntity && !(e instanceof org.bukkit.entity.ArmorStand)) s.mobs++;
                    else s.other++;
                }
                for (var state : chunk.getTileEntities(false)) {
                    if (state instanceof Hopper) s.hoppers++;
                }
                s.redstone = redstoneLast.getOrDefault(key, 0);
                result.put(key, s);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ Aufraeumen

    private static class ClearResult {
        int items, xp, projectiles, mobs, redstoneBlocks, skippedChunks;
    }

    private ClearResult clearLag() {
        var cfg = getConfig();
        boolean cItems = cfg.getBoolean("clear.items", true);
        boolean cXp = cfg.getBoolean("clear.xp-orbs", true);
        boolean cProj = cfg.getBoolean("clear.projectiles", true);
        boolean cMobs = cfg.getBoolean("clear.mobs", true);
        int maxMobs = cfg.getInt("clear.max-mobs-per-chunk", 30);
        boolean cRed = cfg.getBoolean("clear.redstone", false);
        int redThreshold = cfg.getInt("clear.redstone-threshold", 1000);

        ClearResult r = new ClearResult();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                ChunkKey key = ChunkKey.of(chunk);
                if (excluded.contains(key)) {
                    r.skippedChunks++;
                    continue;
                }
                List<Mob> mobs = new ArrayList<>();
                for (Entity e : chunk.getEntities()) {
                    if (e instanceof Player) continue;
                    if (protectedTypes.contains(e.getType())) continue;
                    if (e instanceof Item && cItems) {
                        e.remove();
                        r.items++;
                    } else if (e instanceof ExperienceOrb && cXp) {
                        e.remove();
                        r.xp++;
                    } else if (e instanceof Projectile && cProj) {
                        e.remove();
                        r.projectiles++;
                    } else if (e instanceof Mob m && cMobs && isRemovable(m)) {
                        mobs.add(m);
                    }
                }
                if (cMobs && mobs.size() > maxMobs) {
                    // Aelteste zuerst entfernen
                    mobs.sort(Comparator.comparingInt(Entity::getTicksLived).reversed());
                    for (int i = 0; i < mobs.size() - maxMobs; i++) {
                        mobs.get(i).remove();
                        r.mobs++;
                    }
                }
                if (cRed && redstoneLast.getOrDefault(key, 0) >= redThreshold) {
                    r.redstoneBlocks += clearRedstone(chunk);
                }
            }
        }
        return r;
    }

    private boolean isRemovable(Mob m) {
        if (m.customName() != null) return false;
        if (m instanceof Tameable t && t.isTamed()) return false;
        if (m instanceof AbstractVillager) return false;
        if (m.isLeashed()) return false;
        if (m.isInsideVehicle() || !m.getPassengers().isEmpty()) return false;
        return true;
    }

    private int clearRedstone(Chunk chunk) {
        int removed = 0;
        World w = chunk.getWorld();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = w.getMinHeight(); y < w.getMaxHeight(); y++) {
                    Block b = chunk.getBlock(x, y, z);
                    Material t = b.getType();
                    if (t == Material.REDSTONE_WIRE || t == Material.REPEATER
                            || t == Material.COMPARATOR || t == Material.OBSERVER) {
                        b.setType(Material.AIR, false);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ Befehle

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "clear" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "clear" -> {
                if (!sender.hasPermission("lag.clear")) return noPerm(sender);
                ClearResult r = clearLag();
                msg(sender, "&aLagquellen entfernt:");
                msg(sender, " &7Items: &e" + r.items + " &7| XP-Orbs: &e" + r.xp
                        + " &7| Projektile: &e" + r.projectiles + " &7| Mobs: &e" + r.mobs);
                if (r.redstoneBlocks > 0) msg(sender, " &7Redstone-Bauteile: &e" + r.redstoneBlocks);
                if (r.skippedChunks > 0) msg(sender, " &7Uebersprungene (ausgeschlossene) Chunks: &e" + r.skippedChunks);
            }
            case "info" -> {
                if (!sender.hasPermission("lag.info")) return noPerm(sender);
                showInfo(sender);
            }
            case "exclude" -> {
                if (!sender.hasPermission("lag.admin")) return noPerm(sender);
                ChunkKey k = targetChunk(sender, args);
                if (k == null) return true;
                if (excluded.add(k)) {
                    saveExcluded();
                    msg(sender, "&aChunk &e" + k + " &aist jetzt vom Loeschen ausgeschlossen.");
                } else {
                    msg(sender, "&cDieser Chunk ist bereits ausgeschlossen.");
                }
            }
            case "include" -> {
                if (!sender.hasPermission("lag.admin")) return noPerm(sender);
                ChunkKey k = targetChunk(sender, args);
                if (k == null) return true;
                if (excluded.remove(k)) {
                    saveExcluded();
                    msg(sender, "&aChunk &e" + k + " &awird wieder bereinigt.");
                } else {
                    msg(sender, "&cDieser Chunk war nicht ausgeschlossen.");
                }
            }
            case "list" -> {
                if (!sender.hasPermission("lag.admin")) return noPerm(sender);
                if (excluded.isEmpty()) {
                    msg(sender, "&7Keine Chunks ausgeschlossen.");
                } else {
                    msg(sender, "&aAusgeschlossene Chunks (&e" + excluded.size() + "&a):");
                    for (ChunkKey k : excluded) msg(sender, " &8- &e" + k);
                }
            }
            case "tp" -> {
                if (!sender.hasPermission("lag.admin")) return noPerm(sender);
                if (!(sender instanceof Player p) || args.length < 4) {
                    msg(sender, "&cNutzung: /lag tp <welt> <chunkX> <chunkZ> (nur Spieler)");
                    return true;
                }
                World w = Bukkit.getWorld(args[1]);
                if (w == null) {
                    msg(sender, "&cWelt nicht gefunden.");
                    return true;
                }
                try {
                    int cx = Integer.parseInt(args[2]);
                    int cz = Integer.parseInt(args[3]);
                    int bx = cx * 16 + 8, bz = cz * 16 + 8;
                    Location loc = new Location(w, bx + 0.5, w.getHighestBlockYAt(bx, bz) + 1, bz + 0.5);
                    p.teleportAsync(loc);
                } catch (NumberFormatException ex) {
                    msg(sender, "&cUngueltige Chunk-Koordinaten.");
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("lag.admin")) return noPerm(sender);
                loadSettings();
                msg(sender, "&aConfig neu geladen. (Check-Intervall wird erst nach Neustart uebernommen.)");
            }
            default -> msg(sender, "&cNutzung: /lag [info|exclude|include|list|tp|reload]");
        }
        return true;
    }

    private void showInfo(CommandSender sender) {
        Map<ChunkKey, Stats> all = scanAll();
        Stats total = new Stats();
        all.values().forEach(total::add);

        double tps = Bukkit.getTPS()[0];
        double mspt = Bukkit.getAverageTickTime();
        msg(sender, String.format(Locale.ROOT, "&aServer-Status: &7TPS &e%.1f &7| MSPT &e%.1f &7| Geladene Chunks &e%d",
                tps, mspt, all.size()));
        msg(sender, "&aLagquellen (gesamt):");
        msg(sender, " &7Items: &e" + total.items + " &7| XP-Orbs: &e" + total.xp + " &7| Mobs: &e" + total.mobs);
        msg(sender, " &7Projektile: &e" + total.projectiles + " &7| Fahrzeuge: &e" + total.vehicles
                + " &7| Sonstige: &e" + total.other);
        msg(sender, " &7Hopper: &e" + total.hoppers + " &7| Redstone-Updates (letzte Messung): &e" + total.redstone);
        msg(sender, " &7Hauptursache: &c" + mainCause(total));

        List<Map.Entry<ChunkKey, Stats>> top = new ArrayList<>(all.entrySet());
        top.sort((a, b) -> Double.compare(b.getValue().score(), a.getValue().score()));
        msg(sender, "&aSchlimmste Chunks (Klick = Teleport):");
        int shown = 0;
        for (var en : top) {
            if (shown >= 5 || en.getValue().score() <= 0) break;
            ChunkKey k = en.getKey();
            Stats s = en.getValue();
            String line = String.format(Locale.ROOT,
                    " &8#%d &e%s &7[%d, %d] &8| &7Items %d, Mobs %d, Hopper %d, Redstone %d%s",
                    ++shown, k.world(), k.x(), k.z(), s.items, s.mobs, s.hoppers, s.redstone,
                    excluded.contains(k) ? " &a(ausgeschlossen)" : "");
            Component c = LEGACY.deserialize(line)
                    .clickEvent(ClickEvent.runCommand("/lag tp " + k.world() + " " + k.x() + " " + k.z()))
                    .hoverEvent(HoverEvent.showText(Component.text("Zum Chunk teleportieren")));
            sender.sendMessage(c);
        }
        if (shown == 0) msg(sender, " &7Nichts Auffaelliges.");
    }

    /** Chunk aus Argumenten (welt x z) oder, bei Spielern ohne Argumente, der aktuelle Chunk. */
    private ChunkKey targetChunk(CommandSender sender, String[] args) {
        if (args.length >= 4) {
            try {
                return new ChunkKey(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            } catch (NumberFormatException ex) {
                msg(sender, "&cUngueltige Chunk-Koordinaten.");
                return null;
            }
        }
        if (sender instanceof Player p) return ChunkKey.of(p.getLocation().getChunk());
        msg(sender, "&cNutzung: /lag <exclude|include> <welt> <chunkX> <chunkZ>");
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("clear", "info", "exclude", "include", "list", "tp", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && List.of("exclude", "include", "tp").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> out = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) out.add(w.getName());
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------------------------ Helfer

    private Component prefix() {
        return LEGACY.deserialize(getConfig().getString("prefix", "&8[&cLag&8] &7"));
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(prefix().append(LEGACY.deserialize(text)));
    }

    private boolean noPerm(CommandSender s) {
        msg(s, "&cDafuer hast du keine Berechtigung.");
        return true;
    }
}
