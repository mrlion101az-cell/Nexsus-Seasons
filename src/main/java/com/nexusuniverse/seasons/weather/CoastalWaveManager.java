package com.nexusuniverse.seasons.weather;

import com.nexusuniverse.seasons.SeasonsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * "Real waves crashing on the shore" as a continuous, always-happening ambient process -- not a
 * rare admin-triggered event like tsunami/hurricane. Built on the exact same CoastalFloodEngine
 * those two use (real water blocks genuinely rising onto land, then draining back out in reverse
 * order, with real velocity pushing anyone -- or any boat -- caught in it), just run continuously
 * in small, fast, frequent bursts instead of one huge one.
 *
 * Works on ANY qualifying body of water, not just the ocean -- a lake, a large pond, anything at
 * least waves.min-body-size blocks across (default 40x40) counts, at whatever height that body's
 * own surface actually sits (WaterBodyDetector never assumes World#getSeaLevel()). A player only
 * has to be near water at all for the cheap first check; the more expensive size check only runs
 * for players who actually cleared that first one.
 *
 * Multiple shore-break waves can be active at once (each its own CoastalFloodEngine instance, up
 * to waves.shore-break.max-concurrent) so players spread across different bodies of water all get
 * real waves rather than only whoever is nearest a single shared one. Height scales with
 * WindManager's current strength between shore-break.min-height and shore-break.max-height
 * (default up to 10 blocks) -- calm wind means small waves lapping the shore, severe wind means
 * the full-height waves crashing inland, tying this into the same wind system as everything else
 * in this "crazy weather" layer rather than being its own unrelated dial.
 */
public class CoastalWaveManager {

    private final JavaPlugin plugin;
    private final SeasonsConfig config;
    private final WindManager wind;
    private final Random random = new Random();
    private final List<ActiveWave> activeWaves = new ArrayList<>();

    private long ticksUntilNextSpawnCheck;
    private BukkitTask task;

    public CoastalWaveManager(JavaPlugin plugin, SeasonsConfig config, WindManager wind) {
        this.plugin = plugin;
        this.config = config;
        this.wind = wind;
    }

    public void start() {
        if (!config.shoreBreakEnabled()) return;
        ticksUntilNextSpawnCheck = spawnCheckIntervalTicks();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        activeWaves.clear();
    }

    private void tick() {
        // tick every currently-active wave, dropping any that finished (fully advanced and receded)
        activeWaves.removeIf(wave -> wave.engine.tick());

        ticksUntilNextSpawnCheck--;
        if (ticksUntilNextSpawnCheck <= 0) {
            ticksUntilNextSpawnCheck = spawnCheckIntervalTicks();
            maybeSpawnWave();
        }
    }

    /**
     * Tries every currently-eligible player each check, not just one random pick -- on a server
     * with several players, picking just one online player and giving up for the whole check
     * cycle if THAT one specific person isn't near a coastline meant this could go a very long
     * time without ever finding anyone actually standing at the shore, even with players who were
     * right at the water's edge the whole time. Shuffled order (not always the same player first)
     * and can spawn more than one wave in a single check, up to the remaining concurrent slots, so
     * several players near different bodies of water all get served in the same pass rather than
     * one at a time.
     */
    private void maybeSpawnWave() {
        var players = new ArrayList<>(Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getWorld().getEnvironment() == World.Environment.NORMAL)
                .toList());
        if (players.isEmpty()) return;
        Collections.shuffle(players, random);

        for (Player anchor : players) {
            if (activeWaves.size() >= config.shoreBreakMaxConcurrent()) return;
            trySpawnNear(anchor);
        }
    }

    private void trySpawnNear(Player anchor) {
        // don't stack a new wave on top of one already breaking near this same spot
        for (ActiveWave existing : activeWaves) {
            if (!existing.anchorLocation.getWorld().equals(anchor.getWorld())) continue;
            if (existing.anchorLocation.distanceSquared(anchor.getLocation()) < 4000) return; // ~63 blocks
        }

        // cheap check first: is there even any water within a few blocks at all? most players,
        // most of the time, aren't anywhere near water, so this rules most candidates out fast
        // before ever attempting the much more expensive flood-fill size check below
        Location water = WaterBodyDetector.findNearbyWater(anchor.getLocation(), config.wavesWaterSearchRadius());
        if (water == null) return;
        if (!WaterBodyDetector.isLargeBody(water, config.wavesMinBodySize(), config.wavesBodyDetectionMaxBlocks())) return;

        CoastalFloodEngine engine = new CoastalFloodEngine();
        if (!engine.findCoastNear(anchor.getLocation(), config.shoreBreakCoastSearchRadius(), water.getBlockY())) return;

        double windStrength = wind != null ? wind.currentStrength() : 0.2;
        double height = config.shoreBreakMinHeight()
                + windStrength * (config.shoreBreakMaxHeight() - config.shoreBreakMinHeight());

        engine.start(config.shoreBreakMaxInlandDistance(), config.shoreBreakAdvanceSpeed(),
                config.shoreBreakFrontWidth(), height, config.shoreBreakKnockbackStrength(),
                config.shoreBreakMaxAffectedBlocks());

        activeWaves.add(new ActiveWave(engine, anchor.getLocation().clone()));
    }

    private long spawnCheckIntervalTicks() {
        return 20L * config.shoreBreakCheckIntervalSeconds();
    }

    private static class ActiveWave {
        final CoastalFloodEngine engine;
        final Location anchorLocation; // where this wave was triggered from, just for spacing new spawns apart

        ActiveWave(CoastalFloodEngine engine, Location anchorLocation) {
            this.engine = engine;
            this.anchorLocation = anchorLocation;
        }
    }
}
