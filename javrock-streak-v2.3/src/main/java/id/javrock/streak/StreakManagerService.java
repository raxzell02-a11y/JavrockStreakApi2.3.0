package id.javrock.streak;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class StreakManagerService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    public enum GuiScreen {
        MODE_SELECTOR,
        SOLO_REWARDS,
        DUO_REWARDS,
        TEAM_REWARDS
    }

    public static class StreakGuiHolder implements InventoryHolder {
        private final GuiScreen screen;

        public StreakGuiHolder(GuiScreen screen) {
            this.screen = screen;
        }

        public GuiScreen getScreen() {
            return screen;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 9);
        }
    }

    private static class PendingInvite {
        final UUID senderUuid;
        final String senderName;
        final long expiresAtMillis;

        PendingInvite(UUID senderUuid, String senderName, long expiresAtMillis) {
            this.senderUuid = senderUuid;
            this.senderName = senderName;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private final JavrockStreakPlugin plugin;
    private final BetterTeamsHook betterTeamsHook;

    private final Map<String, StreakGroupData> groups = new ConcurrentHashMap<>();
    private final Map<UUID, EnumMap<StreakGroupData.Mode, String>> playerModeIndex = new ConcurrentHashMap<>();
    private final Map<UUID, PendingInvite> pendingInvites = new ConcurrentHashMap<>();

    private File dataFile;
    private YamlConfiguration dataYaml;

    public StreakManagerService(JavrockStreakPlugin plugin) {
        this.plugin = plugin;
        this.betterTeamsHook = new BetterTeamsHook();
        loadData();
    }

    public ZoneId getZoneId() {
        String tz = plugin.getConfig().getString("settings.timezone", "Asia/Jakarta");
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Jakarta");
        }
    }

    public LocalDate today() {
        return LocalDate.now(getZoneId());
    }

    public int getMaxRestores() {
        return Math.max(1, plugin.getConfig().getInt("settings.max-restores", 3));
    }

    public int getMinTeamMembers() {
        return Math.max(1, plugin.getConfig().getInt("settings.min-team-members", 5));
    }

    public void loadData() {
        groups.clear();
        playerModeIndex.clear();
        dataFile = new File(plugin.getDataFolder(), "streaks.yml");
        if (!dataFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Gagal membuat streaks.yml: " + e.getMessage());
            }
        }
        dataYaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = dataYaml.getConfigurationSection("groups");
        if (sec == null) {
            return;
        }
        for (String id : sec.getKeys(false)) {
            ConfigurationSection g = sec.getConfigurationSection(id);
            if (g == null) continue;
            StreakGroupData.Mode mode;
            try {
                mode = StreakGroupData.Mode.valueOf(g.getString("mode", "SOLO").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                mode = StreakGroupData.Mode.SOLO;
            }
            String displayName = g.getString("display-name", "Player");
            StreakGroupData data = new StreakGroupData(id, mode, displayName, getMaxRestores());
            data.setStreak(g.getInt("streak", 0));
            data.setActive(g.getBoolean("active", false));
            data.setDead(g.getBoolean("dead", false));
            data.setRestoresRemaining(g.getInt("restores-remaining", getMaxRestores()));
            data.setLastCompletedDate(g.getString("last-completed-date", ""));
            data.setTodayCheckInDate(g.getString("today-checkin-date", ""));

            List<UUID> members = new ArrayList<>();
            Map<UUID, String> names = new HashMap<>();
            ConfigurationSection memSec = g.getConfigurationSection("members");
            if (memSec != null) {
                for (String uuidStr : memSec.getKeys(false)) {
                    try {
                        UUID u = UUID.fromString(uuidStr);
                        members.add(u);
                        names.put(u, memSec.getString(uuidStr, "Player"));
                        playerModeIndex
                                .computeIfAbsent(u, k -> new EnumMap<>(StreakGroupData.Mode.class))
                                .put(mode, id);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            data.setMembers(members);
            data.setMemberNames(names);

            Set<UUID> checkedToday = new HashSet<>();
            for (String uStr : g.getStringList("today-checked-in")) {
                try {
                    checkedToday.add(UUID.fromString(uStr));
                } catch (IllegalArgumentException ignored) {
                }
            }
            data.setTodayCheckedIn(checkedToday);
            data.setClaimedMilestones(new HashSet<>(g.getIntegerList("claimed-milestones")));

            groups.put(id, data);
        }
    }

    public synchronized void saveData() {
        if (dataYaml == null) {
            dataYaml = new YamlConfiguration();
        }
        dataYaml.set("groups", null);
        for (Map.Entry<String, StreakGroupData> entry : groups.entrySet()) {
            String path = "groups." + entry.getKey();
            StreakGroupData d = entry.getValue();
            dataYaml.set(path + ".mode", d.getMode().name());
            dataYaml.set(path + ".display-name", d.getDisplayName());
            dataYaml.set(path + ".streak", d.getStreak());
            dataYaml.set(path + ".active", d.isActive());
            dataYaml.set(path + ".dead", d.isDead());
            dataYaml.set(path + ".restores-remaining", d.getRestoresRemaining());
            dataYaml.set(path + ".last-completed-date", d.getLastCompletedDate());
            dataYaml.set(path + ".today-checkin-date", d.getTodayCheckInDate());

            for (UUID u : d.getMembers()) {
                dataYaml.set(path + ".members." + u, d.getMemberNames().getOrDefault(u, "Player"));
            }
            List<String> checkedList = d.getTodayCheckedIn().stream().map(UUID::toString).collect(Collectors.toList());
            dataYaml.set(path + ".today-checked-in", checkedList);
            dataYaml.set(path + ".claimed-milestones", new ArrayList<>(d.getClaimedMilestones()));
        }
        try {
            dataYaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Gagal menyimpan streaks.yml: " + e.getMessage());
        }
    }

    public StreakGroupData getPlayerGroup(UUID uuid, StreakGroupData.Mode mode) {
        EnumMap<StreakGroupData.Mode, String> map = playerModeIndex.get(uuid);
        if (map == null) return null;
        String gid = map.get(mode);
        if (gid == null) return null;
        return groups.get(gid);
    }

    public List<StreakGroupData> getPlayerGroups(UUID uuid) {
        EnumMap<StreakGroupData.Mode, String> map = playerModeIndex.get(uuid);
        if (map == null || map.isEmpty()) return List.of();
        List<StreakGroupData> result = new ArrayList<>();
        for (String gid : map.values()) {
            StreakGroupData d = groups.get(gid);
            if (d != null) {
                result.add(d);
            }
        }
        return result;
    }

    public StreakGroupData getPrimaryPlayerGroup(UUID uuid) {
        for (StreakGroupData.Mode m : List.of(StreakGroupData.Mode.SOLO, StreakGroupData.Mode.DUO, StreakGroupData.Mode.TEAM)) {
            StreakGroupData d = getPlayerGroup(uuid, m);
            if (d != null) return d;
        }
        return null;
    }

    public Set<StreakGroupData.Mode> getActiveOrPendingModes(UUID uuid) {
        Set<StreakGroupData.Mode> activeModes = new HashSet<>();
        for (StreakGroupData d : getPlayerGroups(uuid)) {
            evaluateGroupStatus(d);
            if ((d.isActive() && !d.isDead()) || (d.isDead() && d.getRestoresRemaining() > 0)) {
                activeModes.add(d.getMode());
            }
        }
        return activeModes;
    }

    public boolean canActivateMode(UUID playerId, StreakGroupData.Mode targetMode, boolean isDuoTargetConflict) {
        Set<StreakGroupData.Mode> currentModes = getActiveOrPendingModes(playerId);

        if (currentModes.contains(targetMode)) {
            return false;
        }

        if (targetMode == StreakGroupData.Mode.SOLO && currentModes.contains(StreakGroupData.Mode.DUO)) {
            return false;
        }
        if (targetMode == StreakGroupData.Mode.DUO && currentModes.contains(StreakGroupData.Mode.SOLO)) {
            return false;
        }

        if (targetMode == StreakGroupData.Mode.DUO && isDuoTargetConflict) {
            return false;
        }

        if (currentModes.size() >= 2) {
            return false;
        }

        return true;
    }

    private void registerGroup(StreakGroupData data) {
        groups.put(data.getId(), data);
        for (UUID u : data.getMembers()) {
            playerModeIndex
                    .computeIfAbsent(u, k -> new EnumMap<>(StreakGroupData.Mode.class))
                    .put(data.getMode(), data.getId());
        }
    }

    private void unregisterGroup(StreakGroupData data) {
        if (data == null) return;
        groups.remove(data.getId());
        for (UUID u : data.getMembers()) {
            EnumMap<StreakGroupData.Mode, String> map = playerModeIndex.get(u);
            if (map != null && data.getId().equals(map.get(data.getMode()))) {
                map.remove(data.getMode());
                if (map.isEmpty()) {
                    playerModeIndex.remove(u);
                }
            }
        }
    }

    private void syncTeamMembersIfNeeded(StreakGroupData data) {
        if (data == null || data.getMode() != StreakGroupData.Mode.TEAM || data.getMembers().isEmpty()) {
            return;
        }
        BetterTeamsHook.TeamSnapshot snap = betterTeamsHook.getTeamByPlayerUuid(data.getMembers().get(0));
        if (snap != null && !snap.memberUuids.isEmpty()) {
            data.setDisplayName(snap.teamName);
            for (UUID oldU : data.getMembers()) {
                if (!snap.memberUuids.contains(oldU)) {
                    EnumMap<StreakGroupData.Mode, String> map = playerModeIndex.get(oldU);
                    if (map != null && data.getId().equals(map.get(StreakGroupData.Mode.TEAM))) {
                        map.remove(StreakGroupData.Mode.TEAM);
                        if (map.isEmpty()) {
                            playerModeIndex.remove(oldU);
                        }
                    }
                }
            }
            Map<UUID, String> updatedNames = new HashMap<>(data.getMemberNames());
            for (UUID u : snap.memberUuids) {
                OfflinePlayer off = Bukkit.getOfflinePlayer(u);
                if (off.getName() != null) {
                    updatedNames.put(u, off.getName());
                } else {
                    updatedNames.putIfAbsent(u, "Member");
                }
                playerModeIndex
                        .computeIfAbsent(u, k -> new EnumMap<>(StreakGroupData.Mode.class))
                        .put(StreakGroupData.Mode.TEAM, data.getId());
            }
            data.setMembers(snap.memberUuids);
            data.setMemberNames(updatedNames);
        }
    }

    public void evaluateGroupStatus(StreakGroupData data) {
        if (data == null || !data.isActive() || data.isDead()) {
            return;
        }
        syncTeamMembersIfNeeded(data);

        LocalDate now = today();
        String todayStr = now.toString();

        if (!todayStr.equals(data.getTodayCheckInDate())) {
            data.getTodayCheckedIn().clear();
            data.setTodayCheckInDate(todayStr);
        }

        if (data.getLastCompletedDate() == null || data.getLastCompletedDate().isEmpty()) {
            return;
        }

        try {
            LocalDate lastCompleted = LocalDate.parse(data.getLastCompletedDate());
            long diff = ChronoUnit.DAYS.between(lastCompleted, now);
            if (diff >= 2) {
                data.setActive(false);
                data.setDead(true);
            }
        } catch (Exception ignored) {
        }
    }

    public void handlePlayerJoin(Player player) {
        List<StreakGroupData> playerGroups = getPlayerGroups(player.getUniqueId());
        if (playerGroups.isEmpty()) {
            return;
        }

        LocalDate now = today();
        String todayStr = now.toString();

        for (StreakGroupData data : playerGroups) {
            data.getMemberNames().put(player.getUniqueId(), player.getName());
            evaluateGroupStatus(data);

            if (!data.isActive() && !data.isDead()) {
                continue;
            }

            if (data.isDead()) {
                if (plugin.getConfig().getBoolean("settings.notify-on-join", true)) {
                    if (data.getRestoresRemaining() > 0) {
                        sendMessage(player, formatGroupMsg("messages.streak-died", data));
                    } else {
                        sendMessage(player, formatGroupMsg("messages.streak-died-no-restore", data));
                    }
                }
                continue;
            }

            if (todayStr.equals(data.getLastCompletedDate())) {
                continue;
            }

            if (!todayStr.equals(data.getTodayCheckInDate())) {
                data.getTodayCheckedIn().clear();
                data.setTodayCheckInDate(todayStr);
            }
            boolean newlyChecked = data.getTodayCheckedIn().add(player.getUniqueId());

            int totalMembers = Math.max(1, data.getMembers().size());
            int checkedCount = 0;
            for (UUID memberId : data.getMembers()) {
                if (data.getTodayCheckedIn().contains(memberId)) {
                    checkedCount++;
                }
            }

            if (checkedCount >= totalMembers) {
                data.setStreak(data.getStreak() + 1);
                data.setLastCompletedDate(todayStr);

                String incMsg = formatGroupMsg("messages.daily-increment", data);
                for (UUID memberId : data.getMembers()) {
                    Player onlineMember = Bukkit.getPlayer(memberId);
                    if (onlineMember != null && onlineMember.isOnline()) {
                        if (plugin.getConfig().getBoolean("settings.notify-on-join", true)) {
                            sendMessage(onlineMember, incMsg);
                        }
                        checkAndGrantMilestones(onlineMember, data);
                    }
                }
            } else if (newlyChecked && plugin.getConfig().getBoolean("settings.notify-on-join", true)) {
                String waitMsg = formatGroupMsg("messages.daily-checkin-part", data)
                        .replace("%checked%", String.valueOf(checkedCount))
                        .replace("%total%", String.valueOf(totalMembers));
                sendMessage(player, waitMsg);
            }
        }

        saveData();
    }

    public void startSoloStreakApi(Player player) {
        StreakGroupData existingSolo = getPlayerGroup(player.getUniqueId(), StreakGroupData.Mode.SOLO);
        if (existingSolo != null) {
            evaluateGroupStatus(existingSolo);
            if (existingSolo.isActive() && !existingSolo.isDead()) {
                sendMessage(player, formatGroupMsg("messages.already-active", existingSolo));
                return;
            }
            if (existingSolo.isDead() && existingSolo.getRestoresRemaining() > 0) {
                sendMessage(player, formatGroupMsg("messages.need-restore-first", existingSolo));
                return;
            }
            unregisterGroup(existingSolo);
        }

        StreakGroupData existingDuo = getPlayerGroup(player.getUniqueId(), StreakGroupData.Mode.DUO);
        if (existingDuo != null) {
            evaluateGroupStatus(existingDuo);
            if ((existingDuo.isActive() && !existingDuo.isDead()) || (existingDuo.isDead() && existingDuo.getRestoresRemaining() > 0)) {
                sendMessage(player, plugin.getConfig().getString(
                        "messages.cannot-combine-solo-duo",
                        "&c🔥 ᴋᴀᴍᴜ ᴛɪᴅᴀᴋ ʙɪꜱᴀ ᴍᴇɴɢᴀᴍʙɪʟ ᴍᴏᴅᴇ &6ꜱᴏʟᴏ &cᴅᴀɴ &dᴅᴜᴏ &cꜱᴇᴄᴀʀᴀ ʙᴇʀꜱᴀᴍᴀᴀɴ!"
                ));
                return;
            }
            unregisterGroup(existingDuo);
        }

        if (getActiveOrPendingModes(player.getUniqueId()).size() >= 2) {
            sendMessage(player, plugin.getConfig().getString(
                    "messages.max-modes-reached",
                    "&cᴋᴀᴍᴜ ꜱᴜᴅᴀʜ ᴍᴇɴɢɢᴜɴᴀᴋᴀɴ ᴍᴀᴋꜱɪᴍᴀʟ &b2 ᴍᴏᴅᴇ ꜱᴛʀᴇᴀᴋ &cꜱᴇᴋᴀʟɪɢᴜꜱ."
            ));
            return;
        }

        String id = "solo_" + player.getUniqueId();
        StreakGroupData solo = new StreakGroupData(id, StreakGroupData.Mode.SOLO, player.getName(), getMaxRestores());
        solo.setMembers(List.of(player.getUniqueId()));
        solo.setMemberNames(Map.of(player.getUniqueId(), player.getName()));
        solo.setStreak(1);
        solo.setActive(true);
        solo.setDead(false);
        solo.setRestoresRemaining(getMaxRestores());
        String todayStr = today().toString();
        solo.setLastCompletedDate(todayStr);
        solo.setTodayCheckInDate(todayStr);
        solo.getTodayCheckedIn().add(player.getUniqueId());

        registerGroup(solo);
        sendMessage(player, formatGroupMsg("messages.started", solo));
        checkAndGrantMilestones(player, solo);
        saveData();
    }

    public void inviteDuoPartner(Player sender, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            sendMessage(sender, plugin.getConfig().getString("messages.player-offline", "&cᴘʟᴀʏᴇʀ &3%target% &cᴏꜰꜰʟɪɴᴇ.")
                    .replace("%target%", targetName));
            return;
        }
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sendMessage(sender, plugin.getConfig().getString("messages.invite-self", "&cᴛɪᴅᴀᴋ ʙɪꜱᴀ ᴍᴇɴɢᴜɴᴅᴀɴɢ ᴅɪʀɪ ꜱᴇɴᴅɪʀɪ."));
            return;
        }

        StreakGroupData senderDuo = getPlayerGroup(sender.getUniqueId(), StreakGroupData.Mode.DUO);
        if (senderDuo != null) {
            evaluateGroupStatus(senderDuo);
            if (senderDuo.isActive() && !senderDuo.isDead()) {
                sendMessage(sender, formatGroupMsg("messages.already-active", senderDuo));
                return;
            }
            if (senderDuo.isDead() && senderDuo.getRestoresRemaining() > 0) {
                sendMessage(sender, formatGroupMsg("messages.need-restore-first", senderDuo));
                return;
            }
        }

        StreakGroupData senderSolo = getPlayerGroup(sender.getUniqueId(), StreakGroupData.Mode.SOLO);
        if (senderSolo != null) {
            evaluateGroupStatus(senderSolo);
            if ((senderSolo.isActive() && !senderSolo.isDead()) || (senderSolo.isDead() && senderSolo.getRestoresRemaining() > 0)) {
                sendMessage(sender, plugin.getConfig().getString(
                        "messages.cannot-combine-solo-duo",
                        "&c🔥 ᴋᴀᴍᴜ ᴛɪᴅᴀᴋ ʙɪꜱᴀ ᴍᴇɴɢᴀᴍʙɪʟ ᴍᴏᴅᴇ &6ꜱᴏʟᴏ &cᴅᴀɴ &dᴅᴜᴏ &cꜱᴇᴄᴀʀᴀ ʙᴇʀꜱᴀᴍᴀᴀɴ!"
                ));
                return;
            }
        }

        if (getActiveOrPendingModes(sender.getUniqueId()).size() >= 2) {
            sendMessage(sender, plugin.getConfig().getString(
                    "messages.max-modes-reached",
                    "&cᴋᴀᴍᴜ ꜱᴜᴅᴀʜ ᴍᴇɴɢɢᴜɴᴀᴋᴀɴ ᴍᴀᴋꜱɪᴍᴀʟ &b2 ᴍᴏᴅᴇ ꜱᴛʀᴇᴀᴋ &cꜱᴇᴋᴀʟɪɢᴜꜱ."
            ));
            return;
        }

        StreakGroupData targetDuo = getPlayerGroup(target.getUniqueId(), StreakGroupData.Mode.DUO);
        if (targetDuo != null) {
            evaluateGroupStatus(targetDuo);
            if ((targetDuo.isActive() && !targetDuo.isDead()) || (targetDuo.isDead() && targetDuo.getRestoresRemaining() > 0)) {
                sendMessage(sender, plugin.getConfig().getString("messages.target-busy", "&cᴘʟᴀʏᴇʀ &3%target% &cꜱᴜᴅᴀʜ ᴍᴇᴍɪʟɪᴋɪ ꜱᴛʀᴇᴀᴋ.")
                        .replace("%target%", target.getName()));
                return;
            }
        }

        StreakGroupData targetSolo = getPlayerGroup(target.getUniqueId(), StreakGroupData.Mode.SOLO);
        if (targetSolo != null) {
            evaluateGroupStatus(targetSolo);
            if ((targetSolo.isActive() && !targetSolo.isDead()) || (targetSolo.isDead() && targetSolo.getRestoresRemaining() > 0)) {
                sendMessage(sender, plugin.getConfig().getString(
                        "messages.target-cannot-combine-solo-duo",
                        "&cᴘʟᴀʏᴇʀ &3%target% &cꜱᴇᴅᴀɴɢ ᴍᴇᴍɪʟɪᴋɪ ᴍᴏᴅᴇ &6ꜱᴏʟᴏ &cᴀᴛᴀᴜ &dᴅᴜᴏ &cʏᴀɴɢ ᴛɪᴅᴀᴋ ʙɪꜱᴀ ᴅɪɢᴀʙᴜɴɢᴋᴀɴ."
                ).replace("%target%", target.getName()));
                return;
            }
        }

        long expireSec = Math.max(15, plugin.getConfig().getLong("settings.invite-expire-seconds", 60));
        pendingInvites.put(target.getUniqueId(), new PendingInvite(
                sender.getUniqueId(),
                sender.getName(),
                System.currentTimeMillis() + (expireSec * 1000L)
        ));

        sendMessage(sender, plugin.getConfig().getString("messages.invite-sent", "&b✉ ᴜɴᴅᴀɴɢᴀɴ ᴅɪᴋɪʀɪᴍ ᴋᴇ &3%target%")
                .replace("%target%", target.getName()));
        sendMessage(target, plugin.getConfig().getString("messages.invite-received", "&b✉ &3%sender% &7ᴍᴇɴɢᴜɴᴅᴀɴɢᴍᴜ! ᴋᴇᴛɪᴋ &3/ꜱᴛʀᴇᴀᴋ ᴀᴄᴄᴇᴘᴛ")
                .replace("%sender%", sender.getName()));
    }

    public void acceptDuoInvite(Player target) {
        PendingInvite invite = pendingInvites.remove(target.getUniqueId());
        if (invite == null || System.currentTimeMillis() > invite.expiresAtMillis) {
            sendMessage(target, plugin.getConfig().getString("messages.invite-none", "&cᴛɪᴅᴀᴋ ᴀᴅᴀ ᴜɴᴅᴀɴɢᴀɴ ꜱᴛʀᴇᴀᴋ."));
            return;
        }

        Player sender = Bukkit.getPlayer(invite.senderUuid);
        if (sender == null || !sender.isOnline()) {
            sendMessage(target, plugin.getConfig().getString("messages.player-offline", "&cᴘʟᴀʏᴇʀ &3%target% &cᴏꜰꜰʟɪɴᴇ.")
                    .replace("%target%", invite.senderName));
            return;
        }

        StreakGroupData senderSolo = getPlayerGroup(sender.getUniqueId(), StreakGroupData.Mode.SOLO);
        if (senderSolo != null) {
            evaluateGroupStatus(senderSolo);
            if ((senderSolo.isActive() && !senderSolo.isDead()) || (senderSolo.isDead() && senderSolo.getRestoresRemaining() > 0)) {
                sendMessage(sender, plugin.getConfig().getString("messages.cannot-combine-solo-duo", "&cᴛɪᴅᴀᴋ ʙɪꜱᴀ ᴍᴇɴɢɢᴀʙᴜɴɢᴋᴀɴ ꜱᴏʟᴏ ᴅᴀɴ ᴅᴜᴏ!"));
                sendMessage(target, plugin.getConfig().getString("messages.target-cannot-combine-solo-duo", "&cᴘʟᴀʏᴇʀ &3%target% &cᴍᴇᴍɪʟɪᴋɪ ᴍᴏᴅᴇ ꜱᴏʟᴏ.")
                        .replace("%target%", sender.getName()));
                return;
            }
        }

        StreakGroupData targetSolo = getPlayerGroup(target.getUniqueId(), StreakGroupData.Mode.SOLO);
        if (targetSolo != null) {
            evaluateGroupStatus(targetSolo);
            if ((targetSolo.isActive() && !targetSolo.isDead()) || (targetSolo.isDead() && targetSolo.getRestoresRemaining() > 0)) {
                sendMessage(target, plugin.getConfig().getString("messages.cannot-combine-solo-duo", "&cᴛɪᴅᴀᴋ ʙɪꜱᴀ ᴍᴇɴɢɢᴀʙᴜɴɢᴋᴀɴ ꜱᴏʟᴏ ᴅᴀɴ ᴅᴜᴏ!"));
                sendMessage(sender, plugin.getConfig().getString("messages.target-cannot-combine-solo-duo", "&cᴘʟᴀʏᴇʀ &3%target% &cᴍᴇᴍɪʟɪᴋɪ ᴍᴏᴅᴇ ꜱᴏʟᴏ.")
                        .replace("%target%", target.getName()));
                return;
            }
        }

        StreakGroupData oldSenderDuo = getPlayerGroup(sender.getUniqueId(), StreakGroupData.Mode.DUO);
        if (oldSenderDuo != null) unregisterGroup(oldSenderDuo);
        StreakGroupData oldTargetDuo = getPlayerGroup(target.getUniqueId(), StreakGroupData.Mode.DUO);
        if (oldTargetDuo != null) unregisterGroup(oldTargetDuo);

        String id = "duo_" + sender.getUniqueId() + "_" + target.getUniqueId();
        StreakGroupData duo = new StreakGroupData(id, StreakGroupData.Mode.DUO, sender.getName() + " & " + target.getName(), getMaxRestores());
        duo.setMembers(List.of(sender.getUniqueId(), target.getUniqueId()));
        Map<UUID, String> names = new HashMap<>();
        names.put(sender.getUniqueId(), sender.getName());
        names.put(target.getUniqueId(), target.getName());
        duo.setMemberNames(names);

        duo.setStreak(1);
        duo.setActive(true);
        duo.setDead(false);
        duo.setRestoresRemaining(getMaxRestores());
        String todayStr = today().toString();
        duo.setLastCompletedDate(todayStr);
        duo.setTodayCheckInDate(todayStr);
        duo.getTodayCheckedIn().add(sender.getUniqueId());
        duo.getTodayCheckedIn().add(target.getUniqueId());

        registerGroup(duo);

        String startedMsg = formatGroupMsg("messages.started", duo);
        sendMessage(sender, startedMsg);
        sendMessage(target, startedMsg);
        checkAndGrantMilestones(sender, duo);
        checkAndGrantMilestones(target, duo);
        saveData();
    }

    public boolean startTeamStreakApi(Player player, boolean bypassMinMembers) {
        if (!betterTeamsHook.isAvailable()) {
            sendMessage(player, plugin.getConfig().getString("messages.team-plugin-missing", "&cᴘʟᴜɢɪɴ ʙᴇᴛᴛᴇʀᴛᴇᴀᴍꜱ ᴛɪᴅᴀᴋ ᴛᴇʀᴘᴀꜱᴀɴɢ."));
            return false;
        }

        BetterTeamsHook.TeamSnapshot snap = betterTeamsHook.getPlayerTeam(player);
        if (snap == null || snap.memberUuids.isEmpty()) {
            sendMessage(player, plugin.getConfig().getString("messages.team-not-found", "&cᴋᴀᴍᴜ ᴛɪᴅᴀᴋ ᴛᴇʀɢᴀʙᴜɴɢ ᴅᴀʟᴀᴍ ᴛᴇᴀᴍ!"));
            return false;
        }

        int minRequired = getMinTeamMembers();
        int currentCount = snap.memberUuids.size();
        if (!bypassMinMembers && !player.isOp() && !player.hasPermission("javrockstreak.admin") && currentCount < minRequired) {
            String msg = plugin.getConfig().getString(
                    "messages.team-min-members",
                    "&cꜱᴛʀᴇᴀᴋ ᴛᴇᴀᴍ ᴍᴇᴍᴇʀʟᴜᴋᴀɴ ᴍɪɴɪᴍᴀʟ &b%min% &cᴀɴɢɢᴏᴛᴀ ᴅɪ ʙᴇᴛᴛᴇʀᴛᴇᴀᴍꜱ (ꜱᴀᴀᴛ ɪɴɪ: &3%current%&c)!"
            ).replace("%min%", String.valueOf(minRequired))
             .replace("%current%", String.valueOf(currentCount));
            sendMessage(player, msg);
            return false;
        }

        String teamGroupId = "team_" + snap.teamId;
        StreakGroupData existing = groups.get(teamGroupId);
        if (existing == null) {
            existing = getPlayerGroup(player.getUniqueId(), StreakGroupData.Mode.TEAM);
        }

        if (existing != null) {
            evaluateGroupStatus(existing);
            if (existing.isActive() && !existing.isDead()) {
                sendMessage(player, formatGroupMsg("messages.already-active", existing));
                return false;
            }
            if (existing.isDead() && existing.getRestoresRemaining() > 0) {
                sendMessage(player, formatGroupMsg("messages.need-restore-first", existing));
                return false;
            }
            unregisterGroup(existing);
        }

        for (UUID memberUuid : snap.memberUuids) {
            StreakGroupData oldTeam = getPlayerGroup(memberUuid, StreakGroupData.Mode.TEAM);
            if (oldTeam != null) {
                evaluateGroupStatus(oldTeam);
                if (oldTeam.isDead() && oldTeam.getRestoresRemaining() <= 0) {
                    unregisterGroup(oldTeam);
                }
            }
        }

        StreakGroupData teamData = new StreakGroupData(teamGroupId, StreakGroupData.Mode.TEAM, snap.teamName, getMaxRestores());
        Map<UUID, String> names = new HashMap<>();
        for (UUID u : snap.memberUuids) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(u);
            names.put(u, off.getName() != null ? off.getName() : "Member");
        }
        teamData.setMembers(snap.memberUuids);
        teamData.setMemberNames(names);

        teamData.setStreak(1);
        teamData.setActive(true);
        teamData.setDead(false);
        teamData.setRestoresRemaining(getMaxRestores());
        String todayStr = today().toString();
        teamData.setLastCompletedDate(todayStr);
        teamData.setTodayCheckInDate(todayStr);
        for (UUID u : snap.memberUuids) {
            Player online = Bukkit.getPlayer(u);
            if (online != null && online.isOnline()) {
                teamData.getTodayCheckedIn().add(u);
            }
        }

        registerGroup(teamData);

        String startedMsg = formatGroupMsg("messages.started", teamData);
        for (UUID u : snap.memberUuids) {
            Player online = Bukkit.getPlayer(u);
            if (online != null && online.isOnline()) {
                sendMessage(online, startedMsg);
                checkAndGrantMilestones(online, teamData);
            }
        }
        saveData();
        return true;
    }

    public boolean restoreStreakForMode(Player player, StreakGroupData.Mode requiredMode) {
        StreakGroupData data = getPlayerGroup(player.getUniqueId(), requiredMode);
        if (data == null) {
            sendMessage(player, plugin.getConfig().getString("messages.cannot-restore-not-started", "&cᴋᴀᴍᴜ ʙᴇʟᴜᴍ ᴍᴇᴍɪʟɪᴋɪ ꜱᴛʀᴇᴀᴋ!"));
            return false;
        }
        evaluateGroupStatus(data);

        if (data.isActive() && !data.isDead()) {
            sendMessage(player, formatGroupMsg("messages.cannot-restore-alive", data));
            return false;
        }

        if (!data.isDead() || data.getStreak() <= 0) {
            sendMessage(player, plugin.getConfig().getString("messages.cannot-restore-not-started", "&cᴋᴀᴍᴜ ʙᴇʟᴜᴍ ᴍᴇᴍɪʟɪᴋɪ ꜱᴛʀᴇᴀᴋ!"));
            return false;
        }

        if (data.getRestoresRemaining() <= 0) {
            sendMessage(player, formatGroupMsg("messages.no-restores-left", data));
            return false;
        }

        data.setRestoresRemaining(data.getRestoresRemaining() - 1);
        data.setDead(false);
        data.setActive(true);
        data.setStreak(data.getStreak() + 1);
        String todayStr = today().toString();
        data.setLastCompletedDate(todayStr);
        data.setTodayCheckInDate(todayStr);
        data.getTodayCheckedIn().clear();
        data.getTodayCheckedIn().addAll(data.getMembers());

        String restoredMsg = formatGroupMsg("messages.restored", data);
        for (UUID u : data.getMembers()) {
            Player online = Bukkit.getPlayer(u);
            if (online != null && online.isOnline()) {
                sendMessage(online, restoredMsg);
                checkAndGrantMilestones(online, data);
            }
        }
        saveData();
        return true;
    }

    public void checkAndGrantMilestones(Player player, StreakGroupData data) {
        String modePath = "milestones." + data.getMode().name();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(modePath);
        if (sec == null) {
            sec = plugin.getConfig().getConfigurationSection("milestones");
        }
        if (sec == null) {
            return;
        }
        int currentStreak = data.getStreak();
        for (String key : sec.getKeys(false)) {
            int milestoneDay;
            try {
                milestoneDay = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }
            if (currentStreak >= milestoneDay && !data.getClaimedMilestones().contains(milestoneDay)) {
                data.getClaimedMilestones().add(milestoneDay);
                List<String> commands = sec.getStringList(key + ".commands");
                for (UUID memberUuid : data.getMembers()) {
                    String memberName = data.getMemberNames().getOrDefault(memberUuid, player.getName());
                    for (String cmd : commands) {
                        String parsed = cmd
                                .replace("%player%", memberName)
                                .replace("%streak%", String.valueOf(milestoneDay));
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
                    }
                    Player online = Bukkit.getPlayer(memberUuid);
                    if (online != null && online.isOnline()) {
                        String reached = formatGroupMsg("messages.milestone-reached", data)
                                .replace("%streak%", String.valueOf(milestoneDay));
                        sendMessage(online, reached);
                    }
                }
            }
        }
    }

    public boolean adminAddDays(UUID targetUuid, String targetName, int daysToAdd) {
        StreakGroupData data = getPrimaryPlayerGroup(targetUuid);
        if (data == null) {
            String id = "solo_" + targetUuid;
            data = new StreakGroupData(id, StreakGroupData.Mode.SOLO, targetName, getMaxRestores());
            data.setMembers(List.of(targetUuid));
            data.setMemberNames(Map.of(targetUuid, targetName));
            data.setActive(true);
            data.setDead(false);
            data.setRestoresRemaining(getMaxRestores());
            registerGroup(data);
        }

        data.setDead(false);
        data.setActive(true);
        data.setStreak(Math.max(0, data.getStreak() + daysToAdd));
        String todayStr = today().toString();
        data.setLastCompletedDate(todayStr);
        data.setTodayCheckInDate(todayStr);
        data.getTodayCheckedIn().clear();
        data.getTodayCheckedIn().addAll(data.getMembers());

        Player online = Bukkit.getPlayer(targetUuid);
        if (online != null && online.isOnline()) {
            checkAndGrantMilestones(online, data);
        }
        saveData();
        return true;
    }

    public boolean adminSetDays(UUID targetUuid, String targetName, int exactDays) {
        StreakGroupData data = getPrimaryPlayerGroup(targetUuid);
        if (data == null) {
            String id = "solo_" + targetUuid;
            data = new StreakGroupData(id, StreakGroupData.Mode.SOLO, targetName, getMaxRestores());
            data.setMembers(List.of(targetUuid));
            data.setMemberNames(Map.of(targetUuid, targetName));
            data.setActive(true);
            data.setDead(false);
            data.setRestoresRemaining(getMaxRestores());
            registerGroup(data);
        }

        data.setDead(false);
        data.setActive(true);
        data.setStreak(Math.max(0, exactDays));
        String todayStr = today().toString();
        data.setLastCompletedDate(todayStr);
        data.setTodayCheckInDate(todayStr);
        data.getTodayCheckedIn().clear();
        data.getTodayCheckedIn().addAll(data.getMembers());

        Player online = Bukkit.getPlayer(targetUuid);
        if (online != null && online.isOnline()) {
            checkAndGrantMilestones(online, data);
        }
        saveData();
        return true;
    }

    public boolean adminKillStreak(UUID targetUuid) {
        List<StreakGroupData> list = getPlayerGroups(targetUuid);
        if (list.isEmpty()) return false;
        for (StreakGroupData d : list) {
            d.setActive(false);
            d.setDead(true);
        }
        saveData();
        return true;
    }

    public boolean adminSetRestores(UUID targetUuid, int restores) {
        List<StreakGroupData> list = getPlayerGroups(targetUuid);
        if (list.isEmpty()) return false;
        for (StreakGroupData d : list) {
            d.setRestoresRemaining(Math.max(0, restores));
        }
        saveData();
        return true;
    }

    public boolean adminResetStreak(UUID targetUuid) {
        List<StreakGroupData> list = new ArrayList<>(getPlayerGroups(targetUuid));
        if (list.isEmpty()) return false;
        for (StreakGroupData d : list) {
            unregisterGroup(d);
        }
        saveData();
        return true;
    }

    public boolean adminStartTeamApi(Player adminPlayer, String memberName) {
        Player refPlayer = (memberName != null && !memberName.isBlank()) ? Bukkit.getPlayerExact(memberName) : adminPlayer;
        if (refPlayer == null) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(memberName);
            if (off.getName() != null) {
                BetterTeamsHook.TeamSnapshot snap = betterTeamsHook.getTeamByPlayerUuid(off.getUniqueId());
                if (snap == null || snap.memberUuids.isEmpty()) {
                    return false;
                }
                return activateTeamSnapshot(snap, true);
            }
            return false;
        }
        BetterTeamsHook.TeamSnapshot snap = betterTeamsHook.getPlayerTeam(refPlayer);
        if (snap == null || snap.memberUuids.isEmpty()) {
            return false;
        }
        return activateTeamSnapshot(snap, true);
    }

    private boolean activateTeamSnapshot(BetterTeamsHook.TeamSnapshot snap, boolean notifyMembers) {
        String teamGroupId = "team_" + snap.teamId;
        StreakGroupData existing = groups.get(teamGroupId);
        if (existing != null) {
            unregisterGroup(existing);
        }
        for (UUID memberUuid : snap.memberUuids) {
            StreakGroupData oldTeam = getPlayerGroup(memberUuid, StreakGroupData.Mode.TEAM);
            if (oldTeam != null) {
                unregisterGroup(oldTeam);
            }
        }

        StreakGroupData teamData = new StreakGroupData(teamGroupId, StreakGroupData.Mode.TEAM, snap.teamName, getMaxRestores());
        Map<UUID, String> names = new HashMap<>();
        for (UUID u : snap.memberUuids) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(u);
            names.put(u, off.getName() != null ? off.getName() : "Member");
        }
        teamData.setMembers(snap.memberUuids);
        teamData.setMemberNames(names);
        teamData.setStreak(1);
        teamData.setActive(true);
        teamData.setDead(false);
        teamData.setRestoresRemaining(getMaxRestores());
        String todayStr = today().toString();
        teamData.setLastCompletedDate(todayStr);
        teamData.setTodayCheckInDate(todayStr);
        teamData.getTodayCheckedIn().addAll(snap.memberUuids);

        registerGroup(teamData);
        if (notifyMembers) {
            String startedMsg = formatGroupMsg("messages.started", teamData);
            for (UUID u : snap.memberUuids) {
                Player online = Bukkit.getPlayer(u);
                if (online != null && online.isOnline()) {
                    sendMessage(online, startedMsg);
                    checkAndGrantMilestones(online, teamData);
                }
            }
        }
        saveData();
        return true;
    }

    public boolean adminRestoreForPlayer(UUID targetUuid) {
        List<StreakGroupData> list = getPlayerGroups(targetUuid);
        if (list.isEmpty()) return false;
        boolean restoredAny = false;
        String todayStr = today().toString();
        for (StreakGroupData data : list) {
            evaluateGroupStatus(data);
            if (data.isDead() && data.getRestoresRemaining() > 0) {
                data.setRestoresRemaining(data.getRestoresRemaining() - 1);
                data.setDead(false);
                data.setActive(true);
                data.setStreak(data.getStreak() + 1);
                data.setLastCompletedDate(todayStr);
                data.setTodayCheckInDate(todayStr);
                data.getTodayCheckedIn().clear();
                data.getTodayCheckedIn().addAll(data.getMembers());
                restoredAny = true;
            }
        }
        if (restoredAny) saveData();
        return restoredAny;
    }

    public boolean adminCreateDuo(Player p1, Player p2) {
        if (p1 == null || p2 == null || p1.getUniqueId().equals(p2.getUniqueId())) return false;
        StreakGroupData old1 = getPlayerGroup(p1.getUniqueId(), StreakGroupData.Mode.DUO);
        if (old1 != null) unregisterGroup(old1);
        StreakGroupData old2 = getPlayerGroup(p2.getUniqueId(), StreakGroupData.Mode.DUO);
        if (old2 != null) unregisterGroup(old2);

        String id = "duo_" + p1.getUniqueId() + "_" + p2.getUniqueId();
        StreakGroupData duo = new StreakGroupData(id, StreakGroupData.Mode.DUO, p1.getName() + " & " + p2.getName(), getMaxRestores());
        duo.setMembers(List.of(p1.getUniqueId(), p2.getUniqueId()));
        duo.setMemberNames(Map.of(p1.getUniqueId(), p1.getName(), p2.getUniqueId(), p2.getName()));
        duo.setStreak(1);
        duo.setActive(true);
        duo.setDead(false);
        duo.setRestoresRemaining(getMaxRestores());
        String todayStr = today().toString();
        duo.setLastCompletedDate(todayStr);
        duo.setTodayCheckInDate(todayStr);
        duo.getTodayCheckedIn().addAll(duo.getMembers());
        registerGroup(duo);

        String startedMsg = formatGroupMsg("messages.started", duo);
        sendMessage(p1, startedMsg);
        sendMessage(p2, startedMsg);
        saveData();
        return true;
    }

    public void sendTopStreaks(Player player) {
        for (StreakGroupData d : groups.values()) {
            evaluateGroupStatus(d);
        }

        List<StreakGroupData> sorted = groups.values().stream()
                .filter(d -> d.getStreak() > 0)
                .sorted(Comparator.comparingInt(StreakGroupData::getStreak).reversed())
                .limit(10)
                .collect(Collectors.toList());

        String header = plugin.getConfig().getString("messages.top-header", "&8&m----------------------------------------");
        String title = plugin.getConfig().getString("messages.top-title", "&9&lᴊᴀᴠʀᴏᴄᴋ &8» &bᴛᴏᴘ 10 ꜱᴛʀᴇᴀᴋ ᴀᴘɪ ᴛᴇʀᴛɪɴɢɢɪ");
        player.sendMessage(LEGACY.deserialize(header));
        player.sendMessage(LEGACY.deserialize(title));

        if (sorted.isEmpty()) {
            String empty = plugin.getConfig().getString("messages.top-empty", "&7ʙᴇʟᴜᴍ ᴀᴅᴀ ᴘᴇᴍᴀɪɴ/ᴛᴇᴀᴍ ʏᴀɴɢ ᴍᴇᴍɪʟɪᴋɪ ꜱᴛʀᴇᴀᴋ.");
            player.sendMessage(LEGACY.deserialize(empty));
        } else {
            String entryTemplate = plugin.getConfig().getString(
                    "messages.top-entry",
                    "&3#%rank% &b%label% &8- &6🔥 &f%streak% ʜᴀʀɪ &7(%state%&7)"
            );
            int rank = 1;
            for (StreakGroupData d : sorted) {
                String state = (d.isActive() && !d.isDead()) ? "&aᴀᴋᴛɪꜰ" : "&cᴍᴀᴛɪ";
                String tag = switch (d.getMode()) {
                    case SOLO -> d.getFormattedLabel();
                    case DUO -> "&d[ᴅᴜᴏ] &b" + d.getFormattedLabel();
                    case TEAM -> "&3[ᴛᴇᴀᴍ] &b" + d.getDisplayName();
                };
                String line = entryTemplate
                        .replace("%rank%", String.valueOf(rank++))
                        .replace("%label%", tag)
                        .replace("%player%", tag)
                        .replace("%streak%", String.valueOf(d.getStreak()))
                        .replace("%state%", state);
                player.sendMessage(LEGACY.deserialize(line));
            }
        }
        player.sendMessage(LEGACY.deserialize(header));
    }

    public void openModeSelectorGui(Player player) {
        int rows = Math.min(6, Math.max(3, plugin.getConfig().getInt("gui.selector.rows", 3)));
        int size = rows * 9;
        String title = plugin.getConfig().getString("gui.selector.title", "&9&lᴊᴀᴠʀᴏᴄᴋ &8» &3ᴘɪʟɪʜ ᴍᴏᴅᴇ ꜱᴛʀᴇᴀᴋ");

        Inventory inv = Bukkit.createInventory(new StreakGuiHolder(GuiScreen.MODE_SELECTOR), size, LEGACY.deserialize(title));
        fillBackground(inv, size, plugin.getConfig().getString("gui.selector.filler-material", "GRAY_STAINED_GLASS_PANE"));

        inv.setItem(getSelectorSlot("solo", 11, size), buildSelectorItem("gui.selector.solo", Material.BLAZE_POWDER, "&6🔥 &b&lꜱᴏʟᴏ ꜱᴛʀᴇᴀᴋ"));
        inv.setItem(getSelectorSlot("duo", 13, size), buildSelectorItem("gui.selector.duo", Material.GOLDEN_APPLE, "&d🔥 &b&lᴅᴜᴏ ꜱᴛʀᴇᴀᴋ"));
        inv.setItem(getSelectorSlot("team", 15, size), buildSelectorItem("gui.selector.team", Material.NETHER_STAR, "&3🔥 &b&lᴛᴇᴀᴍ ꜱᴛʀᴇᴀᴋ"));

        player.openInventory(inv);
    }

    public int getSelectorSlot(String modeKey, int defaultSlot, int size) {
        int slot = plugin.getConfig().getInt("gui.selector." + modeKey + ".slot", defaultSlot);
        return Math.min(size - 1, Math.max(0, slot));
    }

    private ItemStack buildSelectorItem(String path, Material fallbackMat, String fallbackName) {
        String iconName = plugin.getConfig().getString(path + ".icon", fallbackMat.name());
        Material mat = Material.matchMaterial(iconName.toUpperCase(Locale.ROOT));
        if (mat == null) mat = fallbackMat;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(plugin.getConfig().getString(path + ".name", fallbackName)));
            List<Component> lore = new ArrayList<>();
            for (String line : plugin.getConfig().getStringList(path + ".lore")) {
                lore.add(LEGACY.deserialize(line));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void openModeRewardsGui(Player player, StreakGroupData.Mode targetMode) {
        StreakGroupData modeData = getPlayerGroup(player.getUniqueId(), targetMode);
        if (modeData != null) {
            evaluateGroupStatus(modeData);
        }

        String menuKey = switch (targetMode) {
            case SOLO -> "solo-menu";
            case DUO -> "duo-menu";
            case TEAM -> "team-menu";
        };
        GuiScreen screen = switch (targetMode) {
            case SOLO -> GuiScreen.SOLO_REWARDS;
            case DUO -> GuiScreen.DUO_REWARDS;
            case TEAM -> GuiScreen.TEAM_REWARDS;
        };
        String defaultTitle = switch (targetMode) {
            case SOLO -> "&9&lᴊᴀᴠʀᴏᴄᴋ &8» &bʀᴇᴡᴀʀᴅ ꜱᴏʟᴏ ꜱᴛʀᴇᴀᴋ";
            case DUO -> "&9&lᴊᴀᴠʀᴏᴄᴋ &8» &dʀᴇᴡᴀʀᴅ ᴅᴜᴏ ꜱᴛʀᴇᴀᴋ";
            case TEAM -> "&9&lᴊᴀᴠʀᴏᴄᴋ &8» &3ᴛᴇᴀᴍ ʀᴇᴡᴀʀᴅ";
        };

        int rows = Math.min(6, Math.max(3, plugin.getConfig().getInt("gui." + menuKey + ".rows", 4)));
        int size = rows * 9;
        String title = plugin.getConfig().getString("gui." + menuKey + ".title", defaultTitle);

        Inventory inv = Bukkit.createInventory(new StreakGuiHolder(screen), size, LEGACY.deserialize(title));
        fillBackground(inv, size, plugin.getConfig().getString("gui." + menuKey + ".filler-material", "GRAY_STAINED_GLASS_PANE"));

        boolean hasMode = modeData != null;

        int infoSlot = Math.min(size - 1, Math.max(0, plugin.getConfig().getInt("gui." + menuKey + ".info-slot", 4)));
        ItemStack infoItem = new ItemStack(Material.BLAZE_POWDER);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            String modeHeader = switch (targetMode) {
                case SOLO -> "&6🔥 &b&lʀᴇᴡᴀʀᴅ ꜱᴏʟᴏ ꜱᴛʀᴇᴀᴋ";
                case DUO -> "&d🔥 &b&lʀᴇᴡᴀʀᴅ ᴅᴜᴏ ꜱᴛʀᴇᴀᴋ";
                case TEAM -> "&3🔥 &b&lᴛᴇᴀᴍ ʀᴇᴡᴀʀᴅ";
            };
            infoMeta.displayName(LEGACY.deserialize(modeHeader));
            int currentStreak = hasMode ? modeData.getStreak() : 0;
            int restores = hasMode ? modeData.getRestoresRemaining() : getMaxRestores();
            String stateText = hasMode
                    ? ((modeData.isActive() && !modeData.isDead()) ? "&aᴀᴋᴛɪꜰ" : "&cᴍᴀᴛɪ (" + modeData.getRestoreCommandHint() + ")")
                    : "&7ʙᴇʟᴜᴍ ᴀᴋᴛɪꜰ ᴅɪ ᴍᴏᴅᴇ ɪɴɪ";

            List<Component> infoLore = new ArrayList<>();
            infoLore.add(LEGACY.deserialize("&7ꜱᴛʀᴇᴀᴋ ᴋᴀᴍᴜ (" + targetMode.name() + "): &b" + currentStreak + " ʜᴀʀɪ"));
            infoLore.add(LEGACY.deserialize("&7ꜱᴛᴀᴛᴜꜱ ᴀᴘɪ: " + stateText));
            infoLore.add(LEGACY.deserialize("&7ꜱɪꜱᴀ ʀᴇꜱᴛᴏʀᴇ: &3" + restores + "/" + getMaxRestores()));
            infoMeta.lore(infoLore);
            infoItem.setItemMeta(infoMeta);
        }
        inv.setItem(infoSlot, infoItem);

        int backSlot = getBackSlot(targetMode, size);
        inv.setItem(backSlot, buildSelectorItem("gui.back-button", Material.ARROW, "&c« ᴋᴇᴍʙᴀʟɪ ᴋᴇ ᴘɪʟɪʜᴀɴ ᴍᴏᴅᴇ"));

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("milestones." + targetMode.name());
        if (sec != null) {
            String statusClaimed = plugin.getConfig().getString("messages.gui-status-claimed", "&a✔ ꜱᴜᴅᴀʜ ᴅɪᴋʟᴀɪᴍ");
            String statusLocked = plugin.getConfig().getString("messages.gui-status-locked", "&c✘ ʙᴇʟᴜᴍ ᴛᴇʀᴄᴀᴘᴀɪ");
            String statusOther = plugin.getConfig().getString("messages.gui-status-other-mode", "&7🔒 ʙᴇʟᴜᴍ ᴀᴋᴛɪꜰ ᴅɪ ᴍᴏᴅᴇ %mode_name%")
                    .replace("%mode_name%", targetMode.name());

            for (String key : sec.getKeys(false)) {
                int day;
                try {
                    day = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    continue;
                }
                int slot = sec.getInt(key + ".slot", 10);
                if (slot < 0 || slot >= size) continue;

                String iconName = sec.getString(key + ".icon", "COAL");
                Material mat = Material.matchMaterial(iconName.toUpperCase(Locale.ROOT));
                if (mat == null) mat = Material.COAL;

                String statusText;
                if (!hasMode) {
                    statusText = statusOther;
                } else if (modeData.getClaimedMilestones().contains(day)) {
                    statusText = statusClaimed;
                } else {
                    statusText = statusLocked;
                }

                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    String displayName = sec.getString(key + ".name", "&6🔥 &bꜱᴛʀᴇᴀᴋ " + day + " ʜᴀʀɪ");
                    meta.displayName(LEGACY.deserialize(displayName));

                    List<Component> lore = new ArrayList<>();
                    for (String line : sec.getStringList(key + ".lore")) {
                        String replaced = line
                                .replace("%status%", statusText)
                                .replace("%streak%", String.valueOf(day));
                        lore.add(LEGACY.deserialize(replaced));
                    }
                    meta.lore(lore);
                    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
                    item.setItemMeta(meta);
                }
                inv.setItem(slot, item);
            }
        }

        player.openInventory(inv);
    }

    public int getBackSlot(StreakGroupData.Mode mode, int size) {
        String menuKey = switch (mode) {
            case SOLO -> "solo-menu";
            case DUO -> "duo-menu";
            case TEAM -> "team-menu";
        };
        int slot = plugin.getConfig().getInt("gui." + menuKey + ".back-slot", 31);
        return Math.min(size - 1, Math.max(0, slot));
    }

    private void fillBackground(Inventory inv, int size, String matName) {
        Material fillerMat = Material.matchMaterial(matName != null ? matName.toUpperCase(Locale.ROOT) : "GRAY_STAINED_GLASS_PANE");
        if (fillerMat == null) fillerMat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack filler = new ItemStack(fillerMat);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(Component.text(" "));
            filler.setItemMeta(fillerMeta);
        }
        for (int i = 0; i < size; i++) {
            inv.setItem(i, filler);
        }
    }

    public String formatGroupMsg(String path, StreakGroupData data) {
        String raw = plugin.getConfig().getString(path, "");
        return raw
                .replace("%mode%", data.getModeBadge())
                .replace("%streak%", String.valueOf(data.getStreak()))
                .replace("%restores%", String.valueOf(data.getRestoresRemaining()))
                .replace("%max_restores%", String.valueOf(getMaxRestores()))
                .replace("%restore_cmd%", data.getRestoreCommandHint());
    }

    public void sendMessage(Player player, String msg) {
        String prefix = plugin.getConfig().getString("messages.prefix", "&9&lᴊᴀᴠʀᴏᴄᴋ &8» ");
        player.sendMessage(LEGACY.deserialize(prefix + msg));
    }
}
