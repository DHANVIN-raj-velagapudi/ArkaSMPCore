package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.stats.ServerStats;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin dashboard: every server statistic as one icon with a big number, grouped in rows.
 * The bottom row is a 7-day bar chart of the daily peak (taller stack = busier day).
 * Tap-only, so it works for Bedrock players too.
 */
public final class StatsMenu {
    private StatsMenu() {
    }

    private static ItemStack icon(ArkaSMPCore plugin, Material m, String title, String value, String... lore) {
        var msg = plugin.messages();
        List<Component> lines = new ArrayList<>();
        lines.add(msg.mm("<white><bold>" + value));
        for (String l : lore) lines.add(msg.mm("<gray>" + l));
        return Items.of(m, msg.mm("<gold>" + title), lines);
    }

    public static void open(ArkaSMPCore plugin, Player viewer) {
        var msg = plugin.messages();
        ServerStats.Snapshot s = plugin.stats().snapshot();
        int mins = plugin.getConfig().getInt("stats.weekly-active-minutes", 60);
        Menu menu = new Menu(msg.mm("<dark_gray>Server statistics"), 5);

        // row 1: players right now and overall
        menu.set(10, icon(plugin, Material.PLAYER_HEAD, "Players ever joined", String.valueOf(s.totalJoined()),
                "Everyone who has ever logged in."), null);
        menu.set(12, icon(plugin, Material.LIME_DYE, "Online now", String.valueOf(s.online()),
                "Players on the server right now."), null);
        menu.set(14, icon(plugin, Material.BLAZE_POWDER, "Peak today", String.valueOf(s.peakToday()),
                "Most players online at once today."), null);
        menu.set(16, icon(plugin, Material.NETHER_STAR, "Record peak", String.valueOf(s.peak()),
                "Most players online at once, ever."), null);

        // row 2: activity
        menu.set(19, icon(plugin, Material.EMERALD, "Weekly active", String.valueOf(s.weeklyActive()),
                "Played more than " + mins + " min in the last 7 days.",
                "Average: " + String.format(Locale.ROOT, "%.1f", s.avgWeekHours()) + " h each"), null);
        menu.set(21, icon(plugin, Material.CLOCK, "Seen in 24 hours", String.valueOf(s.dailyActive()),
                "Logged in during the last day."), null);
        menu.set(23, icon(plugin, Material.COMPASS, "Seen in 30 days", String.valueOf(s.monthlyActive()),
                "Logged in during the last month."), null);
        menu.set(25, icon(plugin, Material.OAK_SAPLING, "New this week", String.valueOf(s.newThisWeek()),
                "First joined in the last 7 days."), null);

        // row 3: loyalty and timing
        menu.set(28, icon(plugin, Material.ENDER_PEARL, "Returning players", String.valueOf(s.returning()),
                "Came back after 7+ days away", "(counted over the last 7 days)."), null);
        String retention = s.retention() < 0 ? "not enough data yet" : String.format(Locale.ROOT, "%.0f%%", s.retention());
        menu.set(30, icon(plugin, s.retention() < 0 ? Material.GRAY_DYE : Material.HEART_OF_THE_SEA, "7-day retention",
                retention, "Of players who joined 7-30 days ago,", "how many were seen a week later.",
                "Sample: " + s.cohort() + " new players"), null);
        menu.set(32, icon(plugin, Material.SUNFLOWER, "Busiest hour", s.busiestHour(),
                "Most players online on average.", "Good time for events."), null);
        menu.set(34, icon(plugin, Material.BLACK_BED, "Quietest hour", s.quietestHour(),
                "Fewest players online on average.", "Best time for a restart."), null);

        // row 4: bar chart of the last 7 daily peaks
        Map<Long, Integer> peaks = plugin.stats().recentPeaks(7);
        long today = System.currentTimeMillis() / 86_400_000L;
        int max = 1, best = -1;
        for (int i = 0; i < 7; i++) {
            int v = peaks.getOrDefault(today - 6 + i, 0);
            if (v > max) max = v;
        }
        for (int i = 0; i < 7; i++) {
            long day = today - 6 + i;
            int v = peaks.getOrDefault(day, 0);
            if (v > 0 && (best < 0 || v >= peaks.getOrDefault(today - 6 + best, 0))) best = i;
        }
        for (int i = 0; i < 7; i++) {
            long day = today - 6 + i;
            int v = peaks.getOrDefault(day, 0);
            String name = LocalDate.ofEpochDay(day).getDayOfWeek().name();
            name = name.charAt(0) + name.substring(1, 3).toLowerCase(Locale.ROOT);
            Material pane = v == 0 ? Material.GRAY_STAINED_GLASS_PANE
                    : i == best ? Material.LIME_STAINED_GLASS_PANE
                    : i == 6 ? Material.YELLOW_STAINED_GLASS_PANE : Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            ItemStack bar = icon(plugin, pane, name + (i == 6 ? " (today)" : ""), v + " players",
                    "Highest number online that day.");
            bar.setAmount(v == 0 ? 1 : Math.max(1, (int) Math.round(v * 64.0 / max)));
            menu.set(37 + i, bar, null);
        }
        menu.set(36, Items.of(Material.SUNFLOWER, msg.mm("<yellow>Refresh"), List.of(msg.mm("<gray>Reload the numbers"))),
                pl -> open(plugin, pl));
        menu.set(44, Items.of(Material.BARRIER, msg.mm("<red>Close"), null), Player::closeInventory);
        menu.fill(Items.filler());
        menu.open(viewer);
    }
}
