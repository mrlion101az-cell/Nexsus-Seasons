package com.nexusuniverse.seasons;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;

/**
 * Actively drives weather itself -- the same approach DayNightCycleManager takes for time,
 * applied to storm/thunder: instead of leaving vanilla's own random weather engine in charge
 * (and just trying to stop it from being disabled), this plugin becomes the sole authority over
 * whether it's storming, on its own schedule.
 *
 * Picks a random duration for the current state (clear, or rain with a chance of thunder) in
 * real minutes (config: weather.clear-min/max-minutes, weather.rain-min/max-minutes,
 * weather.thunder-chance), counts it down, and rolls a new state when it expires. Every tick,
 * regardless of whether the countdown just changed anything, it also re-asserts the current
 * state on every managed world -- this is what actually defeats a command (or command block)
 * trying to force a specific weather state: any interference gets silently overwritten on this
 * manager's very next tick, the same way DayNightCycleManager already defeats a stray "/time
 * set" just by being the dominant tick-by-tick writer, not by detecting or reacting to the
 * interference specifically.
 *
 * Only touches Environment.NORMAL worlds, same as DayNightCycleManager and for the same reason --
 * the Nether and the End don't have real weather.
 *
 * Like day-night.enabled, weather.enabled is read once at startup; toggling it needs a restart.
 */
public class WeatherCycleManager implements Listener {

    private final JavaPlugin plugin;
    private final SeasonsConfig config;
    private final Random random = new Random();

    private boolean stormy = false;
    private boolean thundering = false;
    private long ticksUntilTransition;
    private BukkitTask task;

    // an event-driven override (e.g. a hurricane in progress) -- null when nothing is overriding.
    // Lets another manager compel real rain/thunder (or force clear skies) for its own duration
    // without fighting this class's own tick-by-tick reassertion of its randomly-rolled state.
    private Boolean overrideStormy;
    private Boolean overrideThundering;
    private long overrideTicksRemaining;

    public WeatherCycleManager(JavaPlugin plugin, SeasonsConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** Call once from onEnable, only when config.weatherCycleEnabled() is true. */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        rollNextState(); // pick an initial state/duration before the first tick runs

        for (World world : Bukkit.getWorlds()) {
            takeOver(world);
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Picks up worlds that load after startup (e.g. a Multiverse world added later). */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        takeOver(event.getWorld());
    }

    private void takeOver(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        applyState(world);
    }

    private void tick() {
        if (overrideStormy != null) {
            overrideTicksRemaining--;
            if (overrideTicksRemaining <= 0) {
                overrideStormy = null;
                overrideThundering = null;
            }
        }

        ticksUntilTransition--;
        if (ticksUntilTransition <= 0) {
            rollNextState();
        }

        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            applyState(world);
        }
    }

    /** Only writes when the world's actual state doesn't already match -- cheap on the very common tick where nothing needs correcting. */
    private void applyState(World world) {
        boolean effectiveStormy = overrideStormy != null ? overrideStormy : stormy;
        boolean effectiveThundering = overrideThundering != null ? overrideThundering : thundering;
        if (world.hasStorm() != effectiveStormy) world.setStorm(effectiveStormy);
        if (world.isThundering() != effectiveThundering) world.setThundering(effectiveThundering);
    }

    /** Temporarily compels a specific stormy/thundering state for durationTicks -- e.g. a hurricane forcing real rain regardless of whatever this manager's own random cycle currently has rolled. Calling this again just refreshes the override. */
    public void forceState(boolean stormy, boolean thundering, long durationTicks) {
        this.overrideStormy = stormy;
        this.overrideThundering = thundering;
        this.overrideTicksRemaining = durationTicks;
    }

    /** Immediately releases any active forceState() override, handing weather control right back to this manager's own normal rolled cycle -- for when whatever compelled the override (a tornado stopped early, etc.) ends before the duration it was originally given. */
    public void clearOverride() {
        overrideStormy = null;
        overrideThundering = null;
        overrideTicksRemaining = 0;
    }

    private void rollNextState() {
        if (stormy) {
            stormy = false;
            thundering = false;
            int minutes = randomBetween(config.weatherClearMinMinutes(), config.weatherClearMaxMinutes());
            ticksUntilTransition = minutes * 60L * 20L;
        } else {
            stormy = true;
            thundering = random.nextDouble() < config.weatherThunderChance();
            int minutes = randomBetween(config.weatherRainMinMinutes(), config.weatherRainMaxMinutes());
            ticksUntilTransition = minutes * 60L * 20L;
        }
    }

    private int randomBetween(int min, int max) {
        if (max <= min) return Math.max(1, min);
        return min + random.nextInt(max - min + 1);
    }

    /** Restores vanilla's own weather cycle so a world doesn't get stuck frozen in whatever state this plugin left it in, if disabled or removed. */
    public void stop() {
        if (task != null) task.cancel();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, true);
        }
    }
}
