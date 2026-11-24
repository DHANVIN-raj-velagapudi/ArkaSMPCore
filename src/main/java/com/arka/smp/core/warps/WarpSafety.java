package com.arka.smp.core.warps;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.TrapDoor;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether a spot is safe to stand on. Main thread only, and the chunks around the spot must be
 * loaded (WarpManager loads them first). Nothing here loads chunks.
 *
 * Checks, cheapest first:
 *  VOID        spot is outside the world height or world border
 *  BLOCKED     feet or head is inside a solid block, liquid or a hazard
 *  FLOOR       nothing solid to stand on, or the floor itself is a hazard (magma, cactus...)
 *  HAZARD      lava, fire, cactus, dripstone... within two blocks around the spot
 *  LAVA_BELOW  lava under the floor before any solid ground
 *  PLATFORM    a tiny platform (few floor blocks around the spot) with a long drop under it. Big platforms
 *              (a sky base) are fine; max-height-above-ground is how far down we look for ground
 *  ENCLOSED    a sealed pocket (for example an obsidian cell) with no way out
 */
public final class WarpSafety {
    public enum Problem { VOID, BLOCKED, FLOOR, HAZARD, LAVA_BELOW, PLATFORM, ENCLOSED }

    public record Result(Problem problem, String detail) {
        public boolean safe() {
            return problem == null;
        }

        public static final Result OK = new Result(null, "");
    }

    /** Settings, read from config.yml (warps.safety). */
    public record Settings(int maxHeightAboveGround, int minPlatformBlocks, int platformDrop, int roomCells) {
    }

    private static final Set<Material> HAZARD = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE, Material.POINTED_DRIPSTONE, Material.COBWEB, Material.TNT, Material.BUBBLE_COLUMN,
            Material.END_PORTAL, Material.NETHER_PORTAL, Material.END_GATEWAY, Material.LAVA_CAULDRON,
            Material.POWDER_SNOW_CAULDRON);

    private static final Set<Material> UNBREAKABLE = EnumSet.of(
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.BEDROCK, Material.REINFORCED_DEEPSLATE,
            Material.BARRIER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK);

    private WarpSafety() {
    }

    /**
     * A player standing on a slab, carpet or snow has a feet block that is "solid". Moves the spot up onto
     * that block so the checks (and the later teleport) use a real standing position.
     */
    public static Location normalize(Location loc) {
        Location l = loc.clone();
        World w = l.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        if (w == null) return l;
        Block feet = w.getBlockAt(x, y, z);
        if (l.getY() - y > 0.001 && !feet.isPassable() && !feet.isLiquid()) {
            l.setY(y + 1);
        } else if (l.getY() - y > 0.001) {
            l.setY(y);            // standing a hair above a full block: snap to the block
        }
        return l;
    }

    public static Result check(Location loc, Settings s) {
        World w = loc.getWorld();
        if (w == null) return new Result(Problem.VOID, "world");
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        if (y <= w.getMinHeight() + 1 || y >= w.getMaxHeight() - 2 || !w.getWorldBorder().isInside(loc)) {
            return new Result(Problem.VOID, "");
        }
        Block floor = w.getBlockAt(x, y - 1, z);
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        if (!free(feet) || !free(head)) return new Result(Problem.BLOCKED, feet.getType().name());
        if (!floor.getType().isSolid() || HAZARD.contains(floor.getType())) {
            // standing in water over solid ground is fine; anything else is not
            if (!(floor.getType() == Material.WATER && isSolidBelow(w, x, y - 2, z, 3))) {
                return new Result(Problem.FLOOR, floor.getType().name());
            }
        }
        // hazards around the spot (two blocks sideways, three below, two above)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) continue;
                    Material m = w.getBlockAt(x + dx, y + dy, z + dz).getType();
                    if (HAZARD.contains(m)) return new Result(Problem.HAZARD, m.name());
                }
            }
        }
        // how far is the ground below the floor? lava first = bad, nothing within the limit = too high
        int below = -1;
        for (int d = 2; d <= s.maxHeightAboveGround() + 1; d++) {
            int yy = y - d;
            if (yy <= w.getMinHeight()) break;
            Block b = w.getBlockAt(x, yy, z);
            Material m = b.getType();
            if (m == Material.LAVA) return new Result(Problem.LAVA_BELOW, "");
            if (m.isSolid() || m == Material.WATER) {
                below = d - 2;     // empty blocks between the floor and the ground
                break;
            }
        }
        if (below < 0) below = s.maxHeightAboveGround() + 1;   // no ground in range: treat as a long drop
        if (below > s.platformDrop()) {
            int solid = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)
                            || w.getBlockAt(x + dx, y - 1, z + dz).getType().isSolid()) solid++;   // never load chunks here
                }
            }
            if (solid < s.minPlatformBlocks()) return new Result(Problem.PLATFORM, String.valueOf(solid));
        }
        if (enclosed(w, x, y, z, s.roomCells())) return new Result(Problem.ENCLOSED, "");
        return Result.OK;
    }

    private static boolean isSolidBelow(World w, int x, int y, int z, int depth) {
        for (int d = 0; d < depth; d++) {
            if (w.getBlockAt(x, y - d, z).getType().isSolid()) return true;
        }
        return false;
    }

    private static boolean free(Block b) {
        return b.isPassable() && !b.isLiquid() && !HAZARD.contains(b.getType());
    }

    /**
     * Flood fill through open air (doors, trapdoors and gates count as open). If the fill runs out before
     * it reaches "cells" blocks, the player is in a sealed pocket. Unloaded chunks count as open.
     */
    private static boolean enclosed(World w, int sx, int sy, int sz, int cells) {
        record P(int x, int y, int z) {
        }
        ArrayDeque<P> queue = new ArrayDeque<>();
        Set<P> seen = new HashSet<>();
        P start = new P(sx, sy, sz);
        queue.add(start);
        seen.add(start);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        while (!queue.isEmpty()) {
            P p = queue.poll();
            if (seen.size() >= cells) return false;
            for (int[] d : dirs) {
                int nx = p.x + d[0], ny = p.y + d[1], nz = p.z + d[2];
                P n = new P(nx, ny, nz);
                if (seen.contains(n)) continue;
                if (ny <= w.getMinHeight() || ny >= w.getMaxHeight()) return false;   // open to the sky / void
                if (!w.isChunkLoaded(nx >> 4, nz >> 4)) return false;
                Block b = w.getBlockAt(nx, ny, nz);
                if (!open(b)) continue;
                seen.add(n);
                queue.add(n);
            }
        }
        return true;
    }

    private static boolean open(Block b) {
        if (b.isPassable()) return true;
        var data = b.getBlockData();
        return data instanceof Door || data instanceof TrapDoor || data instanceof Gate;
    }

    /** True if the pocket around a sealed spot is lined with unbreakable blocks (only used for the message). */
    public static boolean unbreakableNear(World w, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (UNBREAKABLE.contains(w.getBlockAt(x + dx, y + dy, z + dz).getType())) return true;
                }
            }
        }
        return false;
    }
}
