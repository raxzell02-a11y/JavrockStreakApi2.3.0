package id.javrock.streak;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class StreakCommandExecutor implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavrockStreakPlugin plugin;
    private final StreakManagerService streakService;

    public StreakCommandExecutor(JavrockStreakPlugin plugin, StreakManagerService streakService) {
        this.plugin = plugin;
        this.streakService = streakService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String prefix = plugin.getConfig().getString("messages.prefix", "&9&lᴊᴀᴠʀᴏᴄᴋ &8» ");
        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        if (cmdName.equals("streakadmin") || cmdName.equals("sadmin")) {
            return handleAdminCommand(sender, label, args, prefix);
        }

        if (cmdName.equals("streaktop") || cmdName.equals("topstreak")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Perintah ini hanya untuk pemain.");
                return true;
            }
            streakService.sendTopStreaks(player);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("javrockstreak.admin")) {
                sender.sendMessage(LEGACY.deserialize(prefix + plugin.getConfig().getString("messages.no-permission", "&cᴛɪᴅᴀᴋ ᴀᴅᴀ ɪᴢɪɴ.")));
                return true;
            }
            plugin.reloadPluginConfig();
            sender.sendMessage(LEGACY.deserialize(prefix + plugin.getConfig().getString("messages.reloaded", "&bᴋᴏɴꜰɪɢᴜʀᴀꜱɪ ᴅɪᴍᴜᴀᴛ ᴜʟᴀɴɢ.")));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Gunakan perintah ini di dalam game.");
            return true;
        }

        if (args.length == 0) {
            streakService.openModeSelectorGui(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "api", "start" -> streakService.startSoloStreakApi(player);
            case "duo" -> {
                if (args.length < 2) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/ꜱᴛʀᴇᴀᴋ ᴅᴜᴏ <ᴘʟᴀʏᴇʀɴᴀᴍᴇ> &8| &3/ꜱᴛʀᴇᴀᴋ ᴅᴜᴏ ʀᴇꜱᴛᴏʀᴇ"));
                    return true;
                }
                if (args[1].equalsIgnoreCase("restore")) {
                    streakService.restoreStreakForMode(player, StreakGroupData.Mode.DUO);
                } else {
                    streakService.inviteDuoPartner(player, args[1]);
                }
            }
            case "accept", "terima" -> streakService.acceptDuoInvite(player);
            case "team" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("api")) {
                    streakService.startTeamStreakApi(player, false);
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("restore")) {
                    streakService.restoreStreakForMode(player, StreakGroupData.Mode.TEAM);
                } else {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/ꜱᴛʀᴇᴀᴋ ᴛᴇᴀᴍ ᴀᴘɪ &8| &3/ꜱᴛʀᴇᴀᴋ ᴛᴇᴀᴍ ʀᴇꜱᴛᴏʀᴇ"));
                }
            }
            case "restore", "pulihkan" -> streakService.restoreStreakForMode(player, StreakGroupData.Mode.SOLO);
            case "reward", "rewards", "hadiah", "gui" -> {
                if (args.length >= 2) {
                    String m = args[1].toLowerCase(Locale.ROOT);
                    if (m.equals("solo")) {
                        streakService.openModeRewardsGui(player, StreakGroupData.Mode.SOLO);
                        return true;
                    } else if (m.equals("duo")) {
                        streakService.openModeRewardsGui(player, StreakGroupData.Mode.DUO);
                        return true;
                    } else if (m.equals("team")) {
                        streakService.openModeRewardsGui(player, StreakGroupData.Mode.TEAM);
                        return true;
                    }
                }
                streakService.openModeSelectorGui(player);
            }
            case "top" -> streakService.sendTopStreaks(player);
            case "info", "status" -> {
                List<StreakGroupData> all = streakService.getPlayerGroups(player.getUniqueId());
                if (all.isEmpty()) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&7ᴋᴀᴍᴜ ʙᴇʟᴜᴍ ᴍᴇᴍɪʟɪᴋɪ ꜱᴛʀᴇᴀᴋ. ᴋᴇᴛɪᴋ &3/ꜱᴛʀᴇᴀᴋ ᴀᴘɪ&7, &3/ꜱᴛʀᴇᴀᴋ ᴅᴜᴏ <ᴘʟᴀʏᴇʀ>&7, ᴀᴛᴀᴜ &3/ꜱᴛʀᴇᴀᴋ ᴛᴇᴀᴍ ᴀᴘɪ&7."));
                } else {
                    for (StreakGroupData data : all) {
                        streakService.evaluateGroupStatus(data);
                        String state = (data.isActive() && !data.isDead()) ? "&aᴀᴋᴛɪꜰ" : (data.isDead() ? "&cᴍᴀᴛɪ" : "&7ʙᴇʟᴜᴍ ᴍᴜʟᴀɪ");
                        sender.sendMessage(LEGACY.deserialize(prefix + "&7ᴍᴏᴅᴇ: &3" + data.getModeBadge() + " &8| &7ꜱᴛʀᴇᴀᴋ: &b" + data.getStreak() + " ʜᴀʀɪ &8| &7ꜱᴛᴀᴛᴜꜱ: " + state + " &8| &7ʀᴇꜱᴛᴏʀᴇ: &3" + data.getRestoresRemaining() + "/" + streakService.getMaxRestores()));
                    }
                }
            }
            default -> sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/ꜱᴛʀᴇᴀᴋ api &8| &3/ꜱᴛʀᴇᴀᴋ duo <player> &8| &3/ꜱᴛʀᴇᴀᴋ duo restore &8| &3/ꜱᴛʀᴇᴀᴋ team api &8| &3/ꜱᴛʀᴇᴀᴋ team restore &8| &3/ꜱᴛʀᴇᴀᴋ reward"));
        }
        return true;
    }

    private boolean handleAdminCommand(CommandSender sender, String label, String[] args, String prefix) {
        if (!sender.hasPermission("javrockstreak.admin")) {
            sender.sendMessage(LEGACY.deserialize(prefix + plugin.getConfig().getString("messages.no-permission", "&cᴛɪᴅᴀᴋ ᴀᴅᴀ ɪᴢɪɴ.")));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/" + label + " <addday|setday|setrestore|kill|reset|info|teamapi|teamrestore|duo|duorestore> ..."));
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        if (action.equals("teamapi")) {
            String memberName = args.length >= 2 ? args[1] : (sender instanceof Player p ? p.getName() : null);
            if (memberName == null) {
                sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/" + label + " teamapi <player>"));
                return true;
            }
            Player ref = (sender instanceof Player p && args.length < 2) ? p : null;
            if (streakService.adminStartTeamApi(ref, memberName)) {
                sender.sendMessage(LEGACY.deserialize(prefix + "&a✔ ꜱᴛʀᴇᴀᴋ ᴛᴇᴀᴍ ᴜɴᴛᴜᴋ &3" + memberName + " &aʙᴇʀʜᴀꜱɪʟ ᴅɪᴀᴋᴛɪꜰᴋᴀɴ (ʙʏᴘᴀꜱꜱ ꜱʏᴀʀᴀᴛ ᴀɴɢɢᴏᴛᴀ)."));
            } else {
                sender.sendMessage(LEGACY.deserialize(prefix + "&cᴘʟᴀʏᴇʀ &3" + memberName + " &cᴛɪᴅᴀᴋ ᴛᴇʀɢᴀʙᴜɴɢ ᴅɪ ᴛᴇᴀᴍ ʙᴇᴛᴛᴇʀᴛᴇᴀᴍꜱ ᴀᴛᴀᴜ ᴘʟᴜɢɪɴ ʙᴇʟᴜᴍ ᴀᴋᴛɪꜰ."));
            }
            return true;
        }

        if (action.equals("teamrestore") || action.equals("duorestore")) {
            if (args.length < 2) {
                sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/" + label + " " + action + " <player>"));
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (streakService.adminRestoreForPlayer(target.getUniqueId())) {
                sender.sendMessage(LEGACY.deserialize(prefix + "&a✔ ꜱᴛʀᴇᴀᴋ ᴜɴᴛᴜᴋ &3" + args[1] + " &aʙᴇʀʜᴀꜱɪʟ ᴅɪᴘᴜʟɪʜᴋᴀɴ."));
            } else {
                sender.sendMessage(LEGACY.deserialize(prefix + "&cɢᴀɢᴀʟ ᴍᴇᴍᴜʟɪʜᴋᴀɴ (ꜱᴛʀᴇᴀᴋ ᴛɪᴅᴀᴋ ᴍᴀᴛɪ ᴀᴛᴀᴜ ꜱɪꜱᴀ ʀᴇꜱᴛᴏʀᴇ 0)."));
            }
            return true;
        }

        if (action.equals("duo")) {
            if (args.length < 3) {
                sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/" + label + " duo <player1> <player2>"));
                return true;
            }
            Player p1 = Bukkit.getPlayerExact(args[1]);
            Player p2 = Bukkit.getPlayerExact(args[2]);
            if (p1 == null || p2 == null) {
                sender.sendMessage(LEGACY.deserialize(prefix + "&cᴋᴇᴅᴜᴀ ᴘʟᴀʏᴇʀ ʜᴀʀᴜꜱ ᴏɴʟɪɴᴇ."));
                return true;
            }
            if (streakService.adminCreateDuo(p1, p2)) {
                sender.sendMessage(LEGACY.deserialize(prefix + "&a✔ ꜱᴛʀᴇᴀᴋ ᴅᴜᴏ &3" + p1.getName() + " & &3" + p2.getName() + " &aᴅɪᴀᴋᴛɪꜰᴋᴀɴ."));
            } else {
                sender.sendMessage(LEGACY.deserialize(prefix + "&cɢᴀɢᴀʟ ᴍᴇᴍʙᴜᴀᴛ ꜱᴛʀᴇᴀᴋ ᴅᴜᴏ."));
            }
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/" + label + " <addday|setday|setrestore|kill|reset|info> <player> [jumlah]"));
            return true;
        }

        String targetName = args[1];
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target.getName() == null && !target.hasPlayedBefore() && Bukkit.getPlayerExact(targetName) == null) {
            sender.sendMessage(LEGACY.deserialize(prefix + "&cᴘʟᴀʏᴇʀ &3" + targetName + " &cᴛɪᴅᴀᴋ ᴅɪᴛᴇᴍᴜᴋᴀɴ."));
            return true;
        }
        String resolvedName = target.getName() != null ? target.getName() : targetName;

        switch (action) {
            case "addday", "tambahhari", "add" -> {
                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(LEGACY.deserialize(prefix + "&cᴀɴɢᴋᴀ ʜᴀʀɪ ᴛɪᴅᴀᴋ ᴠᴀʟɪᴅ."));
                        return true;
                    }
                }
                streakService.adminAddDays(target.getUniqueId(), resolvedName, amount);
                StreakGroupData d = streakService.getPrimaryPlayerGroup(target.getUniqueId());
                int total = d != null ? d.getStreak() : amount;
                sender.sendMessage(LEGACY.deserialize(prefix + "&a✔ ʙᴇʀʜᴀꜱɪʟ ᴍᴇɴᴀᴍʙᴀʜ &b+" + amount + " ʜᴀʀɪ &aᴜɴᴛᴜᴋ &3" + resolvedName + " &7(ᴛᴏᴛᴀʟ: &b" + total + " ʜᴀʀɪ&7)."));
            }
            case "setday", "sethari", "set" -> {
                if (args.length < 3) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/" + label + " setday <player> <hari>"));
                    return true;
                }
                int exact;
                try {
                    exact = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&cᴀɴɢᴋᴀ ʜᴀʀɪ ᴛɪᴅᴀᴋ ᴠᴀʟɪᴅ."));
                    return true;
                }
                streakService.adminSetDays(target.getUniqueId(), resolvedName, exact);
                sender.sendMessage(LEGACY.deserialize(prefix + "&a✔ ꜱᴛʀᴇᴀᴋ &3" + resolvedName + " &aᴅɪᴀᴛᴜʀ ᴋᴇ &b" + exact + " ʜᴀʀɪ&a."));
            }
            case "setrestore" -> {
                if (args.length < 3) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/" + label + " setrestore <player> <jumlah>"));
                    return true;
                }
                int val;
                try {
                    val = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&cᴀɴɢᴋᴀ ᴛɪᴅᴀᴋ ᴠᴀʟɪᴅ."));
                    return true;
                }
                if (streakService.adminSetRestores(target.getUniqueId(), val)) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&a✔ ꜱɪꜱᴀ ʀᴇꜱᴛᴏʀᴇ &3" + resolvedName + " &aᴅɪᴀᴛᴜʀ ᴋᴇ &b" + val + "&a."));
                } else {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&cᴘʟᴀʏᴇʀ ʙᴇʟᴜᴍ ᴍᴇᴍɪʟɪᴋɪ ᴅᴀᴛᴀ ꜱᴛʀᴇᴀᴋ."));
                }
            }
            case "kill", "matikan" -> {
                if (streakService.adminKillStreak(target.getUniqueId())) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&e💀 ꜱᴛʀᴇᴀᴋ &3" + resolvedName + " &eᴅɪᴍᴀᴛɪᴋᴀɴ ᴜɴᴛᴜᴋ ᴛᴇꜱ ʀᴇꜱᴛᴏʀᴇ."));
                } else {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&cᴘʟᴀʏᴇʀ ʙᴇʟᴜᴍ ᴍᴇᴍɪʟɪᴋɪ ᴅᴀᴛᴀ ꜱᴛʀᴇᴀᴋ."));
                }
            }
            case "reset" -> {
                if (streakService.adminResetStreak(target.getUniqueId())) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&a✔ ᴅᴀᴛᴀ ꜱᴛʀᴇᴀᴋ &3" + resolvedName + " &aᴅɪʜᴀᴘᴜꜱ/ᴅɪʀᴇꜱᴇᴛ."));
                } else {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&cᴘʟᴀʏᴇʀ ʙᴇʟᴜᴍ ᴍᴇᴍɪʟɪᴋɪ ᴅᴀᴛᴀ ꜱᴛʀᴇᴀᴋ."));
                }
            }
            case "info" -> {
                List<StreakGroupData> all = streakService.getPlayerGroups(target.getUniqueId());
                if (all.isEmpty()) {
                    sender.sendMessage(LEGACY.deserialize(prefix + "&7" + resolvedName + " ʙᴇʟᴜᴍ ᴍᴇᴍɪʟɪᴋɪ ꜱᴛʀᴇᴀᴋ."));
                } else {
                    for (StreakGroupData d : all) {
                        streakService.evaluateGroupStatus(d);
                        sender.sendMessage(LEGACY.deserialize(prefix + "&3" + resolvedName + " &8| &7ᴍᴏᴅᴇ: &b" + d.getModeBadge() + " &8| &7ʜᴀʀɪ: &b" + d.getStreak() + " &8| &7ᴍᴀᴛɪ: &c" + d.isDead() + " &8| &7ʀᴇꜱᴛᴏʀᴇ: &3" + d.getRestoresRemaining()));
                    }
                }
            }
            default -> sender.sendMessage(LEGACY.deserialize(prefix + "&fɢᴜɴᴀᴋᴀɴ: &3/" + label + " <addday|setday|setrestore|kill|reset|info> <player> [jumlah]"));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        if (cmdName.equals("streakadmin") || cmdName.equals("sadmin")) {
            if (!sender.hasPermission("javrockstreak.admin")) {
                return Collections.emptyList();
            }
            if (args.length == 1) {
                String p = args[0].toLowerCase(Locale.ROOT);
                return List.of("addday", "setday", "setrestore", "kill", "reset", "info", "teamapi", "teamrestore", "duo", "duorestore").stream()
                        .filter(s -> s.startsWith(p))
                        .collect(Collectors.toList());
            }
            if (args.length == 2 || args.length == 3) {
                String p = args[args.length - 1].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(p))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }

        if (cmdName.equals("streaktop")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("api", "duo", "accept", "team", "restore", "reward", "hadiah", "info", "top"));
            if (sender.hasPermission("javrockstreak.admin")) {
                options.add("reload");
            }
            String p = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(s -> s.startsWith(p)).collect(Collectors.toList());
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("duo")) {
                String p = args[1].toLowerCase(Locale.ROOT);
                List<String> res = new ArrayList<>();
                if ("restore".startsWith(p)) res.add("restore");
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    if (!pl.getName().equalsIgnoreCase(sender.getName()) && pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) {
                        res.add(pl.getName());
                    }
                }
                return res;
            }
            if (args[0].equalsIgnoreCase("team")) {
                String p = args[1].toLowerCase(Locale.ROOT);
                return List.of("api", "restore").stream().filter(s -> s.startsWith(p)).collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("reward") || args[0].equalsIgnoreCase("rewards") || args[0].equalsIgnoreCase("hadiah")) {
                String p = args[1].toLowerCase(Locale.ROOT);
                return List.of("solo", "duo", "team").stream().filter(s -> s.startsWith(p)).collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
