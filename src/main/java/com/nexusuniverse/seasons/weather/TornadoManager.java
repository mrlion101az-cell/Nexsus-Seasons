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
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * A genuine physics-driven tornado, built entirely from particles and real velocity manipulation
 * -- no resource pack, no custom models. Three layers together: a stack of rotating particle rings
 * (funnelRadiusAt() -- pointed/narrow at the base, flared/wide at the top, a real funnel-cloud
 * silhouette, NOT narrow-at-both-ends) that give it a defined spinning shape; a dense white-smoke/
 * cloud body fill (renderDenseBody(), the same dense-random-scatter-in-a-radius technique
 * FogManager/BlizzardManager use for their whiteout look) that thickens it into a real,
 * solid-looking cyclone rather than a sparse wireframe; and a wide spiraling cloud canopy
 * (renderCloudCanopy()) up near the top -- a log-spiral disc far wider than the funnel itself,
 * standing in for the rotating storm cloud (mesocyclone) a real tornado actually descends from,
 * since the funnel alone reads as "a column," not "a storm." ALL THREE layers spawn every particle
 * with the spawnParticle(count=0, offsets-as-velocity) trick -- each individual particle gets real
 * tangential motion curving around the funnel's own center, not just a new randomly-jittered
 * position every tick. That distinction matters: recomputing a particle's position at a rotated
 * angle each frame LOOKS like a rotating shape from a distance, but each individual particle isn't
 * actually moving -- giving every particle its own curving velocity is what makes it read as
 * something genuinely spinning up close. "Leaf debris" is simulated with tinted DUST particles
 * (green/brown) rather than a literal leaf particle, since there isn't a stable,
 * guaranteed-available vanilla particle for that -- see FogManager's doc comment for the same kind
 * of honest caveat applied to real API limits.
 *
 * ON TOP of those three particle layers, maybeLaunchDebris()/updateDebris() add real flying
 * blocks -- actual FallingBlock entities picked up from the natural terrain within the funnel's
 * radius (tornado.debris.materials, deliberately grass/dirt/sand/logs/leaves/stone rather than
 * anything players commonly build with), given the same kind of self-updating tangential-swirl
 * velocity every tick so they trace a real spiraling orbit upward around the funnel rather than
 * just flying off in one direction. Non-destructive by default -- once a debris block's short
 * lifetime ends, the original block gets put back where it was picked up from
 * (tornado.debris.restore-terrain), same "never leave lasting terrain damage by default" rule as
 * every other effect in this "crazy weather" layer.
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
    private final List<TornadoDebris> debris = new ArrayList<>();

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
        clearAllDebris();
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
        clearAllDebris();
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
        renderDenseBody(active);
        renderCloudCanopy(active);
        applyPhysics(active);
        maybeLaunchDebris(active);
        updateDebris(active);
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
        int pointsInRing = config.tornadoRingPointCount();
        int verticalStep = config.tornadoRingVerticalStep();

        for (int y = 0; y < height; y += verticalStep) {
            double t = (double) y / height;
            double radiusHere = funnelRadiusAt(t, baseRadius);
            double ringAngle = tornado.angle + y * 0.3; // each height layer spins offset from the one below, for a twisting look

            for (int i = 0; i < pointsInRing; i++) {
                double a = ringAngle + (Math.PI * 2 * i / pointsInRing);
                double px = Math.cos(a) * radiusHere;
                double pz = Math.sin(a) * radiusHere;
                Location point = tornado.center.clone().add(px, y, pz);

                // tangential direction at this exact point around the ring -- gives each
                // individual particle real curving motion around the funnel instead of just
                // popping into a new rotated position every tick with no motion of its own. This
                // is what actually reads as "spinning," not just "a shape that happens to rotate."
                double tx = -Math.sin(a);
                double tz = Math.cos(a);

                if (i % 2 == 0) {
                    world.spawnParticle(Particle.WHITE_SMOKE, point, 0, tx * 0.25, 0.06, tz * 0.25, 0.15);
                } else {
                    // simulated leaf/debris flecks -- see class doc for why this is tinted dust rather than a real leaf particle
                    Color tint = random.nextBoolean() ? Color.fromRGB(90, 130, 40) : Color.fromRGB(120, 90, 45);
                    world.spawnParticle(Particle.DUST, point, 0, tx * 0.25, 0.06, tz * 0.25, 0.15,
                            new Particle.DustOptions(tint, 1.5f));
                }
            }
        }
    }

    /**
     * The funnel's actual SHAPE, shared by both renderFunnel() (the ring structure) and
     * renderDenseBody() (the cloud fill) so the two layers can never draw two different silhouettes
     * again -- t is 0 at the very base (ground) and 1 at the very top. Pointed/narrow at the base
     * (tornado.base-radius-ratio, default a fraction of the configured radius) and flared/wide at
     * the top (tornado.top-radius-ratio, default well past 1.0x), like a real funnel cloud -- not
     * the old narrow-at-both-ends "football" shape this used to have. Math.pow with an exponent
     * under 1 keeps it noticeably narrow for a good stretch near the base before opening up, rather
     * than widening in a straight line the whole way, which reads more like an actual tornado's
     * silhouette than a plain cone would.
     */
    private double funnelRadiusAt(double t, double baseRadius) {
        double clampedT = Math.max(0.0, Math.min(1.0, t));
        double widen = Math.pow(clampedT, config.tornadoFunnelFlareCurve());
        double ratio = config.tornadoBaseRadiusRatio() + (config.tornadoTopRadiusRatio() - config.tornadoBaseRadiusRatio()) * widen;
        return baseRadius * ratio;
    }

    /**
     * Fills in the funnel's silhouette with the same dense-white-particle technique
     * FogManager/BlizzardManager use for their whiteout look -- turns the sparse ring rendered
     * above into a real, thick-looking cyclone body. Each fill particle is spawned with the
     * spawnParticle(count=0, ...) trick (also used by BlizzardManager's blowing snow) where the
     * offset arguments become genuine directional velocity instead of random jitter -- so every
     * one of these particles visibly spirals outward along the tornado's own rotation rather than
     * just sitting in place, which is what actually sells "spiraling" rather than just "dense."
     */
    private void renderDenseBody(Tornado tornado) {
        int fillCount = config.tornadoFunnelFillDensity();
        if (fillCount <= 0) return;

        World world = tornado.center.getWorld();
        int height = config.tornadoHeight();
        double baseRadius = config.tornadoRadius();

        for (int i = 0; i < fillCount; i++) {
            double y = random.nextDouble() * height;
            double t = y / height;
            double radiusHere = funnelRadiusAt(t, baseRadius);
            double dist = random.nextDouble() * radiusHere;
            // follow the same per-height twist the ring uses, with a little scatter around it so
            // fill particles read as belonging to the spiral structure rather than a random haze
            double angle = tornado.angle + y * 0.3 + (random.nextDouble() - 0.5) * 1.2;

            double px = Math.cos(angle) * dist;
            double pz = Math.sin(angle) * dist;
            Location point = tornado.center.clone().add(px, y, pz);

            // tangential direction at this point -- the actual outward-spiraling velocity
            double tx = -Math.sin(angle);
            double tz = Math.cos(angle);

            world.spawnParticle(Particle.WHITE_SMOKE, point, 0, tx * 0.2, 0.05, tz * 0.2, 0.15);
            if (random.nextDouble() < 0.4) {
                Color stormTint = Color.fromRGB(205, 205, 210);
                world.spawnParticle(Particle.DUST, point, 0, tx * 0.2, 0.05, tz * 0.2, 0.15,
                        new Particle.DustOptions(stormTint, 2.0f));
            }
            if (random.nextDouble() < 0.3) {
                world.spawnParticle(Particle.CLOUD, point, 0, tx * 0.15, 0.04, tz * 0.15, 0.1);
            }
        }
    }

    /**
     * The wide, spiraling cloud disc up near the top of the funnel -- a real mesocyclone (the
     * rotating storm cloud a tornado actually descends from) has a broad swirling cloud ceiling
     * far wider than the funnel itself, spinning visibly above where the narrow column meets the
     * sky. The two funnel layers above build the narrow column; this is what was still missing --
     * something wide, thin, and unmistakably a rotating cloud, not just a thicker funnel.
     *
     * Built as a log-spiral (angle increases with distance from center, tornado.canopy-arms of
     * them at once) rather than plain concentric rings -- a log spiral is what an actual rotating
     * storm system looks like from below/the side, arms curling outward rather than uniform rings.
     * Kept as a thin, mostly-flat disc (small vertical scatter only) near the very top of the
     * funnel's own height, at a radius well past the funnel's own top width
     * (tornado.canopy-radius-multiplier, multiplied against tornado.radius). Every particle still
     * gets real tangential velocity via the same count=0 trick the rest of the funnel uses --
     * outer particles drift a little faster than inner ones, which is what actually reads as a
     * spiral rotating outward rather than a flat disc rotating as one rigid piece.
     */
    private void renderCloudCanopy(Tornado tornado) {
        if (!config.tornadoCanopyEnabled()) return;

        World world = tornado.center.getWorld();
        double maxRadius = config.tornadoRadius() * config.tornadoCanopyRadiusMultiplier();
        double canopyY = config.tornadoHeight() * 0.95;
        int arms = config.tornadoCanopyArms();
        int density = config.tornadoCanopyDensity();

        for (int i = 0; i < density; i++) {
            double r = random.nextDouble() * maxRadius;
            double armOffset = random.nextInt(arms) * (Math.PI * 2 / arms);
            // log-spiral twist: the further out along an arm, the more it's rotated from the arm's
            // own base angle -- this curl is what makes it read as a spiral instead of a straight
            // line or a plain ring
            double angle = tornado.angle + armOffset + (r / maxRadius) * Math.PI * 2.5
                    + (random.nextDouble() - 0.5) * 0.3;

            double px = Math.cos(angle) * r;
            double pz = Math.sin(angle) * r;
            double py = canopyY + (random.nextDouble() - 0.5) * 1.5; // thin disc -- only a little vertical scatter
            Location point = tornado.center.clone().add(px, py, pz);

            double tx = -Math.sin(angle);
            double tz = Math.cos(angle);
            double speed = 0.1 + (r / maxRadius) * 0.15; // outer particles drift faster -- sells the outward spiral

            if (random.nextDouble() < 0.6) {
                world.spawnParticle(Particle.CLOUD, point, 0, tx * speed, 0.0, tz * speed, 0.08);
            } else {
                world.spawnParticle(Particle.WHITE_SMOKE, point, 0, tx * speed, 0.0, tz * speed, 0.1);
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
        boolean spawnProtected = config.spawnProtectionEnabled();
        int spawnRadius = config.spawnProtectionRadiusChunks();
        while (checked < attempts && toRemove.size() < budget) {
            checked++;
            int x = tornado.center.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int z = tornado.center.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            if (SpawnProtection.isProtected(world, x, z, spawnProtected, spawnRadius)) continue;
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

    /**
     * Picks up one real natural-terrain block (not a particle -- an actual FallingBlock entity, so
     * it visibly renders in the world as a real block, exactly what the ring/fill/canopy particle
     * layers can't do) from somewhere within the funnel's radius and hands it to updateDebris()
     * below to spiral upward. Uses tornado.debris.materials -- see that config's own doc and
     * DEFAULT_DEBRIS_MATERIALS' comment in SeasonsConfig for the honest limits of what "natural
     * terrain, not a player build" can actually mean without a block-placement tracking system.
     */
    private void maybeLaunchDebris(Tornado tornado) {
        if (!config.tornadoDebrisEnabled()) return;
        if (debris.size() >= config.tornadoDebrisMaxActive()) return;
        if (random.nextDouble() >= config.tornadoDebrisSpawnChancePerTick()) return;

        List<Material> allowed = config.tornadoDebrisMaterials();
        if (allowed.isEmpty()) return;

        World world = tornado.center.getWorld();
        int radius = (int) config.tornadoRadius();
        int x = tornado.center.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
        int z = tornado.center.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;

        if (SpawnProtection.isProtected(world, x, z, config.spawnProtectionEnabled(), config.spawnProtectionRadiusChunks())) {
            return;
        }

        Block block = world.getHighestBlockAt(x, z);
        if (!allowed.contains(block.getType())) return;

        Location origin = block.getLocation();
        BlockData originalData = block.getBlockData().clone();

        block.setType(Material.AIR);
        FallingBlock entity = world.spawnFallingBlock(origin.clone().add(0.5, 0.3, 0.5), originalData);
        entity.setDropItem(false);
        entity.setHurtEntities(false);
        entity.setGravity(false); // this class drives its motion directly every tick in updateDebris(), not vanilla falling-block physics

        debris.add(new TornadoDebris(entity, origin, originalData));
    }

    /**
     * Keeps every currently-airborne debris block orbiting the tornado: a dominant tangential swirl
     * (recomputed from the entity's OWN current position each tick, which is what makes it trace a
     * real circular path rather than just drifting in one fixed direction), a steady upward lift so
     * it climbs into the sky over its lifetime, and a gentle pull back toward a hover radius so it
     * settles into orbiting around the funnel instead of flying off outward or crashing through the
     * dead center. Once a debris block's lifetime runs out, it's removed and (tornado.debris.
     * restore-terrain, on by default) the original block is put back where it was picked up from --
     * matching every other effect in this layer's rule against leaving permanent terrain damage.
     */
    private void updateDebris(Tornado tornado) {
        if (debris.isEmpty()) return;

        int lifetimeTicks = config.tornadoDebrisLifetimeTicks();
        boolean restore = config.tornadoDebrisRestoreTerrain();
        double hoverRadius = config.tornadoRadius() * 0.8;
        double swirlSpeed = config.tornadoDebrisSwirlSpeed();
        double liftSpeed = config.tornadoDebrisLiftSpeed();

        Iterator<TornadoDebris> iterator = debris.iterator();
        while (iterator.hasNext()) {
            TornadoDebris d = iterator.next();

            if (!d.entity.isValid()) {
                finishDebris(d, restore);
                iterator.remove();
                continue;
            }

            d.ticksAlive++;
            if (d.ticksAlive >= lifetimeTicks) {
                d.entity.remove();
                finishDebris(d, restore);
                iterator.remove();
                continue;
            }

            Location loc = d.entity.getLocation();
            double dx = loc.getX() - tornado.center.getX();
            double dz = loc.getZ() - tornado.center.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);

            double tx, tz;
            if (dist > 0.01) {
                tx = -dz / dist;
                tz = dx / dist;
            } else {
                tx = 1;
                tz = 0;
            }

            Vector velocity = new Vector(tx, 0, tz).multiply(swirlSpeed).add(new Vector(0, liftSpeed, 0));
            if (dist > 0.01) {
                double inwardBias = (dist - hoverRadius) * 0.02; // pulls in if too far out, pushes out if too close to dead center
                velocity.add(new Vector(-dx / dist, 0, -dz / dist).multiply(inwardBias));
            }
            d.entity.setVelocity(velocity);
        }
    }

    /** Puts the original block back where a debris entity was picked up from, unless something else has since occupied that exact spot -- called both when a debris block's own lifetime naturally ends and when the whole tornado dissipates early. */
    private void finishDebris(TornadoDebris d, boolean restore) {
        if (!restore) return;
        Block block = d.originalLocation.getBlock();
        if (block.getType() == Material.AIR) {
            block.setBlockData(d.originalData);
        }
    }

    /** Removes every currently-airborne debris entity and restores their terrain immediately -- called when the tornado dissipates (naturally or forced) or the plugin disables, so nothing floats in the sky or leaves a permanent hole after the tornado itself is gone. */
    private void clearAllDebris() {
        boolean restore = config.tornadoDebrisRestoreTerrain();
        for (TornadoDebris d : debris) {
            if (d.entity.isValid()) d.entity.remove();
            finishDebris(d, restore);
        }
        debris.clear();
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

    /** One currently-airborne debris block -- the live FallingBlock entity itself, plus enough to put the original block back where it's from once it's done. */
    private static class TornadoDebris {
        final FallingBlock entity;
        final Location originalLocation;
        final BlockData originalData;
        int ticksAlive = 0;

        TornadoDebris(FallingBlock entity, Location originalLocation, BlockData originalData) {
            this.entity = entity;
            this.originalLocation = originalLocation;
            this.originalData = originalData;
        }
    }
}
