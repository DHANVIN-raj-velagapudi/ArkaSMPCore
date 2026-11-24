package com.arka.smp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;

/** Location (de)serialization that survives missing worlds. */
public final class Locs {
    private Locs() {
    }

    public static String str(Location l) {
        return String.format(Locale.ROOT, "%s;%.2f;%.2f;%.2f;%.1f;%.1f",
                l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    public static Location parse(String s) {
        if (s == null) return null;
        String[] p = s.split(";");
        if (p.length < 6) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
