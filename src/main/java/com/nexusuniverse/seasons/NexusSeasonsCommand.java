package com.nexusuniverse.seasons;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class NexusSeasonsCommand implements CommandExecutor {

    private final SeasonClock clock;
    private final SeasonsConfig config;
    private final Runnable onSilentChange;
    private final Runnable onAdvance;

    public NexusSeasonsCommand(SeasonClock clock, SeasonsConfig config, Runnable onSilentChange, Runnable onAdvance) {
        this.clock = clock;
        this.config = config;
        this.onSilentChange = onSilentChange;
        this.onAdvance = onAdvance;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (!sender.hasPermission("nexusseasons.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setyear" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /nexusseasons setyear <n>");
                    return true;
                }
                try {
                    clock.setYear(Integer.parseInt(args[1]));
                    onSilentChange.run();
                    sender.sendMessage("§aYear set to " + clock.year() + ".");
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cThat's not a number.");
                }
            }
            case "setseason" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /nexusseasons setseason <spring|summer|fall|winter>");
                    return true;
                }
                try {
                    clock.setSeason(Season.valueOf(args[1].toUpperCase()));
                    onSilentChange.run();
                    sender.sendMessage("§aSeason set to " + clock.season().coloredName() + "§a.");
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("§cUnknown season. Options: spring, summer, fall, winter");
                }
            }
            case "setday" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /nexusseasons setday <n>");
                    return true;
                }
                try {
                    clock.setDayOfSeason(Integer.parseInt(args[1]));
                    onSilentChange.run();
                    sender.sendMessage("§aDay set to " + clock.dayOfSeason() + "/" + clock.daysPerSeason() + ".");
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cThat's not a number.");
                }
            }
            case "advance" -> {
                onAdvance.run();
                sender.sendMessage("§aAdvanced one day -- now " + clock.season().coloredName()
                        + "§a, day " + clock.dayOfSeason() + "/" + clock.daysPerSeason() + ", year " + clock.year() + ".");
            }
            case "reload" -> {
                config.reload();
                sender.sendMessage("§aConfig reloaded.");
            }
            default -> sender.sendMessage("§cUnknown subcommand.");
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("§7--- Season Status ---");
        sender.sendMessage("§fSeason: " + clock.season().coloredName());
        sender.sendMessage("§fDay: §7" + clock.dayOfSeason() + "/" + clock.daysPerSeason());
        sender.sendMessage("§fYear: §7" + clock.year());
        if (config.customDayNightEnabled()) {
            sender.sendMessage("§fDay/Night: §7custom -- " + config.dayLengthMinutes() + "m day / "
                    + config.nightLengthMinutes() + "m night");
        } else {
            sender.sendMessage("§fDay/Night: §7vanilla");
        }
    }
}
