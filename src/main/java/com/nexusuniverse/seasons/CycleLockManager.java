package com.nexusuniverse.seasons;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Continuously re-asserts the day/night and weather gamerules NexusSeasons needs, so a
 * /gamerule command -- run by anyone with permission, on purpose or by accident -- can't disable
 * the day/night or weather cycle out from under this plugin. This exists specifically so
 * "someone flips a gamerule" isn't a way around the cycle running; the only sanctioned way to
 * change that is /nexusseasons cyclelock off, which this class checks every pass and backs off
 * completely the moment it's set.
 *
 * What "locked" enforces, per Environment.NORMAL world:
 *  - DO_DAYLIGHT_CYCLE: false if day-night.enabled (DayNightCycleManager owns time advancement
 *    itself at that point, and vanilla's own per-tick increment would otherwise fight it), or
 *    true if day-night.enabled is off (so vanilla's own day/night keeps running naturally
 *    instead of getting stuck frozen wherever it was when doDaylightCycle got set to false).
 *  - DO_WEATHER_CYCLE: always true. NexusSeasons doesn't drive weather itself -- this just makes
 *    sure vanilla's own natural weather cycling can't be switched off and left permanently
 *    stuck on whatever it happened to be at the time.
 *
 * Runs on a 1-second interval rather than every tick: checks the current gamerule value first
 * and only calls setGameRule when it's actually different from what's wanted, so in the (very
 * common) case where nobody's touched a gamerule recently, each pass is close to free -- a
 * couple of reads per world, no writes.
 */
public class CycleLockManager {

    private final JavaPlugin plugin;
    private final SeasonsConfig config;
    private BukkitTask task;

    public CycleLockManager(JavaPlugin plugin, SeasonsConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::enforce, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void enforce() {
        if (!config.cycleLockEnabled()) return;

        boolean desiredDaylightCycle = !config.customDayNightEnabled();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;

            Boolean daylight = world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
            if (daylight == null || daylight != desiredDaylightCycle) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, desiredDaylightCycle);
            }

            Boolean weather = world.getGameRuleValue(GameRule.DO_WEATHER_CYCLE);
            if (weather == null || !weather) {
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, true);
            }
        }
    }
}
