package com.nexusuniverse.seasons;

import com.nexusuniverse.seasons.weather.BlizzardManager;
import com.nexusuniverse.seasons.weather.CoastalWaveManager;
import com.nexusuniverse.seasons.weather.DryThunderstormManager;
import com.nexusuniverse.seasons.weather.FogManager;
import com.nexusuniverse.seasons.weather.HurricaneManager;
import com.nexusuniverse.seasons.weather.TornadoManager;
import com.nexusuniverse.seasons.weather.TsunamiManager;
import com.nexusuniverse.seasons.weather.WaveManager;
import com.nexusuniverse.seasons.weather.WaveTrainManager;
import com.nexusuniverse.seasons.weather.WeatherHudManager;
import com.nexusuniverse.seasons.weather.WindManager;
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
    private DayNightCycleManager dayNightCycle;
    private WeatherCycleManager weatherCycle;
    private CycleLockManager cycleLock;
    private WindManager wind;
    private DryThunderstormManager dryThunder;
    private FogManager fog;
    private TornadoManager tornado;
    private BlizzardManager blizzard;
    private WaveManager wave;
    private CoastalWaveManager coastalWave;
    private TsunamiManager tsunami;
    private HurricaneManager hurricane;
    private WaveTrainManager waveTrain;
    private WeatherHudManager weatherHud;
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

        if (config.weatherCycleEnabled()) {
            weatherCycle = new WeatherCycleManager(this, config);
            weatherCycle.start();
        }

        // "crazy weather" layer -- wind, dry thunderstorms, fog, tornadoes, blizzards, ocean
        // waves, tsunamis, and hurricanes. Each is independently toggleable and fully
        // config-driven; tornado/blizzard/wave all take the WindManager reference, and
        // hurricane additionally takes weatherCycle (created just above -- may be null if
        // weather.enabled is off, which HurricaneManager tolerates and handles itself), so
        // everything stays visually/mechanically consistent rather than being unrelated
        // systems. Created before the command below since the command needs live references
        // to control several of these directly.
        this.wind = new WindManager(this, config);
        wind.start();
        this.dryThunder = new DryThunderstormManager(this, config);
        dryThunder.start();
        this.fog = new FogManager(this, config);
        fog.start();
        this.tornado = new TornadoManager(this, config, wind);
        tornado.start();
        this.blizzard = new BlizzardManager(this, config, wind);
        blizzard.start();
        this.wave = new WaveManager(this, config, wind);
        wave.start();
        this.coastalWave = new CoastalWaveManager(this, config, wind);
        coastalWave.start();
        this.tsunami = new TsunamiManager(this, config);
        tsunami.start();
        this.hurricane = new HurricaneManager(this, config, wind, weatherCycle);
        hurricane.start();
        this.waveTrain = new WaveTrainManager(this, config, wind, tsunami, hurricane);
        waveTrain.start();
        this.weatherHud = new WeatherHudManager(this, config, wind, dryThunder, fog, blizzard, tornado, tsunami, hurricane);
        weatherHud.start();

        getServer().getPluginManager().registerEvents(bossBar, this);
        getServer().getPluginManager().registerEvents(musicManager, this);
        getCommand("nexusseasons").setExecutor(new NexusSeasonsCommand(clock, config, this::syncDisplayState, this::advanceOneDay,
                dryThunder, fog, tornado, blizzard, tsunami, hurricane, waveTrain));

        getServer().getPluginManager().registerEvents(new PlantGrowthModifier(clock, config), this);
        getServer().getPluginManager().registerEvents(new SeasonalMobSpawner(clock, config), this);

        refreshBossBar(); // show correct info immediately, don't wait for the first day to tick over

        if (config.musicEnabled()) {
            musicManager.switchToSeason(clock.season()); // start the soundtrack immediately on enable
            scheduleNextTrackRotation();
        }

        if (config.customDayNightEnabled()) {
            // custom 12h-day/12h-night cycle drives world time itself, and
            // signals advanceOneDay when a full cycle completes
            dayNightCycle = new DayNightCycleManager(this, config, this::advanceOneDay);
            dayNightCycle.start();
        } else {
            // one Minecraft day (24000 ticks) = one season-day
            Bukkit.getScheduler().runTaskTimer(this, this::advanceOneDay, 24000L, 24000L);
        }

        // independent of which day/night/weather mode is active above -- also protects vanilla's
        // own cycles when either is off, not just the custom ones
        this.cycleLock = new CycleLockManager(this, config);
        cycleLock.start();
        getServer().getPluginManager().registerEvents(new CycleLockGuard(config), this);

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
        if (dayNightCycle != null) dayNightCycle.stop();
        if (cycleLock != null) cycleLock.stop();
        if (weatherHud != null) weatherHud.stop();
        if (waveTrain != null) waveTrain.stop();
        if (hurricane != null) hurricane.stop();
        if (tsunami != null) tsunami.stop();
        if (coastalWave != null) coastalWave.stop();
        if (wave != null) wave.stop();
        if (wind != null) wind.stop();
        if (dryThunder != null) dryThunder.stop();
        if (fog != null) fog.stop();
        if (tornado != null) tornado.stop();
        if (blizzard != null) blizzard.stop();
        if (weatherCycle != null) weatherCycle.stop();
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
