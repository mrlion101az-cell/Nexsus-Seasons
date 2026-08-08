package com.nexusuniverse.seasons.weather;

import com.nexusuniverse.seasons.SeasonsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * "Real waves crashing on the shore" as a continuous, always-happening ambient process -- not a
 * rare admin-triggered event like tsunami/hurricane. Built on the exact same CoastalFloodEngine
 * those two use (real water blocks genuinely rising above sea level onto land, then draining back
 * out in reverse order, with real velocity pushing anyone -- or any boat -- caught in it), just
 * run continuously in small, fast, frequent bursts instead of one huge one.
 *
 * Multiple shore-break waves can be active at once (each its own CoastalFloodEngine instance, up
 * to waves.shore-break.max-concurrent) so players spread across different beaches all get real
 * waves rather than only whoever is nearest a single shared one. Height scales with WindManager's
 * current strength between shore-break.min-height and shore-break.max-height (default up to 10
 * blocks) -- calm wind means small waves lapping the shore, severe wind means the full-height
 * waves crashing inland the user asked for, tying this into the same wind system as everything
 * else in this "crazy weather" layer rather than being its own unrelated dial.
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

    private void maybeSpawnWave() {
        if (activeWaves.size() >= config.shoreBreakMaxConcurrent()) return;

        var players = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getWorld().getEnvironment() == World.Environment.NORMAL)
                .toList();
        if (players.isEmpty()) return;
        Player anchor = players.get(random.nextInt(players.size()));

        // don't stack a new wave on top of one already breaking near this same player
        for (ActiveWave existing : activeWaves) {
            if (!existing.anchorLocation.getWorld().equals(anchor.getWorld())) continue;
            if (existing.anchorLocation.distanceSquared(anchor.getLocation()) < 4000) return; // ~63 blocks
        }

        CoastalFloodEngine engine = new CoastalFloodEngine();
        if (!engine.findCoastNear(anchor.getLocation(), config.shoreBreakCoastSearchRadius())) return;

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
