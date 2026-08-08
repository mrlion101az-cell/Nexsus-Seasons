package com.nexusuniverse.seasons.weather;

import com.nexusuniverse.seasons.SeasonsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Real, continuously scrolling rows of raised water -- a genuine wave TRAIN moving across the
 * open surface of any qualifying body of water, distinct from CoastalWaveManager (which handles
 * a single wave flooding onto land near the shore, then draining back out). This is the other
 * half of the ask: parallel ridge lines of real water blocks, gapped apart, all traveling
 * together across the water's own surface the way actual ocean swell looks from above.
 *
 * NOT a single advance-then-recede event -- a periodic pattern (ridge blocks, then a gap, then
 * the next ridge, repeating every waveTrain.ridge-width + waveTrain.gap-width blocks) that scrolls
 * continuously in the current wind direction. Ridges are genuinely raised WATER blocks (real
 * block placement, one to two blocks above the body's own surface -- never touches anything that
 * isn't already open water or air directly above it), reverted the instant a ridge scrolls past a
 * given column and a gap arrives there instead. Tracked per-column so only blocks that actually
 * change state get touched each pass, not the whole field.
 *
 * Runs per-player (only for players confirmed standing at/in a qualifying body, reusing the same
 * WaterBodyDetector size check the rest of this "crazy weather" layer uses) rather than as one
 * shared whole-world field -- simpler and bounded, at the cost of some redundant work if several
 * players are near the same stretch of water (harmless, just not perfectly efficient).
 *
 * Deliberately does NOT run anywhere a tsunami or hurricane storm surge is currently active --
 * those stay the single, massive, one-big-wave events they already were, rather than being
 * replaced by or fighting with a field of smaller rolling swell.
 */
public class WaveTrainManager {

    private final JavaPlugin plugin;
    private final SeasonsConfig config;
    private final WindManager wind;
    private final TsunamiManager tsunami;
    private final HurricaneManager hurricane;

    private final Map<UUID, PlayerField> fields = new HashMap<>();
    private long clockTicks;
    private BukkitTask task;

    public WaveTrainManager(JavaPlugin plugin, SeasonsConfig config, WindManager wind,
                             TsunamiManager tsunami, HurricaneManager hurricane) {
        this.plugin = plugin;
        this.config = config;
        this.wind = wind;
        this.tsunami = tsunami;
        this.hurricane = hurricane;
    }

    public void start() {
        if (!config.waveTrainEnabled()) return;
        int interval = config.waveTrainTickInterval();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (PlayerField field : fields.values()) {
            revertAll(field);
        }
        fields.clear();
    }

    private void tick() {
        clockTicks += config.waveTrainTickInterval();

        // don't run this near an active single-big-wave event -- see class doc
        boolean bigEventActive = tsunami.isActive() || hurricane.isActive();

        Set<UUID> stillEligible = new HashSet<>();
        if (!bigEventActive) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
                Location feet = player.getLocation();
                Location eye = player.getEyeLocation();
                Location waterSeed = feet.getBlock().getType() == Material.WATER ? feet
                        : eye.getBlock().getType() == Material.WATER ? eye : null;
                if (waterSeed == null) continue;
                if (!WaterBodyDetector.isLargeBody(waterSeed, config.wavesMinBodySize(), config.wavesBodyDetectionMaxBlocks())) continue;

                stillEligible.add(player.getUniqueId());
                PlayerField field = fields.computeIfAbsent(player.getUniqueId(), id -> new PlayerField());
                updateField(field, waterSeed);
            }
        }

        // anyone no longer eligible (left the water, body too small, a big event just started,
        // went offline) gets their ridges reverted and their field dropped entirely
        var iterator = fields.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!stillEligible.contains(entry.getKey())) {
                revertAll(entry.getValue());
                iterator.remove();
            }
        }
    }

    private void updateField(PlayerField field, Location waterSeed) {
        World world = waterSeed.getWorld();
        int surfaceY = waterSeed.getBlockY();

        Vector direction = wind != null ? wind.currentDirection() : new Vector(1, 0, 0);
        Vector perpendicular = new Vector(-direction.getZ(), 0, direction.getX());

        int halfSpan = config.waveTrainSpan() / 2;
        int reach = config.waveTrainReach();
        int ridgeWidth = config.waveTrainRidgeWidth();
        int period = ridgeWidth + config.waveTrainGapWidth();
        double phase = (clockTicks * config.waveTrainSpeed()) % period;

        double windStrength = wind != null ? wind.currentStrength() : 0.2;
        int height = (int) Math.round(config.waveTrainMinHeight()
                + windStrength * (config.waveTrainMaxHeight() - config.waveTrainMinHeight()));
        height = Math.max(1, height);

        Set<Long> nowRidge = new HashSet<>();
        int budget = config.waveTrainBlocksPerTick();
        int touched = 0;

        for (int along = -reach; along <= reach && touched < budget; along++) {
            double distanceAlong = along + phase;
            double mod = ((distanceAlong % period) + period) % period;
            if (mod >= ridgeWidth) continue; // this row is in the gap between ridges right now

            for (int across = -halfSpan; across <= halfSpan && touched < budget; across++) {
                int x = (int) Math.round(waterSeed.getX() + direction.getX() * along + perpendicular.getX() * across);
                int z = (int) Math.round(waterSeed.getZ() + direction.getZ() * along + perpendicular.getZ() * across);

                // only ever raise a column that's already genuinely open water at the surface --
                // never touches land or anything that isn't water/air
                if (world.getBlockAt(x, surfaceY, z).getType() != Material.WATER) continue;

                nowRidge.add(columnKey(x, z));
                for (int dy = 1; dy <= height; dy++) {
                    Block block = world.getBlockAt(x, surfaceY + dy, z);
                    if (block.getType() == Material.AIR || block.getType() == Material.CAVE_AIR) {
                        block.setType(Material.WATER);
                        field.raised.add(new RaisedBlock(x, surfaceY + dy, z, world.getName()));
                    }
                }
                touched++;
            }
        }

        // revert any previously-raised block whose column isn't a ridge anymore this pass -- the
        // gap has scrolled into where the ridge used to be
        field.raised.removeIf(raised -> {
            if (nowRidge.contains(columnKey(raised.x, raised.z))) return false;
            World raisedWorld = Bukkit.getWorld(raised.worldName);
            if (raisedWorld != null) {
                Block block = raisedWorld.getBlockAt(raised.x, raised.y, raised.z);
                if (block.getType() == Material.WATER) block.setType(Material.AIR);
            }
            return true;
        });
    }

    private void revertAll(PlayerField field) {
        for (RaisedBlock raised : field.raised) {
            World world = Bukkit.getWorld(raised.worldName);
            if (world == null) continue;
            Block block = world.getBlockAt(raised.x, raised.y, raised.z);
            if (block.getType() == Material.WATER) block.setType(Material.AIR);
        }
        field.raised.clear();
    }

    private long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static class PlayerField {
        final Set<RaisedBlock> raised = new HashSet<>();
    }

    private static class RaisedBlock {
        final int x;
        final int y;
        final int z;
        final String worldName;

        RaisedBlock(int x, int y, int z, String worldName) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.worldName = worldName;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RaisedBlock other)) return false;
            return x == other.x && y == other.y && z == other.z && worldName.equals(other.worldName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z, worldName);
        }
    }
}
