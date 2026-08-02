package com.nexusuniverse.seasons;

import org.bukkit.plugin.java.JavaPlugin;

public class SeasonsConfig {

    private final JavaPlugin plugin;

    public SeasonsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        // saveDefaultConfig() only ever writes config.yml the very first time this plugin is
        // installed -- an update that adds new keys (or changes a default, like day-night's
        // below) would otherwise never reach a server that already has a config.yml on disk.
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    public int daysPerSeason() {
        return Math.max(1, plugin.getConfig().getInt("season.days-per-season", 30));
    }

    public int startingYear() {
        return plugin.getConfig().getInt("season.starting-year", 356);
    }

    public String startingSeasonName() {
        return plugin.getConfig().getString("season.starting-season", "SPRING");
    }

    public double plantGrowthMultiplier(Season season) {
        return plugin.getConfig().getDouble("plant-growth." + season.name().toLowerCase(), 1.0);
    }

    public double mobSpawnWeight(Season season, String mobKey) {
        return plugin.getConfig().getDouble("mob-spawns." + season.name().toLowerCase() + "." + mobKey, 1.0);
    }

    public boolean snowEnabled() {
        return plugin.getConfig().getBoolean("visuals.snow-accumulation", true);
    }

    public int snowSweepBlocksPerTick() {
        return plugin.getConfig().getInt("visuals.snow-blocks-per-tick", 64);
    }

    public int sweepIntervalSeconds() {
        return Math.max(1, plugin.getConfig().getInt("visuals.sweep-interval-seconds", 5));
    }

    public boolean transitionMessagesEnabled() {
        return plugin.getConfig().getBoolean("ambiance.transition-enabled", true);
    }

    public boolean ambianceEnabled() {
        return plugin.getConfig().getBoolean("ambiance.enabled", true);
    }

    public int ambianceMinIntervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("ambiance.min-interval-minutes", 8));
    }

    public int ambianceMaxIntervalMinutes() {
        return Math.max(ambianceMinIntervalMinutes(), plugin.getConfig().getInt("ambiance.max-interval-minutes", 20));
    }

    public boolean musicEnabled() {
        return plugin.getConfig().getBoolean("music.enabled", true);
    }

    public int musicTrackLengthSeconds() {
        return Math.max(20, plugin.getConfig().getInt("music.track-length-seconds", 210));
    }

    public boolean customDayNightEnabled() {
        return plugin.getConfig().getBoolean("day-night.enabled", false);
    }

    public int dayLengthMinutes() {
        return Math.max(1, plugin.getConfig().getInt("day-night.day-length-minutes", 360));
    }

    public int nightLengthMinutes() {
        return Math.max(1, plugin.getConfig().getInt("day-night.night-length-minutes", 360));
    }

    public boolean cycleLockEnabled() {
        return plugin.getConfig().getBoolean("cycle-lock.enabled", true);
    }

    public boolean weatherCycleEnabled() {
        return plugin.getConfig().getBoolean("weather.enabled", true);
    }

    public int weatherClearMinMinutes() {
        return Math.max(1, plugin.getConfig().getInt("weather.clear-min-minutes", 20));
    }

    public int weatherClearMaxMinutes() {
        return Math.max(weatherClearMinMinutes(), plugin.getConfig().getInt("weather.clear-max-minutes", 45));
    }

    public int weatherRainMinMinutes() {
        return Math.max(1, plugin.getConfig().getInt("weather.rain-min-minutes", 10));
    }

    public int weatherRainMaxMinutes() {
        return Math.max(weatherRainMinMinutes(), plugin.getConfig().getInt("weather.rain-max-minutes", 25));
    }

    public double weatherThunderChance() {
        return Math.max(0.0, Math.min(1.0, plugin.getConfig().getDouble("weather.thunder-chance", 0.35)));
    }

    /** Persists the toggle immediately -- this is the runtime switch /nexusseasons cyclelock flips, not a config.yml-edit-and-restart setting. */
    public void setCycleLockEnabled(boolean enabled) {
        plugin.getConfig().set("cycle-lock.enabled", enabled);
        plugin.saveConfig();
    }

    public void reload() {
        plugin.reloadConfig();
    }
}
