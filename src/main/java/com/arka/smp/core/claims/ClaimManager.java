package com.arka.smp.core.claims;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rectangular claims, full height. Lookups use a chunk index: finding the claim at a block is one
 * hash lookup plus a check of the few claims touching that chunk.
 */
public final class ClaimManager {
    public record ChunkKey(String world, int x, int z) {
    }

    public static final class Claim {
        public final int id;
        public final UUID owner;
        public final String world;
        public final int minX, minZ, maxX, maxZ;
        /** Players allowed to build here. Trust belongs to THIS claim only. */
        public final Set<UUID> trusted = java.util.concurrent.ConcurrentHashMap.newKeySet();

        Claim(int id, UUID owner, String world, int x1, int z1, int x2, int z2) {
            this.id = id;
            this.owner = owner;
            this.world = world;
            this.minX = Math.min(x1, x2);
            this.maxX = Math.max(x1, x2);
            this.minZ = Math.min(z1, z2);
            this.maxZ = Math.max(z1, z2);
        }

        public int width() { return maxX - minX + 1; }
        public int length() { return maxZ - minZ + 1; }
        public long area() { return (long) width() * length(); }
        public boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }

        boolean intersects(int x1, int z1, int x2, int z2) {
            return minX <= x2 && maxX >= x1 && minZ <= z2 && maxZ >= z1;
        }
    }

    private record Display(List<Location> blocks, BukkitTask restore) {
    }

    private final ArkaSMPCore plugin;
    private final DataFile data;
    private final Map<Integer, Claim> all = new HashMap<>();
    private final Map<ChunkKey, List<Claim>> index = new HashMap<>();
    private final Map<UUID, List<Claim>> byOwner = new java.util.concurrent.ConcurrentHashMap<>();   // read by async placeholders
    private final Set<UUID> bypassing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Display> displays = new HashMap<>();
    private int nextId = 1;

    public ClaimManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.data = new DataFile(plugin, "claims-v2.yml");
        load();
    }

    // ---- persistence ------------------------------------------------------------------------

    private void load() {
        ConfigurationSection cs = data.yaml().getConfigurationSection("claims");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                ConfigurationSection s = cs.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    int id = Integer.parseInt(key);
                    Claim c = new Claim(id, UUID.fromString(s.getString("owner", "")), s.getString("world", ""),
                            s.getInt("x1"), s.getInt("z1"), s.getInt("x2"), s.getInt("z2"));
                    for (String t : s.getStringList("trusted")) {
                        try {
                            c.trusted.add(UUID.fromString(t));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    register(c);
                    nextId = Math.max(nextId, id + 1);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        // Old versions trusted a player for ALL of an owner's claims. Keep what people were promised:
        // copy each old entry onto that owner's existing claims. From now on trust is per claim.
        ConfigurationSection tr = data.yaml().getConfigurationSection("trusted");
        if (tr != null) {
            int moved = 0;
            for (String owner : tr.getKeys(false)) {
                try {
                    UUID o = UUID.fromString(owner);
                    for (String t : tr.getStringList(owner)) {
                        UUID friend = UUID.fromString(t);
                        for (Claim c : claimsOf(o)) {
                            c.trusted.add(friend);
                            moved++;
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (moved > 0) {
                plugin.getLogger().info("Converted old owner-wide trust into per-claim trust (" + moved + " entries).");
                changed();
            }
        }
    }

    private void changed() {
        var y = data.yaml();
        y.set("claims", null);
        y.set("trusted", null);
        for (Claim c : all.values()) {
            String p = "claims." + c.id;
            y.set(p + ".owner", c.owner.toString());
            y.set(p + ".world", c.world);
            y.set(p + ".x1", c.minX);
            y.set(p + ".z1", c.minZ);
            y.set(p + ".x2", c.maxX);
            y.set(p + ".z2", c.maxZ);
            if (!c.trusted.isEmpty()) y.set(p + ".trusted", c.trusted.stream().map(UUID::toString).toList());
        }
        data.markDirty();
    }

    public void save() {
        clearAllDisplays();
        data.saveNow();
    }

    // ---- index ------------------------------------------------------------------------------

    private void register(Claim c) {
        all.put(c.id, c);
        byOwner.computeIfAbsent(c.owner, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(c);
        for (int cx = c.minX >> 4; cx <= c.maxX >> 4; cx++) {
            for (int cz = c.minZ >> 4; cz <= c.maxZ >> 4; cz++) {
                index.computeIfAbsent(new ChunkKey(c.world, cx, cz), k -> new ArrayList<>(2)).add(c);
            }
        }
    }

    private void unregister(Claim c) {
        all.remove(c.id);
        List<Claim> mine = byOwner.get(c.owner);
        if (mine != null) mine.remove(c);
        for (int cx = c.minX >> 4; cx <= c.maxX >> 4; cx++) {
            for (int cz = c.minZ >> 4; cz <= c.maxZ >> 4; cz++) {
                ChunkKey k = new ChunkKey(c.world, cx, cz);
                List<Claim> list = index.get(k);
                if (list != null) {
                    list.remove(c);
                    if (list.isEmpty()) index.remove(k);
                }
            }
        }
    }

    // ---- queries ----------------------------------------------------------------------------

    public Claim claimAt(String world, int x, int z) {
        List<Claim> list = index.get(new ChunkKey(world, x >> 4, z >> 4));
        if (list == null) return null;
        for (Claim c : list) if (c.contains(x, z)) return c;
        return null;
    }

    public Claim claimAt(Location l) {
        return claimAt(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    public Claim claimAt(Block b) {
        return claimAt(b.getWorld().getName(), b.getX(), b.getZ());
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    public Claim byId(int id) {
        return all.get(id);
    }

    public UUID ownerOf(Location l) {
        Claim c = claimAt(l);
        return c == null ? null : c.owner;
    }

    public UUID ownerOf(Block b) {
        Claim c = claimAt(b);
        return c == null ? null : c.owner;
    }

    public boolean isClaimed(Location l) {
        return claimAt(l) != null;
    }

    public String ownerName(UUID id) {
        return id == null ? "-" : plugin.players().name(id);
    }

    /** Owner of this claim, or trusted in THIS claim. Ignores the admin bypass (used by /trapped). */
    public boolean isMember(Player p, Claim c) {
        return c == null || c.owner.equals(p.getUniqueId()) || c.trusted.contains(p.getUniqueId());
    }

    /**
     * Staff bypass: needs the arka.claims.bypass permission AND (by default) switching it on with
     * /arka admin bypass, so operators and wildcard-permission staff are protected like everyone else
     * until they deliberately choose to bypass.
     */
    public boolean bypassing(Player p) {
        if (!p.hasPermission("arka.claims.bypass")) return false;
        return !plugin.getConfig().getBoolean("claims.bypass-needs-toggle", true) || bypassing.contains(p.getUniqueId());
    }

    public boolean toggleBypass(Player p) {
        UUID id = p.getUniqueId();
        if (bypassing.remove(id)) return false;
        bypassing.add(id);
        return true;
    }

    public void forgetBypass(UUID id) {
        bypassing.remove(id);
    }

    public boolean canBuild(Player p, Claim c) {
        return c == null || isMember(p, c) || bypassing(p);
    }

    public boolean canBuild(Player p, Location l) {
        return canBuild(p, claimAt(l));
    }

    public boolean canBuild(Player p, Block b) {
        return canBuild(p, claimAt(b));
    }

    public List<Claim> claimsOf(UUID owner) {
        return byOwner.getOrDefault(owner, List.of());
    }

    public int count(UUID owner) {
        return claimsOf(owner).size();
    }

    public long usedBlocks(UUID owner) {
        long sum = 0;
        for (Claim c : claimsOf(owner)) sum += c.area();
        return sum;
    }

    /** First claim whose rectangle overlaps the given area (null = free). */
    private Claim overlapping(String world, int x1, int z1, int x2, int z2) {
        for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) {
            for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) {
                List<Claim> list = index.get(new ChunkKey(world, cx, cz));
                if (list == null) continue;
                for (Claim c : list) if (c.intersects(x1, z1, x2, z2)) return c;
            }
        }
        return null;
    }

    // ---- actions ----------------------------------------------------------------------------

    public void createClaim(Player p, Location a, Location b) {
        var msg = plugin.messages();
        if (a.getWorld() != b.getWorld()) {
            msg.send(p, "claim-other-world");
            return;
        }
        String world = a.getWorld().getName();
        if (plugin.getConfig().getStringList("claims.disabled-worlds").contains(world)) {
            msg.send(p, "claim-disabled-world");
            return;
        }
        int x1 = Math.min(a.getBlockX(), b.getBlockX()), x2 = Math.max(a.getBlockX(), b.getBlockX());
        int z1 = Math.min(a.getBlockZ(), b.getBlockZ()), z2 = Math.max(a.getBlockZ(), b.getBlockZ());
        int w = x2 - x1 + 1, l = z2 - z1 + 1;
        int min = plugin.getConfig().getInt("claims.min-size", 5);
        if (w < min || l < min) {
            msg.send(p, "claim-too-small", "min", String.valueOf(min));
            return;
        }
        Claim hit = overlapping(world, x1, z1, x2, z2);
        if (hit != null) {
            msg.send(p, "claim-overlap", "owner", ownerName(hit.owner));
            show(p, hit);   // red border (if it is someone else's) so they see exactly how far it reaches
            return;
        }
        long area = (long) w * l;
        long have = plugin.players().remaining(p.getUniqueId());
        if (area > have) {
            msg.send(p, "claim-not-enough", "need", String.valueOf(area), "have", String.valueOf(have));
            return;
        }
        Claim c = new Claim(nextId++, p.getUniqueId(), world, x1, z1, x2, z2);
        register(c);
        changed();
        msg.send(p, "claim-created", "w", String.valueOf(w), "l", String.valueOf(l), "area", String.valueOf(area),
                "remaining", String.valueOf(plugin.players().remaining(p.getUniqueId())));
        show(p, c);
    }

    /** Delete the claim the player stands in (owner, or anyone when admin). */
    public void deleteHere(Player p, boolean admin) {
        Claim c = claimAt(p.getLocation());
        if (c == null) {
            plugin.messages().send(p, "claim-none-here");
            return;
        }
        if (!c.owner.equals(p.getUniqueId()) && !admin) {
            plugin.messages().send(p, "claim-not-yours");
            return;
        }
        delete(c);
        plugin.messages().send(p, "claim-removed", "area", String.valueOf(c.area()));
    }

    public void delete(Claim c) {
        unregister(c);
        changed();
    }

    public int deleteAll(UUID owner) {
        List<Claim> list = new ArrayList<>(claimsOf(owner));
        list.forEach(this::unregister);
        changed();
        return list.size();
    }

    /** The claims a trust/untrust applies to: the one the owner stands in, or all of theirs. Null = not allowed. */
    private List<Claim> trustScope(Player owner, boolean all) {
        Claim here = claimAt(owner.getLocation());
        if (here == null) {
            plugin.messages().send(owner, "trust-not-in-claim");
            return null;
        }
        if (!here.owner.equals(owner.getUniqueId())) {
            plugin.messages().send(owner, "claim-not-yours");
            return null;
        }
        return all ? new ArrayList<>(claimsOf(owner.getUniqueId())) : List.of(here);
    }

    /** Trusts the player in the claim you are standing in (or in every claim of yours when all is true). */
    public void trust(Player owner, OfflinePlayer target, boolean all) {
        String name = target.getName() != null ? target.getName() : ownerName(target.getUniqueId());
        if (target.getUniqueId().equals(owner.getUniqueId())) {
            plugin.messages().send(owner, "trust-self");
            return;
        }
        List<Claim> scope = trustScope(owner, all);
        if (scope == null) return;
        boolean any = false;
        for (Claim c : scope) any |= c.trusted.add(target.getUniqueId());
        if (!any) {
            plugin.messages().send(owner, "trust-already", "player", name);
            return;
        }
        changed();
        plugin.messages().send(owner, all ? "trust-added-all" : "trust-added", "player", name);
    }

    public void untrust(Player owner, OfflinePlayer target, boolean all) {
        String name = target.getName() != null ? target.getName() : ownerName(target.getUniqueId());
        List<Claim> scope = trustScope(owner, all);
        if (scope == null) return;
        boolean any = false;
        for (Claim c : scope) any |= c.trusted.remove(target.getUniqueId());
        if (!any) {
            plugin.messages().send(owner, "trust-not-trusted", "player", name);
            return;
        }
        changed();
        plugin.messages().send(owner, all ? "trust-removed-all" : "trust-removed", "player", name);
    }

    // ---- fake-block borders (only the viewing player sees them) ------------------------------

    /** Own/trusted claims use the normal border block; someone else's claim is drawn in red. */
    public void show(Player p, Claim c) {
        var cfg = plugin.getConfig();
        World w = Bukkit.getWorld(c.world);
        if (w == null || p.getWorld() != w) return;
        BlockData[] looks = isMember(p, c) ? ownLook() : foreignLook();
        int step = Math.max(1, cfg.getInt("claims.border.spacing", 2));
        int maxDist = cfg.getInt("claims.border.max-distance", 64);
        int px = p.getLocation().getBlockX(), pz = p.getLocation().getBlockZ();

        List<Location> blocks = new ArrayList<>();
        for (int x = c.minX; x <= c.maxX; x++) {
            if ((x - c.minX) % step == 0 || x == c.maxX) {
                addMarker(w, x, c.minZ, px, pz, maxDist, blocks);
                addMarker(w, x, c.maxZ, px, pz, maxDist, blocks);
            }
        }
        for (int z = c.minZ; z <= c.maxZ; z++) {
            if ((z - c.minZ) % step == 0 || z == c.maxZ) {
                addMarker(w, c.minX, z, px, pz, maxDist, blocks);
                addMarker(w, c.maxX, z, px, pz, maxDist, blocks);
            }
        }
        display(p, blocks, looks);
    }

    private BlockData[] ownLook() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("claims.border.material", "GLOWSTONE"));
        return new BlockData[]{(m != null && m.isBlock() ? m : Material.GLOWSTONE).createBlockData()};
    }

    /** Alternating red blocks (default netherrack + redstone block) so it is obvious it is not yours. */
    private BlockData[] foreignLook() {
        List<BlockData> out = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("claims.border.foreign-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null && m.isBlock()) out.add(m.createBlockData());
        }
        if (out.isEmpty()) {
            out.add(Material.NETHERRACK.createBlockData());
            out.add(Material.REDSTONE_BLOCK.createBlockData());
        }
        return out.toArray(new BlockData[0]);
    }

    /** Single glowing marker, used for the first corner. */
    public void showCorner(Player p, Location at) {
        display(p, new ArrayList<>(List.of(at.getBlock().getLocation())), ownLook());
    }

    private void addMarker(World w, int x, int z, int px, int pz, int maxDist, List<Location> out) {
        if (Math.abs(x - px) > maxDist || Math.abs(z - pz) > maxDist) return;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        out.add(new Location(w, x, y, z));
    }

    private void display(Player p, List<Location> blocks, BlockData[] looks) {
        clearDisplay(p.getUniqueId());
        for (int i = 0; i < blocks.size(); i++) p.sendBlockChange(blocks.get(i), looks[i % looks.length]);
        int seconds = Math.max(1, plugin.getConfig().getInt("claims.border.seconds", 15));
        UUID id = p.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> clearDisplay(id), seconds * 20L);
        displays.put(id, new Display(blocks, task));
    }

    public void clearDisplay(UUID id) {
        Display d = displays.remove(id);
        if (d == null) return;
        d.restore().cancel();
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        for (Location l : d.blocks()) {
            if (l.getWorld() != null && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) {
                p.sendBlockChange(l, l.getBlock().getBlockData());
            }
        }
    }

    private void clearAllDisplays() {
        new ArrayList<>(displays.keySet()).forEach(this::clearDisplay);
    }
}
