package com.nexusuniverse.seasons;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Random;

public class NexusSeasonsPlugin extends JavaPlugin implements NexusSeasonsAPI {

    private SeasonsConfig config;
    private SeasonClock clock;
    private WorldVisualManager visualManager;
    private SeasonBossBar bossBar;
    private SeasonAmbianceManager ambianceManager;
    private SeasonMusicManager musicManager;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        this.config = new SeasonsConfig(this);
        this.clock = new SeasonClock(this, config);
        this.visualManager = new WorldVisualManager(config);
        this.bossBar = new SeasonBossBar();
        this.ambianceManager = new SeasonAmbianceManager();
        this.musicManager = new SeasonMusicManager(config);

        getServer().getServicesManager().register(NexusSeasonsAPI.class, this, this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(bossBar, this);
        getServer().getPluginManager().registerEvents(musicManager, this);
        getCommand("nexusseasons").setExecutor(new NexusSeasonsCommand(clock, config, this::syncDisplayState, this::advanceOneDay));

        getServer().getPluginManager().registerEvents(new PlantGrowthModifier(clock, config), this);
        getServer().getPluginManager().registerEvents(new SeasonalMobSpawner(clock, config), this);

        refreshBossBar(); // show correct info immediately, don't wait for the first day to tick over

        if (config.musicEnabled()) {
            musicManager.switchToSeason(clock.season()); // start the soundtrack immediately on enable
            scheduleNextTrackRotation();
        }

        // one Minecraft day (24000 ticks) = one season-day
        Bukkit.getScheduler().runTaskTimer(this, this::advanceOneDay, 24000L, 24000L);

        long sweepIntervalTicks = 20L * config.sweepIntervalSeconds();
        Bukkit.getScheduler().runTaskTimer(this, () -> visualManager.tick(clock.season()), sweepIntervalTicks, sweepIntervalTicks);

        if (config.ambianceEnabled()) {
            scheduleNextAmbientLine();
        }

        getLogger().info("NexusSeasons enabled -- Year " + clock.year() + ", " + clock.season().displayName()
                + ", day " + clock.dayOfSeason() + "/" + clock.daysPerSeason() + ".");
    }

    /** Self-rescheduling: picks a fresh random delay after every line so it never falls into a predictable rhythm. */
    private void scheduleNextAmbientLine() {
        int minTicks = 20 * 60 * config.ambianceMinIntervalMinutes();
        int maxTicks = 20 * 60 * config.ambianceMaxIntervalMinutes();
        int delay = minTicks + (maxTicks > minTicks ? random.nextInt(maxTicks - minTicks) : 0);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            ambianceManager.ambientTick(clock.season());
            if (config.ambianceEnabled()) scheduleNextAmbientLine();
        }, delay);
    }

    /** Fixed-interval rotation, same pattern as the ambient line scheduler but on a regular timer instead of randomized. */
    private void scheduleNextTrackRotation() {
        long delayTicks = 20L * config.musicTrackLengthSeconds();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            musicManager.rotateTrack(clock.season());
            if (config.musicEnabled()) scheduleNextTrackRotation();
        }, delayTicks);
    }

    /** Advances the clock by one day, refreshes the boss bar, and broadcasts an announcement if that crossed into a new season. */
    private void advanceOneDay() {
        Season before = clock.season();
        int yearBefore = clock.year();

        clock.advanceDay();

        if (clock.season() != before) {
            if (config.transitionMessagesEnabled()) {
                ambianceManager.announceSeasonChange(clock.season(), clock.year(), clock.year() != yearBefore);
            }
            if (config.musicEnabled()) {
                musicManager.switchToSeason(clock.season()); // don't wait for the current track's rotation timer
            }
        }
        refreshBossBar();
    }

    /** Used after a silent admin edit (setseason/setday/setyear) -- updates the boss bar and matches the music to the current season, no fanfare banner. */
    private void syncDisplayState() {
        refreshBossBar();
        if (config.musicEnabled()) {
            musicManager.switchToSeason(clock.season());
        }
    }

    private void refreshBossBar() {
        bossBar.update(clock.season(), clock.year(), clock.dayOfSeason(), clock.daysPerSeason());
    }

    @Override
    public void onDisable() {
        if (clock != null) clock.save();
        if (bossBar != null) bossBar.removeAll();
        if (musicManager != null) musicManager.stopAll();
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public Season getCurrentSeason() {
        return clock.season();
    }

    @Override
    public int getCurrentYear() {
        return clock.year();
    }

    @Override
    public int getDayOfSeason() {
        return clock.dayOfSeason();
    }

    @Override
    public int getDaysPerSeason() {
        return clock.daysPerSeason();
    }
}
