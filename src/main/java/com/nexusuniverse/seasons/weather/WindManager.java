package com.nexusuniverse.seasons.weather;

import com.nexusuniverse.seasons.SeasonsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * A continuous, ambient wind field -- not a one-off event like the tornado/blizzard/dry-thunder
 * managers, always running whenever wind.enabled is true. Direction and strength slowly drift
 * (a small random walk every tick) between wind.min-strength and wind.max-strength, with a bigger
 * re-roll every wind.change-interval-minutes so it doesn't feel perfectly smooth and predictable.
 *
 * Two effects, independently toggleable:
 *  - Pushing players: anyone standing with open sky above them (same getHighestBlockYAt technique
 *    NexusSurvival/NexusVitals use for "exposed to the sky") gets a gentle velocity nudge in the
 *    wind direction, scaled by current strength. Below wind.player-push-min-strength, nothing
 *    happens at all -- a light breeze shouldn't shove anyone around.
 *  - Dislodging fragile blocks: once strength crosses wind.severe-threshold, each tick has a small
 *    chance to pick a random exposed, lightly-supported block (matching wind.fragile-materials --
 *    default wool, fences, leaves, and other "sits by itself" block types) near an online player
 *    and blow it away -- removes the block and spawns a real dropped item with velocity in the
 *    wind direction, so it visibly goes tumbling off rather than just vanishing.
 *
 * Other event managers (tornado, blizzard) can read currentDirection()/currentStrength() to keep
 * their own particle drift visually consistent with whatever the wind is actually doing, and can
 * call forceSeverity() to temporarily spike conditions for their own duration (a blizzard should
 * feel windier than an ordinary day, without permanently changing the ambient wind).
 */
public class WindManager {

    private final JavaPlugin plugin;
    private final SeasonsConfig config;
    private final Random random = new Random();

    private double directionRadians;
    private double strength; // 0.0-1.0
    private long ticksUntilReroll;
    private BukkitTask task;

    // an event-driven override (e.g. a blizzard in progress) -- null when nothing is overriding
    private Double overrideStrength;
    private long overrideTicksRemaining;

    public WindManager(JavaPlugin plugin, SeasonsConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        if (!config.windEnabled()) return;
        rerollDirectionAndStrength();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    /** Current wind direction as a flat (Y=0) unit vector, for other managers to align their own effects with. */
    public Vector currentDirection() {
        return new Vector(Math.cos(directionRadians), 0, Math.sin(directionRadians));
    }

    /** 0.0-1.0, including any active override from another event manager. */
    public double currentStrength() {
        return overrideStrength != null ? overrideStrength : strength;
    }

    /** Temporarily forces wind strength to at least this value for durationTicks, e.g. a blizzard making conditions feel windier than the ambient day. Calling this again just refreshes the duration. */
    public void forceSeverity(double minStrength, long durationTicks) {
        overrideStrength = Math.max(minStrength, strength);
        overrideTicksRemaining = durationTicks;
    }

    private void tick() {
        if (overrideStrength != null) {
            overrideTicksRemaining--;
            if (overrideTicksRemaining <= 0) overrideStrength = null;
        }

        ticksUntilReroll--;
        if (ticksUntilReroll <= 0) {
            rerollDirectionAndStrength();
        } else {
            drift();
        }

        double effectiveStrength = currentStrength();
        if (config.windPushPlayers() && effectiveStrength >= config.windPlayerPushMinStrength()) {
            pushPlayers(effectiveStrength);
        }
        if (effectiveStrength >= config.windSevereThreshold()) {
            maybeDislodgeBlock();
        }
    }

    private void rerollDirectionAndStrength() {
        directionRadians = random.nextDouble() * Math.PI * 2;
        strength = randomBetween(config.windMinStrength(), config.windMaxStrength());
        ticksUntilReroll = 20L * 60L * config.windChangeIntervalMinutes();
    }

    /** Small per-tick nudge so direction/strength don't snap instantly between re-rolls. */
    private void drift() {
        directionRadians += (random.nextDouble() - 0.5) * 0.01;
        strength += (random.nextDouble() - 0.5) * 0.002;
        strength = Math.max(config.windMinStrength(), Math.min(config.windMaxStrength(), strength));
    }

    private void pushPlayers(double effectiveStrength) {
        Vector push = currentDirection().multiply(effectiveStrength * config.windPlayerPushMultiplier());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            if (player.isFlying() || player.isInsideVehicle()) continue;
            if (!isExposedToSky(player.getLocation())) continue;
            player.setVelocity(player.getVelocity().add(push));
        }
    }

    /** Whether a location has open sky directly above it -- exposed to real wind vs. sheltered indoors/underground. Public because WeatherHudManager reuses this exact check so its wind-meter reading reflects where the player actually is. */
    public boolean isExposedToSky(Location location) {
        World world = location.getWorld();
        if (world == null) return false;
        return world.getHighestBlockYAt(location.getBlockX(), location.getBlockZ()) <= location.getBlockY();
    }

    private void maybeDislodgeBlock() {
        if (random.nextDouble() >= config.windDislodgeChancePerTick()) return;

        var players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) return;
        Player anchor = players.stream().skip(random.nextInt(players.size())).findFirst().orElse(null);
        if (anchor == null || anchor.getWorld().getEnvironment() != World.Environment.NORMAL) return;

        int radius = config.windDislodgeSearchRadius();
        Location center = anchor.getLocation();
        int x = center.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
        int z = center.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
        Block block = center.getWorld().getHighestBlockAt(x, z).getRelative(BlockFace.DOWN);

        if (!isFragile(block.getType())) return;
        if (!isLooselySupported(block)) return;

        blowAway(block);
    }

    private boolean isFragile(Material material) {
        return config.windFragileMaterials().contains(material);
    }

    /** Cheap heuristic, not real structural analysis: fewer than 2 solid horizontal neighbors counts as "sitting by itself." */
    private boolean isLooselySupported(Block block) {
        int solidNeighbors = 0;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (block.getRelative(face).getType().isSolid()) solidNeighbors++;
        }
        return solidNeighbors < 2;
    }

    private void blowAway(Block block) {
        ItemStack drop = new ItemStack(block.getType());
        Location origin = block.getLocation().add(0.5, 0.5, 0.5);
        block.setType(Material.AIR);

        Item item = block.getWorld().dropItem(origin, drop);
        item.setPickupDelay(100);
        Vector velocity = currentDirection().multiply(0.4 + random.nextDouble() * 0.3);
        velocity.setY(0.2 + random.nextDouble() * 0.15);
        item.setVelocity(velocity);
    }

    private double randomBetween(double min, double max) {
        if (max <= min) return Math.max(0, min);
        return min + random.nextDouble() * (max - min);
    }
}
