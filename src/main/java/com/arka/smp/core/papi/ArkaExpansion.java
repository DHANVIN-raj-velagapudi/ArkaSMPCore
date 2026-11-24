package com.arka.smp.core.papi;

import com.arka.smp.core.ArkaSMPCore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

/**
 * PlaceholderAPI placeholders (usable in TAB, scoreboards, chat...):
 * %arka_claimblocks%        claim blocks still available
 * %arka_claimblocks_total%  all claim blocks the player owns
 * %arka_claimblocks_used%   blocks used by claims
 * %arka_claims%             number of claims
 * %arka_homes%              homes set
 * %arka_homes_limit%        homes allowed
 * %arka_playhours%          hours played
 * %arka_blocks_per_hour%    claim blocks earned per hour
 * %arka_afk%                the AFK tag while AFK, empty otherwise
 * %arka_week_hours%         your active play time in the last 7 days
 *
 * Server statistics (same for everyone):
 * %arka_stats_total%        players who ever joined
 * %arka_stats_weekly%       players with more than 1 hour of play in the last 7 days
 * %arka_stats_daily%        players seen in the last 24 hours
 * %arka_stats_monthly%      players seen in the last 30 days
 * %arka_stats_new_week%     players who first joined in the last 7 days
 * %arka_stats_online%       players online now
 * %arka_stats_returning%    players who came back after 7+ days away, in the last 7 days
 * %arka_stats_retention%    % of players who joined 7-30 days ago and were seen again a week later
 * %arka_stats_peak_today%   most players online at once today
 * %arka_stats_busiest_hour% e.g. 20:00-21:00 (busiest by average players online)
 * %arka_stats_quietest_hour% best time for a restart
 * %arka_stats_peak%         most players ever online at once
 * %arka_stats_week_hours%   hours played by everyone in the last 7 days
 * %arka_stats_avg_week_hours%  average hours per weekly active player
 *
 * Leaderboards (category = kills, deaths, money, playtime; n = 1..N):
 * %arka_top_<category>_<n>_name%   name of the player at rank n
 * %arka_top_<category>_<n>_value%  formatted value (e.g. $1,250 or 3d 4h)
 * %arka_top_<category>_<n>_raw%    plain number
 * %arka_top_position_<category>%   the VIEWER's own rank number
 * %arka_top_value_<category>%      the VIEWER's own value
 */
public final class ArkaExpansion extends PlaceholderExpansion {
    private final ArkaSMPCore plugin;

    public ArkaExpansion(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "arka";
    }

    @Override
    public String getAuthor() {
        return "Arka";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        try {
            return compute(player, params);
        } catch (RuntimeException e) {
            return "";   // read from other threads (TAB): never throw into the caller
        }
    }

    private String compute(OfflinePlayer player, String params) {
        if (params.startsWith("stats_")) return plugin.stats().placeholder(params.toLowerCase(Locale.ROOT).substring(6));
        if (player == null) return "";
        if (params.startsWith("top_")) return plugin.leaderboards().placeholder(player, params);
        UUID id = player.getUniqueId();
        var players = plugin.players();
        switch (params.toLowerCase(Locale.ROOT)) {
            case "afk":
                return plugin.afk().tag(id);
            case "claimblocks":
                return String.valueOf(players.remaining(id));
            case "claimblocks_total":
                return String.valueOf(players.total(id));
            case "claimblocks_used":
                return String.valueOf(players.used(id));
            case "claims":
                return String.valueOf(plugin.claims().count(id));
            case "homes":
                return String.valueOf(plugin.homes().names(id).size());
            case "homes_limit":
                return String.valueOf(plugin.homes().cachedLimit(id));
            case "playhours":
                return String.format(Locale.ROOT, "%.1f", players.playTicks(id) / 72000.0);
            case "week_hours":
                return String.format(Locale.ROOT, "%.1f", plugin.stats().weekHours(id));
            case "blocks_per_hour":
                return String.valueOf(plugin.getConfig().getInt("claims.blocks-per-hour", 100));
            default:
                return null;
        }
    }
}
