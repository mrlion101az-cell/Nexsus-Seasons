package com.nexusuniverse.seasons;

import org.bukkit.command.BlockCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCommandExecuteEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * CycleLockManager corrects doDaylightCycle/doWeatherCycle back to what they should be, but only
 * on its own 1-second check -- a command block on a fast redstone clock re-running
 * "/gamerule doDaylightCycle false" (or "/time set 0", which isn't even a gamerule and
 * CycleLockManager doesn't touch at all) every tick could still cause a visible flicker or a
 * stuck/jittery clock in between corrections. This is the more direct half of "shut those
 * commands down": cancels the command outright, at the source, before it ever runs, for anyone
 * or anything that issues it -- command blocks specifically (the actual concern that prompted
 * this), but also console and players, since a command that shouldn't be allowed to run isn't
 * something that should depend on who typed it.
 *
 * Blocked while cycle-lock is enabled (see SeasonsConfig#cycleLockEnabled /
 * /nexusseasons cyclelock):
 *  - "/gamerule doDaylightCycle <value>" and "/gamerule doWeatherCycle <value>" -- a bare
 *    "/gamerule doDaylightCycle" with no value is a read, not a write, and stays allowed.
 *  - "/time set ..." and "/time add ..." -- jumping or nudging the clock directly sidesteps the
 *    gamerule entirely, so this needs its own check independent of the gamerule guard above.
 *  - "/weather clear|rain|thunder ..." -- forcing a specific weather state repeatedly (a command
 *    block spamming "/weather clear" every tick, say) defeats natural weather cycling just as
 *    effectively as disabling doWeatherCycle would, without ever touching that gamerule.
 *
 * Not blocked: reading any of the above (no arguments), and anything else entirely -- this only
 * intercepts the small, specific set of commands that actually fight the forced cycle.
 */
public class CycleLockGuard implements Listener {

    private final SeasonsConfig config;
    private final Logger logger;

    public CycleLockGuard(SeasonsConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    @EventHandler
    public void onCommandBlock(BlockCommandExecuteEvent event) {
        if (!config.cycleLockEnabled()) return;
        if (!isLockedCommand(event.getCommand())) return;

        event.setCancelled(true);
        BlockCommandSender sender = event.getBlock();
        logger.warning("NexusSeasons: blocked a command block at (" + sender.getBlock().getX() + ", "
                + sender.getBlock().getY() + ", " + sender.getBlock().getZ() + ") in " + sender.getBlock().getWorld().getName()
                + " from running \"" + event.getCommand() + "\" -- cycle lock is on. "
                + "Run /nexusseasons cyclelock off first if this command block should be allowed to do this.");
    }

    @EventHandler
    public void onConsoleCommand(ServerCommandEvent event) {
        if (!config.cycleLockEnabled()) return;
        if (!isLockedCommand(event.getCommand())) return;

        event.setCancelled(true);
        logger.warning("NexusSeasons: blocked a console command (\"" + event.getCommand()
                + "\") -- cycle lock is on. Run /nexusseasons cyclelock off first if this should be allowed.");
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!config.cycleLockEnabled()) return;
        // strip the leading "/" player commands carry that console/command-block commands don't
        String command = event.getMessage().startsWith("/") ? event.getMessage().substring(1) : event.getMessage();
        if (!isLockedCommand(command)) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage("§cThe day/night and weather cycle is locked (/nexusseasons cyclelock is ON) "
                + "-- that command won't run until it's switched off.");
    }

    /**
     * True if this command tries to disable day/night or weather progression, or jump the clock
     * directly. Matches loosely on purpose (tolerates a leading slash already being stripped or
     * not, and any amount of whitespace) since this runs against three different event types that
     * don't all format the command string identically.
     */
    private boolean isLockedCommand(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return false;
        String command = rawCommand.trim();
        if (command.startsWith("/")) command = command.substring(1);
        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0) return false;

        String base = parts[0].toLowerCase(Locale.ROOT);
        String sub = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "";

        if (base.equals("gamerule")) {
            boolean targetsCycleRule = sub.equals("dodaylightcycle") || sub.equals("doweathercycle");
            boolean hasValue = parts.length > 2; // no value = a read, not a write -- allowed
            return targetsCycleRule && hasValue;
        }
        if (base.equals("time")) {
            return sub.equals("set") || sub.equals("add");
        }
        if (base.equals("weather")) {
            return sub.equals("clear") || sub.equals("rain") || sub.equals("thunder");
        }
        return false;
    }
}
