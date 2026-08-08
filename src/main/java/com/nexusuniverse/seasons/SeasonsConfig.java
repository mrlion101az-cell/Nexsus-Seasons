package com.nexusuniverse.seasons;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

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

    // --- wind (continuous ambient) ---

    public boolean windEnabled() {
        return plugin.getConfig().getBoolean("wind.enabled", true);
    }

    public double windMinStrength() {
        return clamp01(plugin.getConfig().getDouble("wind.min-strength", 0.05));
    }

    public double windMaxStrength() {
        return clamp01(plugin.getConfig().getDouble("wind.max-strength", 0.5));
    }

    public int windChangeIntervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("wind.change-interval-minutes", 10));
    }

    public boolean windPushPlayers() {
        return plugin.getConfig().getBoolean("wind.push-players", true);
    }

    public double windPlayerPushMinStrength() {
        return clamp01(plugin.getConfig().getDouble("wind.player-push-min-strength", 0.3));
    }

    public double windPlayerPushMultiplier() {
        return plugin.getConfig().getDouble("wind.player-push-multiplier", 0.15);
    }

    public double windSevereThreshold() {
        return clamp01(plugin.getConfig().getDouble("wind.severe-threshold", 0.7));
    }

    public double windDislodgeChancePerTick() {
        return Math.max(0.0, plugin.getConfig().getDouble("wind.dislodge-chance-per-tick", 0.02));
    }

    public int windDislodgeSearchRadius() {
        return Math.max(1, plugin.getConfig().getInt("wind.dislodge-search-radius", 12));
    }

    public List<Material> windFragileMaterials() {
        return parseMaterialList("wind.fragile-materials", DEFAULT_FRAGILE_MATERIALS);
    }

    // --- wind gusts (short, sharper direction/strength bursts layered on the steady drift) ---

    public boolean windGustEnabled() {
        return plugin.getConfig().getBoolean("wind.gust.enabled", true);
    }

    public int windGustCheckIntervalSeconds() {
        return Math.max(1, plugin.getConfig().getInt("wind.gust.check-interval-seconds", 15));
    }

    public double windGustChance() {
        return Math.max(0.0, plugin.getConfig().getDouble("wind.gust.chance", 0.3));
    }

    public int windGustDurationMinSeconds() {
        return Math.max(1, plugin.getConfig().getInt("wind.gust.duration-min-seconds", 3));
    }

    public int windGustDurationMaxSeconds() {
        return Math.max(windGustDurationMinSeconds(), plugin.getConfig().getInt("wind.gust.duration-max-seconds", 8));
    }

    /** How far off the steady wind direction a gust can swing, in degrees either side -- 180 allows a near-total reversal. */
    public double windGustDirectionSwingDegrees() {
        return Math.max(0, Math.min(180, plugin.getConfig().getDouble("wind.gust.direction-swing-degrees", 120)));
    }

    /** How much stronger than the current steady wind a gust gets, before the 1.0 hard cap. */
    public double windGustStrengthMultiplier() {
        return Math.max(1.0, plugin.getConfig().getDouble("wind.gust.strength-multiplier", 1.8));
    }

    // --- dry thunderstorm (lightning + thunder, no rain) ---

    public boolean dryThunderEnabled() {
        return plugin.getConfig().getBoolean("dry-thunder.enabled", true);
    }

    public double dryThunderNaturalChance() {
        return Math.max(0.0, plugin.getConfig().getDouble("dry-thunder.natural-chance", 0.15));
    }

    public int dryThunderCheckIntervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("dry-thunder.check-interval-minutes", 20));
    }

    public int dryThunderDurationMinSeconds() {
        return Math.max(1, plugin.getConfig().getInt("dry-thunder.duration-min-seconds", 60));
    }

    public int dryThunderDurationMaxSeconds() {
        return Math.max(dryThunderDurationMinSeconds(), plugin.getConfig().getInt("dry-thunder.duration-max-seconds", 180));
    }

    public int dryThunderStrikeMinIntervalSeconds() {
        return Math.max(1, plugin.getConfig().getInt("dry-thunder.strike-min-interval-seconds", 5));
    }

    public int dryThunderStrikeMaxIntervalSeconds() {
        return Math.max(dryThunderStrikeMinIntervalSeconds(), plugin.getConfig().getInt("dry-thunder.strike-max-interval-seconds", 20));
    }

    public int dryThunderStrikeRadius() {
        return Math.max(1, plugin.getConfig().getInt("dry-thunder.strike-radius", 40));
    }

    public boolean dryThunderPlaySound() {
        return plugin.getConfig().getBoolean("dry-thunder.play-sound", true);
    }

    // --- fog (particle-based, see FogManager's doc comment for the honest limits) ---

    public boolean fogEnabled() {
        return plugin.getConfig().getBoolean("fog.enabled", true);
    }

    public double fogNaturalChance() {
        return Math.max(0.0, plugin.getConfig().getDouble("fog.natural-chance", 0.15));
    }

    public int fogCheckIntervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("fog.check-interval-minutes", 25));
    }

    public int fogDurationMinSeconds() {
        return Math.max(1, plugin.getConfig().getInt("fog.duration-min-seconds", 60));
    }

    public int fogDurationMaxSeconds() {
        return Math.max(fogDurationMinSeconds(), plugin.getConfig().getInt("fog.duration-max-seconds", 240));
    }

    public double fogRadius() {
        return Math.max(1, plugin.getConfig().getDouble("fog.radius", 6.0));
    }

    public int fogDensity() {
        return Math.max(1, plugin.getConfig().getInt("fog.density", 25));
    }

    public Color fogColor() {
        return parseColor("fog.color", 220, 220, 225);
    }

    // --- tornado ---

    public boolean tornadoEnabled() {
        return plugin.getConfig().getBoolean("tornado.enabled", true);
    }

    public double tornadoNaturalChance() {
        return Math.max(0.0, plugin.getConfig().getDouble("tornado.natural-chance", 0.05));
    }

    public int tornadoCheckIntervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("tornado.check-interval-minutes", 30));
    }

    public int tornadoDurationMinSeconds() {
        return Math.max(1, plugin.getConfig().getInt("tornado.duration-min-seconds", 30));
    }

    public int tornadoDurationMaxSeconds() {
        return Math.max(tornadoDurationMinSeconds(), plugin.getConfig().getInt("tornado.duration-max-seconds", 90));
    }

    public double tornadoRadius() {
        return Math.max(1, plugin.getConfig().getDouble("tornado.radius", 6.0));
    }

    public int tornadoHeight() {
        return Math.max(2, plugin.getConfig().getInt("tornado.height", 30));
    }

    public double tornadoMoveSpeed() {
        return Math.max(0.0, plugin.getConfig().getDouble("tornado.move-speed", 0.15));
    }

    public double tornadoSpinSpeed() {
        return plugin.getConfig().getDouble("tornado.spin-speed", 0.35);
    }

    public double tornadoPullStrength() {
        return plugin.getConfig().getDouble("tornado.pull-strength", 0.35);
    }

    public double tornadoLiftStrength() {
        return plugin.getConfig().getDouble("tornado.lift-strength", 0.3);
    }

    public double tornadoSwirlStrength() {
        return plugin.getConfig().getDouble("tornado.swirl-strength", 0.4);
    }

    public double tornadoMaxVelocityPerTick() {
        return Math.max(0.1, plugin.getConfig().getDouble("tornado.max-velocity-per-tick", 1.2));
    }

    public boolean tornadoDestroyFragileBlocks() {
        return plugin.getConfig().getBoolean("tornado.destroy-fragile-blocks", true);
    }

    public int tornadoBlocksPerTick() {
        return Math.max(0, plugin.getConfig().getInt("tornado.blocks-per-tick", 2));
    }

    // --- blizzard ---

    public boolean blizzardEnabled() {
        return plugin.getConfig().getBoolean("blizzard.enabled", true);
    }

    public double blizzardNaturalChance() {
        return Math.max(0.0, plugin.getConfig().getDouble("blizzard.natural-chance", 0.1));
    }

    public int blizzardCheckIntervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("blizzard.check-interval-minutes", 25));
    }

    public int blizzardDurationMinSeconds() {
        return Math.max(1, plugin.getConfig().getInt("blizzard.duration-min-seconds", 90));
    }

    public int blizzardDurationMaxSeconds() {
        return Math.max(blizzardDurationMinSeconds(), plugin.getConfig().getInt("blizzard.duration-max-seconds", 300));
    }

    public double blizzardRadius() {
        return Math.max(1, plugin.getConfig().getDouble("blizzard.radius", 8.0));
    }

    public int blizzardDensity() {
        return Math.max(1, plugin.getConfig().getInt("blizzard.density", 20));
    }

    public boolean blizzardForceWind() {
        return plugin.getConfig().getBoolean("blizzard.force-wind", true);
    }

    public boolean blizzardApplySlowness() {
        return plugin.getConfig().getBoolean("blizzard.apply-slowness", true);
    }

    public int blizzardSlownessAmplifier() {
        return Math.max(0, plugin.getConfig().getInt("blizzard.slowness-amplifier", 0));
    }

    // --- waves (continuous ambient, near open ocean) ---

    public boolean wavesEnabled() {
        return plugin.getConfig().getBoolean("waves.enabled", true);
    }

    public double wavesBaseAmplitude() {
        return plugin.getConfig().getDouble("waves.base-amplitude", 0.15);
    }

    public double wavesWindAmplitudeMultiplier() {
        return plugin.getConfig().getDouble("waves.wind-amplitude-multiplier", 0.6);
    }

    public double wavesBaseFrequency() {
        return plugin.getConfig().getDouble("waves.base-frequency", 0.02);
    }

    public double wavesWindFrequencyMultiplier() {
        return plugin.getConfig().getDouble("waves.wind-frequency-multiplier", 0.03);
    }

    public boolean wavesPushSwimmers() {
        return plugin.getConfig().getBoolean("waves.push-swimmers", true);
    }

    public double wavesPushMinWindStrength() {
        return clamp01(plugin.getConfig().getDouble("waves.push-min-wind-strength", 0.3));
    }

    public double wavesPushMultiplier() {
        return plugin.getConfig().getDouble("waves.push-multiplier", 0.1);
    }

    // --- shared water-body detection (used by both ambient waves and shore-break) ---

    /** Minimum X/Z span, in blocks, for a body of water to qualify for waves at all -- a lake this size or larger counts the same as the ocean; anything smaller (ponds, moats, decorative water) doesn't. */
    public int wavesMinBodySize() {
        return Math.max(1, plugin.getConfig().getInt("waves.min-body-size", 40));
    }

    /** Hard cap on how many blocks the flood-fill body-size check will visit before giving up -- bounds worst-case cost for a small, oddly-shaped body that never reaches min-body-size. */
    public int wavesBodyDetectionMaxBlocks() {
        return Math.max(wavesMinBodySize() * wavesMinBodySize(), plugin.getConfig().getInt("waves.body-detection-max-blocks", 4000));
    }

    /** Small radius used to cheaply rule out "not near any water at all" before ever attempting the expensive flood-fill size check. */
    public int wavesWaterSearchRadius() {
        return Math.max(1, plugin.getConfig().getInt("waves.water-search-radius", 5));
    }

    /** How long (real seconds) a player's ambient-wave eligibility (are they in a large-enough body of water) is cached before being recomputed -- the flood-fill check is too expensive to run on every tick for every player. */
    public int wavesEligibilityCacheSeconds() {
        return Math.max(1, plugin.getConfig().getInt("waves.eligibility-cache-seconds", 8));
    }

    // --- shore-break waves (continuous recurring real coastal surges, using CoastalFloodEngine) ---

    public boolean shoreBreakEnabled() {
        return plugin.getConfig().getBoolean("waves.shore-break.enabled", true);
    }

    public int shoreBreakCheckIntervalSeconds() {
        return Math.max(1, plugin.getConfig().getInt("waves.shore-break.check-interval-seconds", 6));
    }

    public int shoreBreakMaxConcurrent() {
        return Math.max(1, plugin.getConfig().getInt("waves.shore-break.max-concurrent", 3));
    }

    public int shoreBreakCoastSearchRadius() {
        return Math.max(4, plugin.getConfig().getInt("waves.shore-break.coast-search-radius", 60));
    }

    /** Wave height at calm wind (0.0 strength) -- scales up to shoreBreakMaxHeight() as wind strength approaches 1.0. */
    public double shoreBreakMinHeight() {
        return Math.max(0, plugin.getConfig().getDouble("waves.shore-break.min-height", 1.0));
    }

    /** Wave height at full (1.0) wind strength -- the "waves up to ten blocks tall" ceiling. */
    public double shoreBreakMaxHeight() {
        return Math.max(shoreBreakMinHeight(), plugin.getConfig().getDouble("waves.shore-break.max-height", 10.0));
    }

    public double shoreBreakMaxInlandDistance() {
        return Math.max(1, plugin.getConfig().getDouble("waves.shore-break.max-inland-distance", 14.0));
    }

    /** Deliberately much slower than tsunami/hurricane's own advance speeds -- this is what actually makes a shore-break wave read as something visibly moving in over a couple of seconds, rather than water just appearing almost instantly. */
    public double shoreBreakAdvanceSpeed() {
        return Math.max(0.05, plugin.getConfig().getDouble("waves.shore-break.advance-speed", 0.35));
    }

    public double shoreBreakFrontWidth() {
        return Math.max(1, plugin.getConfig().getDouble("waves.shore-break.front-width", 24.0));
    }

    public double shoreBreakKnockbackStrength() {
        return plugin.getConfig().getDouble("waves.shore-break.knockback-strength", 0.9);
    }

    public int shoreBreakMaxAffectedBlocks() {
        return Math.max(50, plugin.getConfig().getInt("waves.shore-break.max-affected-blocks", 800));
    }

    /** How long the drain-back-out phase visibly takes, regardless of how many blocks actually got flooded -- a fixed, predictable pace instead of the old size-proportional one that made a modest flood drain almost instantly. */
    public int shoreBreakRecedeDurationSeconds() {
        return Math.max(1, plugin.getConfig().getInt("waves.shore-break.recede-duration-seconds", 4));
    }

    /** How long, after a wave fully finishes, that same spot stays off-limits to a new one -- the real "goes back to normal for a while" gap between waves. */
    public int shoreBreakCooldownSeconds() {
        return Math.max(0, plugin.getConfig().getInt("waves.shore-break.cooldown-seconds", 20));
    }

    /** How far apart (in blocks) two waves -- or a new wave and a spot still on cooldown -- have to be, squared for the cheap distanceSquared() comparisons used everywhere this is checked. */
    public double shoreBreakSpacingRadiusSquared() {
        double radius = Math.max(1, plugin.getConfig().getDouble("waves.shore-break.spacing-radius", 80));
        return radius * radius;
    }

    // --- wave train (rows of raised water continuously scrolling across the open surface) ---

    public boolean waveTrainEnabled() {
        return plugin.getConfig().getBoolean("waves.wave-train.enabled", true);
    }

    /** How often (real ticks) the pattern actually recomputes/repaints -- the wave's travel speed is independent of this, since phase accumulates in real-tick units regardless of how often it's recalculated. */
    public int waveTrainTickInterval() {
        return Math.max(1, plugin.getConfig().getInt("waves.wave-train.tick-interval", 2));
    }

    /** How many blocks wide (along the direction of travel) one ridge is. */
    public int waveTrainRidgeWidth() {
        return Math.max(1, plugin.getConfig().getInt("waves.wave-train.ridge-width", 3));
    }

    /** How many blocks of flat water separate one ridge from the next. */
    public int waveTrainGapWidth() {
        return Math.max(1, plugin.getConfig().getInt("waves.wave-train.gap-width", 5));
    }

    public int waveTrainMinHeight() {
        return Math.max(1, plugin.getConfig().getInt("waves.wave-train.min-height", 1));
    }

    public int waveTrainMaxHeight() {
        return Math.max(waveTrainMinHeight(), plugin.getConfig().getInt("waves.wave-train.max-height", 2));
    }

    /** Blocks per real tick the whole pattern travels -- deliberately slow so it reads as rolling swell, not a flicker. */
    public double waveTrainSpeed() {
        return Math.max(0.01, plugin.getConfig().getDouble("waves.wave-train.speed", 0.15));
    }

    /** How wide (blocks, perpendicular to travel direction) the visible field is around each eligible player. */
    public int waveTrainSpan() {
        return Math.max(2, plugin.getConfig().getInt("waves.wave-train.span", 24));
    }

    /** How far (blocks, along the travel direction, both ahead of and behind the player) the visible field extends. */
    public int waveTrainReach() {
        return Math.max(1, plugin.getConfig().getInt("waves.wave-train.reach", 20));
    }

    /** Hard cap on how many columns get touched per player per pass -- bounds the cost of a wide/deep field. */
    public int waveTrainBlocksPerTick() {
        return Math.max(10, plugin.getConfig().getInt("waves.wave-train.blocks-per-tick", 150));
    }

    // --- tsunami (one-off event, real warning before it hits) ---

    public boolean tsunamiEnabled() {
        return plugin.getConfig().getBoolean("tsunami.enabled", true);
    }

    public double tsunamiNaturalChance() {
        return Math.max(0.0, plugin.getConfig().getDouble("tsunami.natural-chance", 0.02));
    }

    public int tsunamiCheckIntervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("tsunami.check-interval-minutes", 45));
    }

    public int tsunamiCoastSearchRadius() {
        return Math.max(4, plugin.getConfig().getInt("tsunami.coast-search-radius", 60));
    }

    public int tsunamiWarningSeconds() {
        return Math.max(0, plugin.getConfig().getInt("tsunami.warning-seconds", 15));
    }

    public double tsunamiMaxInlandDistance() {
        return Math.max(1, plugin.getConfig().getDouble("tsunami.max-inland-distance", 40.0));
    }

    public double tsunamiAdvanceSpeed() {
        return Math.max(0.1, plugin.getConfig().getDouble("tsunami.advance-speed", 1.2));
    }

    public double tsunamiFrontWidth() {
        return Math.max(1, plugin.getConfig().getDouble("tsunami.front-width", 40.0));
    }

    public double tsunamiWaveHeight() {
        return Math.max(1, plugin.getConfig().getDouble("tsunami.wave-height", 6.0));
    }

    public double tsunamiKnockbackStrength() {
        return plugin.getConfig().getDouble("tsunami.knockback-strength", 1.4);
    }

    public int tsunamiMaxAffectedBlocks() {
        return Math.max(100, plugin.getConfig().getInt("tsunami.max-affected-blocks", 6000));
    }

    // --- hurricane (orchestrated: wind + real forced rain/thunder + periodic storm surge) ---

    public boolean hurricaneEnabled() {
        return plugin.getConfig().getBoolean("hurricane.enabled", true);
    }

    public double hurricaneNaturalChance() {
        return Math.max(0.0, plugin.getConfig().getDouble("hurricane.natural-chance", 0.03));
    }

    public int hurricaneCheckIntervalMinutes() {
        return Math.max(1, plugin.getConfig().getInt("hurricane.check-interval-minutes", 60));
    }

    public int hurricaneDurationMinMinutes() {
        return Math.max(1, plugin.getConfig().getInt("hurricane.duration-min-minutes", 15));
    }

    public int hurricaneDurationMaxMinutes() {
        return Math.max(hurricaneDurationMinMinutes(), plugin.getConfig().getInt("hurricane.duration-max-minutes", 40));
    }

    public double hurricaneRampFraction() {
        return Math.max(0.01, Math.min(0.49, plugin.getConfig().getDouble("hurricane.ramp-fraction", 0.15)));
    }

    public boolean hurricaneEyeEnabled() {
        return plugin.getConfig().getBoolean("hurricane.eye-enabled", true);
    }

    public double hurricaneEyeWidthFraction() {
        return Math.max(0.01, Math.min(0.5, plugin.getConfig().getDouble("hurricane.eye-width-fraction", 0.1)));
    }

    public double hurricaneEyeIntensity() {
        return clamp01(plugin.getConfig().getDouble("hurricane.eye-intensity", 0.1));
    }

    public double hurricaneMinWindStrength() {
        return clamp01(plugin.getConfig().getDouble("hurricane.min-wind-strength", 0.6));
    }

    public double hurricaneRainThreshold() {
        return clamp01(plugin.getConfig().getDouble("hurricane.rain-threshold", 0.25));
    }

    public double hurricaneThunderThreshold() {
        return clamp01(plugin.getConfig().getDouble("hurricane.thunder-threshold", 0.6));
    }

    public double hurricaneThunderChancePerTick() {
        return Math.max(0.0, plugin.getConfig().getDouble("hurricane.thunder-chance-per-tick", 0.02));
    }

    public boolean hurricaneStormSurgeEnabled() {
        return plugin.getConfig().getBoolean("hurricane.storm-surge-enabled", true);
    }

    public int hurricaneSurgeIntervalSeconds() {
        return Math.max(5, plugin.getConfig().getInt("hurricane.surge-interval-seconds", 45));
    }

    public int hurricaneSurgeCoastSearchRadius() {
        return Math.max(4, plugin.getConfig().getInt("hurricane.surge-coast-search-radius", 60));
    }

    public double hurricaneSurgeMaxInlandDistance() {
        return Math.max(1, plugin.getConfig().getDouble("hurricane.surge-max-inland-distance", 15.0));
    }

    public double hurricaneSurgeAdvanceSpeed() {
        return Math.max(0.1, plugin.getConfig().getDouble("hurricane.surge-advance-speed", 0.6));
    }

    public double hurricaneSurgeFrontWidth() {
        return Math.max(1, plugin.getConfig().getDouble("hurricane.surge-front-width", 30.0));
    }

    public double hurricaneSurgeWaveHeight() {
        return Math.max(1, plugin.getConfig().getDouble("hurricane.surge-wave-height", 3.0));
    }

    public double hurricaneSurgeKnockbackStrength() {
        return plugin.getConfig().getDouble("hurricane.surge-knockback-strength", 0.6);
    }

    public int hurricaneSurgeMaxAffectedBlocks() {
        return Math.max(50, plugin.getConfig().getInt("hurricane.surge-max-affected-blocks", 1500));
    }

    // --- weather HUD (scoreboard sidebar) ---

    public boolean weatherHudEnabled() {
        return plugin.getConfig().getBoolean("weather-hud.enabled", true);
    }

    public int weatherHudRefreshIntervalSeconds() {
        return Math.max(1, plugin.getConfig().getInt("weather-hud.refresh-interval-seconds", 3));
    }

    // --- shared helpers ---

    private static final List<Material> DEFAULT_FRAGILE_MATERIALS = List.of(
            Material.OAK_FENCE, Material.SPRUCE_FENCE, Material.BIRCH_FENCE, Material.JUNGLE_FENCE,
            Material.ACACIA_FENCE, Material.DARK_OAK_FENCE, Material.MANGROVE_FENCE, Material.CHERRY_FENCE,
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
            Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
            Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
            Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL,
            Material.OAK_LEAVES, Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES, Material.JUNGLE_LEAVES,
            Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES,
            Material.OAK_SAPLING, Material.TORCH, Material.LADDER, Material.SCAFFOLDING
    );

    private List<Material> parseMaterialList(String path, List<Material> defaults) {
        List<String> raw = plugin.getConfig().getStringList(path);
        if (raw.isEmpty()) return defaults;
        List<Material> materials = new ArrayList<>();
        for (String name : raw) {
            try {
                materials.add(Material.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // skip a bad entry rather than failing the whole list
            }
        }
        return materials.isEmpty() ? defaults : materials;
    }

    private Color parseColor(String path, int defaultR, int defaultG, int defaultB) {
        int r = plugin.getConfig().getInt(path + "-r", defaultR);
        int g = plugin.getConfig().getInt(path + "-g", defaultG);
        int b = plugin.getConfig().getInt(path + "-b", defaultB);
        return Color.fromRGB(clampByte(r), clampByte(g), clampByte(b));
    }

    private int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
