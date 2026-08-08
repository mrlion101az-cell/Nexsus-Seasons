package com.nexusuniverse.seasons.weather;

import com.nexusuniverse.seasons.SeasonsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * Continuous, always-on ambient effect (like WindManager, not a start/stop event) -- for anyone
 * standing at or in open ocean, spawns rhythmic splash/foam particles and gives swimmers a gentle
 * push, both driven by a slow sine wave over real time so it reads as actual swell rather than
 * random noise. "Different types of waves" comes from tying amplitude and frequency directly to
 * WindManager's current strength: calm wind gives small, slow, gentle waves; strong wind gives
 * bigger, faster, rougher ones -- the same wind that pushes players on land is what's stirring up
 * the water they're standing in, rather than two unrelated systems.
 */
public class WaveManager {

    private final JavaPlugin plugin;
    private final SeasonsConfig config;
    private final WindManager wind;
    private final Random random = new Random();

    private long clockTicks = 0;
    private BukkitTask task;

    public WaveManager(JavaPlugin plugin, SeasonsConfig config, WindManager wind) {
        this.plugin = plugin;
        this.config = config;
        this.wind = wind;
    }

    public void start() {
        if (!config.wavesEnabled()) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L); // every other tick -- ambient dressing doesn't need per-tick precision
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void tick() {
        clockTicks += 2;
        double windStrength = wind != null ? wind.currentStrength() : 0.2;
        // amplitude/frequency both scale with wind -- rougher wind means bigger, faster swell
        double amplitude = config.wavesBaseAmplitude() + windStrength * config.wavesWindAmplitudeMultiplier();
        double frequency = config.wavesBaseFrequency() + windStrength * config.wavesWindFrequencyMultiplier();
        double phase = clockTicks * frequency;
        double swell = Math.sin(phase) * amplitude;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            if (player.getLocation().getBlock().getType() != Material.WATER
                    && player.getEyeLocation().getBlock().getType() != Material.WATER) continue;
            if (!isOpenOcean(player.getLocation())) continue;

            spawnFoam(player, swell, windStrength);
            if (config.wavesPushSwimmers()) {
                pushSwimmer(player, swell, windStrength);
            }
        }
    }

    /** Cheap heuristic: treat "at/near sea level, in water, more than a few blocks from any non-water column nearby" as open ocean rather than a pond, river, or moat -- avoids ambient waves showing up in a player's decorative fountain. */
    private boolean isOpenOcean(Location location) {
        World world = location.getWorld();
        int seaLevel = world.getSeaLevel();
        if (Math.abs(location.getBlockY() - seaLevel) > 3) return false;

        int checkRadius = 6;
        int landFound = 0;
        for (int i = 0; i < 6; i++) {
            double angle = i * (Math.PI * 2 / 6);
            int x = location.getBlockX() + (int) (Math.cos(angle) * checkRadius);
            int z = location.getBlockZ() + (int) (Math.sin(angle) * checkRadius);
            if (world.getHighestBlockAt(x, z).getY() > seaLevel + 1) landFound++;
        }
        return landFound == 0; // land in every direction within range means this is a small enclosed body of water, not ocean
    }

    private void spawnFoam(Player player, double swell, double windStrength) {
        if (random.nextDouble() >= 0.15 + windStrength * 0.3) return; // rougher water throws up foam more often

        Location surface = player.getLocation().clone();
        surface.setY(player.getWorld().getSeaLevel() + 1 + swell * 0.3);
        Color foam = Color.fromRGB(230, 230, 235);
        player.getWorld().spawnParticle(Particle.DUST, surface, 4, 1.2, 0.15, 1.2, new Particle.DustOptions(foam, 1.5f));
        if (windStrength > 0.5) {
            player.getWorld().spawnParticle(Particle.BUBBLE_POP, surface, 2, 1.0, 0.1, 1.0, 0.02);
        }
    }

    private void pushSwimmer(Player player, double swell, double windStrength) {
        if (windStrength < config.wavesPushMinWindStrength()) return;
        Vector direction = wind != null ? wind.currentDirection() : new Vector(1, 0, 0);
        Vector push = direction.clone().multiply(windStrength * config.wavesPushMultiplier());
        push.setY(Math.max(-0.05, swell * 0.02)); // bob up and down slightly with the swell, never pulled hard downward
        player.setVelocity(player.getVelocity().add(push));
    }
}
