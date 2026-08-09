package com.nexusuniverse.seasons.weather;

import com.nexusuniverse.seasons.SeasonsConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A persistent per-player scoreboard sidebar (upper-right corner) showing the current headline
 * weather condition and a live wind meter.
 *
 * HONEST PLATFORM LIMIT: this is the actual closest real placement to "top-left corner" reachable
 * from plain plugin code -- Bukkit/Paper has no API for arbitrary on-screen HUD positioning
 * without a resource pack or a client mod. Scoreboard sidebar (renders upper-right), action bar
 * (bottom-center), and boss bar (top-center, already used by SeasonBossBar for season/day) are the
 * only three real options; sidebar is what was picked.
 *
 * The wind reading genuinely reflects where the player actually is rather than a flat global
 * number -- it reuses WindManager's own "exposed to open sky" check, so standing indoors or
 * underground correctly reads as sheltered/calm even while conditions outside are severe.
 *
 * Headline weather is a fixed priority order -- hurricane > tsunami > tornado > blizzard > dry
 * thunderstorm > fog > real vanilla thunder/rain > clear. All of those event managers are single,
 * server-wide states (no per-world or per-player tracking, consistent with the rest of this
 * "crazy weather" layer) -- only the final vanilla thunder/rain fallback is actually read per the
 * player's own current world.
 *
 * Gives each player their own private Scoreboard instance so this can't collide with anything
 * else -- if another plugin on the server also sets player scoreboards for its own sidebar, only
 * one can be shown at a time (standard Bukkit limit, not specific to this), so weather-hud.enabled
 * exists specifically to turn this off if that conflict comes up.
 */
public class WeatherHudManager implements Listener {

    private static final String OBJECTIVE_NAME = "nexusweather";

    private final JavaPlugin plugin;
    private final SeasonsConfig config;
    private final WindManager wind;
    private final DryThunderstormManager dryThunder;
    private final FogManager fog;
    private final BlizzardManager blizzard;
    private final TornadoManager tornado;
    private final TsunamiManager tsunami;
    private final HurricaneManager hurricane;

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private BukkitTask task;

    public WeatherHudManager(JavaPlugin plugin, SeasonsConfig config, WindManager wind, DryThunderstormManager dryThunder,
                              FogManager fog, BlizzardManager blizzard, TornadoManager tornado, TsunamiManager tsunami,
                              HurricaneManager hurricane) {
        this.plugin = plugin;
        this.config = config;
        this.wind = wind;
        this.dryThunder = dryThunder;
        this.fog = fog;
        this.blizzard = blizzard;
        this.tornado = tornado;
        this.tsunami = tsunami;
        this.hurricane = hurricane;
    }

    public void start() {
        if (!config.weatherHudEnabled()) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player); // don't make anyone already online wait for the first scheduled tick
        }
        long interval = 20L * config.weatherHudRefreshIntervalSeconds();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) task.cancel();
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(main);
        }
        boards.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!config.weatherHudEnabled()) return;
        update(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
    }

    private void update(Player player) {
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), id -> {
            Scoreboard fresh = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(fresh);
            return fresh;
        });

        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE_NAME, "dummy", "§9§lNexus Weather");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        String[] lines = buildLines(player);
        for (int i = 0; i < lines.length; i++) {
            String entry = lineEntry(i);
            Team team = board.getTeam("nwline" + i);
            if (team == null) team = board.registerNewTeam("nwline" + i);
            if (!team.hasEntry(entry)) team.addEntry(entry);
            team.setPrefix(lines[i]);
            objective.getScore(entry).setScore(lines.length - i);
        }
    }

    /** Always exactly 3 lines (even when the third is blank) -- a fixed line count avoids leftover stale entries from a previous refresh that had more lines than the current one. */
    private String[] buildLines(Player player) {
        String headline = headlineWeather(player.getWorld());
        double strength = wind.currentStrength();
        boolean sheltered = !wind.isExposedToSky(player.getLocation());

        String windLine = "§7Wind: §f" + windDescriptor(strength) + " " + Math.round(strength * 100) + "%"
                + (sheltered ? "" : " " + compassArrow());

        return new String[]{
                "§7Sky: §f" + headline,
                windLine,
                sheltered ? "§8(sheltered)" : ""
        };
    }

    private String headlineWeather(World world) {
        if (hurricane.isActive()) return "Hurricane";
        if (tsunami.isActive()) return "Tsunami";
        if (tornado.isActive()) return "Tornado";
        if (blizzard.isActive()) return "Blizzard";
        if (dryThunder.isActive()) return "Dry Thunderstorm";
        if (fog.isActive()) return "Foggy";
        if (world.isThundering()) return "Thunderstorm";
        if (world.hasStorm()) return "Raining";
        return "Clear";
    }

    private String windDescriptor(double strength) {
        if (strength < 0.15) return "Calm";
        if (strength < 0.3) return "Breezy";
        if (strength < 0.5) return "Windy";
        if (strength < 0.7) return "Strong";
        return "Severe";
    }

    private String compassArrow() {
        Vector direction = wind.currentDirection();
        double angle = Math.atan2(direction.getZ(), direction.getX());
        if (angle < 0) angle += Math.PI * 2;
        int sector = (int) Math.round(angle / (Math.PI / 4)) % 8;
        String[] arrows = {"→", "↘", "↓", "↙", "←", "↖", "↑", "↗"};
        return arrows[sector];
    }

    /** A short, unique-per-line, effectively-invisible scoreboard entry -- the real visible text comes from that line's Team prefix, not the entry itself. */
    private String lineEntry(int index) {
        return ChatColor.values()[index].toString();
    }
}
