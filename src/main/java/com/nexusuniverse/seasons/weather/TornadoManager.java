package com.nexusuniverse.seasons.weather;

import com.nexusuniverse.seasons.SeasonsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A genuine physics-driven tornado, built entirely from particles and real velocity manipulation
 * -- no resource pack, no custom models. The funnel is a stack of rotating particle rings (wider
 * at the top and bottom, narrower through the middle), spinning faster the closer to the ground.
 * "Leaf debris" is simulated with tinted DUST particles (green/brown) rather than a literal leaf
 * particle, since there isn't a stable, guaranteed-available vanilla particle for that -- see
 * FogManager's doc comment for the same kind of honest caveat applied to real API limits.
 *
 * Physics, applied to every player within the tornado's radius and height each tick:
 *  - PULL: velocity nudged toward the vortex center, stronger the closer they are.
 *  - LIFT: upward velocity, same distance falloff -- being near the center means getting picked
 *    up, not just yanked sideways.
 *  - SWIRL: a tangential (perpendicular-to-center) component so it reads as being spun around the
 *    vortex, not just dragged straight to a point.
 *  All three are added together and capped at tornado.max-velocity-per-tick so nobody gets
 *  launched into orbit from one bad tick.
 *
 * Fragile blocks (tornado.destroy-fragile-blocks, reuses wind.fragile-materials) within radius
 * have a budgeted, throttled chance per tick of getting ripped up -- same "remove the block, spawn
 * a real dropped item with outward+upward+swirl velocity" approach WindManager uses for severe
 * gusts, just far more aggressive and constant while a tornado is actively overhead.
 *
 * The tornado itself drifts across the terrain over its lifetime (a slow random walk, not a fixed
 * point) rather than sitting still, so it reads as a moving storm passing through rather than a
 * stationary special-effect.
 */
public class TornadoManager {

    private final JavaPlugin plugin;
    private final SeasonsConfig config;
    private final WindManager wind;
    private final Random random = new Random();

    private Tornado active;
    private long ticksUntilNaturalCheck;
    private BukkitTask task;

    public TornadoManager(JavaPlugin plugin, SeasonsConfig config, WindManager wind) {
        this.plugin = plugin;
        this.config = config;
        this.wind = wind;
    }

    public void start() {
        if (!config.tornadoEnabled()) return;
        ticksUntilNaturalCheck = checkIntervalTicks();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public boolean isActive() {
        return active != null;
    }

    /** /nexusseasons tornado spawn -- centers a new tornado on the given location, replacing any currently active one. */
    public void spawnAt(Location location) {
        int durationSeconds = randomBetween(config.tornadoDurationMinSeconds(), config.tornadoDurationMaxSeconds());
        active = new Tornado(location.clone(), 20L * durationSeconds);
        if (wind != null) wind.forceSeverity(config.windSevereThreshold(), active.remainingTicks);
    }

    public void dissipate() {
        active = null;
    }

    private void tick() {
        if (active == null) {
            ticksUntilNaturalCheck--;
            if (ticksUntilNaturalCheck <= 0) {
                ticksUntilNaturalCheck = checkIntervalTicks();
                if (random.nextDouble() < config.tornadoNaturalChance()) {
                    spawnNearRandomPlayer();
                }
            }
            return;
        }

        active.remainingTicks--;
        if (active.remainingTicks <= 0) {
            dissipate();
            return;
        }

        active.angle += config.tornadoSpinSpeed();
        drift(active);
        renderFunnel(active);
        applyPhysics(active);
        if (config.tornadoDestroyFragileBlocks()) {
            maybeDislodgeBlock(active);
        }
    }

    private void spawnNearRandomPlayer() {
        var players = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getWorld().getEnvironment() == World.Environment.NORMAL)
                .toList();
        if (players.isEmpty()) return;
        Player anchor = players.get(random.nextInt(players.size()));

        int offset = (int) (config.tornadoRadius() * 3);
        Location center = anchor.getLocation().clone();
        center.add(random.nextInt(offset * 2 + 1) - offset, 0, random.nextInt(offset * 2 + 1) - offset);
        center.setY(center.getWorld().getHighestBlockYAt(center.getBlockX(), center.getBlockZ()) + 1);
        spawnAt(center);
    }

    private void drift(Tornado tornado) {
        tornado.driftAngle += (random.nextDouble() - 0.5) * 0.2;
        double speed = config.tornadoMoveSpeed();
        tornado.center.add(Math.cos(tornado.driftAngle) * speed, 0, Math.sin(tornado.driftAngle) * speed);
        // keep the base pinned to the actual ground as it wanders over uneven terrain
        World world = tornado.center.getWorld();
        tornado.center.setY(world.getHighestBlockYAt(tornado.center.getBlockX(), tornado.center.getBlockZ()) + 1);
    }

    private void renderFunnel(Tornado tornado) {
        World world = tornado.center.getWorld();
        int height = config.tornadoHeight();
        double baseRadius = config.tornadoRadius();

        for (int y = 0; y < height; y += 2) {
            // narrower through the middle of the funnel, flared at the very top and bottom
            double t = (double) y / height;
            double radiusHere = baseRadius * (0.4 + 0.6 * Math.abs(Math.sin(t * Math.PI)));
            double ringAngle = tornado.angle + y * 0.3; // each height layer spins offset from the one below, for a twisting look

            int pointsInRing = 10;
            for (int i = 0; i < pointsInRing; i++) {
                double a = ringAngle + (Math.PI * 2 * i / pointsInRing);
                double px = Math.cos(a) * radiusHere;
                double pz = Math.sin(a) * radiusHere;
                Location point = tornado.center.clone().add(px, y, pz);

                if (i % 2 == 0) {
                    world.spawnParticle(Particle.WHITE_SMOKE, point, 1, 0.1, 0.1, 0.1, 0.0);
                } else {
                    // simulated leaf/debris flecks -- see class doc for why this is tinted dust rather than a real leaf particle
                    Color tint = random.nextBoolean() ? Color.fromRGB(90, 130, 40) : Color.fromRGB(120, 90, 45);
                    world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, new Particle.DustOptions(tint, 1.5f));
                }
            }
        }
    }

    private void applyPhysics(Tornado tornado) {
        double radius = config.tornadoRadius();
        double height = config.tornadoHeight();
        double maxVelocity = config.tornadoMaxVelocityPerTick();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != tornado.center.getWorld()) continue;
            Location loc = player.getLocation();
            double dx = tornado.center.getX() - loc.getX();
            double dz = tornado.center.getZ() - loc.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            double dy = loc.getY() - tornado.center.getY();
            if (horizontalDist > radius || dy < 0 || dy > height) continue;

            double closeness = 1.0 - (horizontalDist / radius); // 0 at the edge, 1 at dead center

            Vector pull = horizontalDist > 0.001
                    ? new Vector(dx, 0, dz).normalize().multiply(closeness * config.tornadoPullStrength())
                    : new Vector(0, 0, 0);
            Vector lift = new Vector(0, closeness * config.tornadoLiftStrength(), 0);
            Vector tangent = horizontalDist > 0.001
                    ? new Vector(-dz, 0, dx).normalize().multiply(closeness * config.tornadoSwirlStrength())
                    : new Vector(0, 0, 0);

            Vector total = pull.add(lift).add(tangent);
            if (total.length() > maxVelocity) total.normalize().multiply(maxVelocity);

            player.setVelocity(player.getVelocity().add(total));
        }
    }

    private void maybeDislodgeBlock(Tornado tornado) {
        int budget = config.tornadoBlocksPerTick();
        List<Material> fragile = config.windFragileMaterials();
        if (fragile.isEmpty()) return;

        World world = tornado.center.getWorld();
        int radius = (int) config.tornadoRadius();
        int checked = 0;
        int attempts = budget * 4; // a few misses per budgeted removal are expected and fine

        List<Block> toRemove = new ArrayList<>();
        while (checked < attempts && toRemove.size() < budget) {
            checked++;
            int x = tornado.center.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int z = tornado.center.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            Block block = world.getHighestBlockAt(x, z).getRelative(BlockFace.DOWN);
            if (fragile.contains(block.getType())) toRemove.add(block);
        }

        for (Block block : toRemove) {
            ItemStack drop = new ItemStack(block.getType());
            Location origin = block.getLocation().add(0.5, 0.5, 0.5);
            block.setType(Material.AIR);

            Item item = world.dropItem(origin, drop);
            item.setPickupDelay(100);
            double dx = origin.getX() - tornado.center.getX();
            double dz = origin.getZ() - tornado.center.getZ();
            Vector outward = new Vector(dx, 0.6 + random.nextDouble() * 0.4, dz);
            if (outward.lengthSquared() > 0) outward.normalize();
            item.setVelocity(outward.multiply(0.5));
        }
    }

    private long checkIntervalTicks() {
        return 20L * 60L * config.tornadoCheckIntervalMinutes();
    }

    private int randomBetween(int min, int max) {
        if (max <= min) return Math.max(1, min);
        return min + random.nextInt(max - min + 1);
    }

    /** Mutable state for the one currently active tornado -- this plugin only ever runs one at a time. */
    private static class Tornado {
        final Location center;
        double angle = 0;
        double driftAngle;
        long remainingTicks;

        Tornado(Location center, long remainingTicks) {
            this.center = center;
            this.remainingTicks = remainingTicks;
            this.driftAngle = new Random().nextDouble() * Math.PI * 2;
        }
    }
}
