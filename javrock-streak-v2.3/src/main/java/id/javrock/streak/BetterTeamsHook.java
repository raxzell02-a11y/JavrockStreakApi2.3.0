package id.javrock.streak;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class BetterTeamsHook {

    public static class TeamSnapshot {
        public final String teamId;
        public final String teamName;
        public final List<UUID> memberUuids;

        public TeamSnapshot(String teamId, String teamName, List<UUID> memberUuids) {
            this.teamId = teamId;
            this.teamName = teamName;
            this.memberUuids = memberUuids;
        }
    }

    public boolean isAvailable() {
        Plugin p = Bukkit.getPluginManager().getPlugin("BetterTeams");
        return p != null && p.isEnabled();
    }

    public TeamSnapshot getPlayerTeam(Player player) {
        if (!isAvailable() || player == null) {
            return null;
        }
        try {
            Class<?> teamClass = Class.forName("com.booksaw.betterTeams.Team");
            Method getTeamMethod = teamClass.getMethod("getTeam", OfflinePlayer.class);
            Object teamObj = getTeamMethod.invoke(null, player);
            if (teamObj == null) {
                return null;
            }
            return extractSnapshot(teamClass, teamObj);
        } catch (Throwable t) {
            return null;
        }
    }

    public TeamSnapshot getTeamByPlayerUuid(UUID uuid) {
        if (!isAvailable() || uuid == null) {
            return null;
        }
        try {
            OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
            Class<?> teamClass = Class.forName("com.booksaw.betterTeams.Team");
            Method getTeamMethod = teamClass.getMethod("getTeam", OfflinePlayer.class);
            Object teamObj = getTeamMethod.invoke(null, off);
            if (teamObj == null) {
                return null;
            }
            return extractSnapshot(teamClass, teamObj);
        } catch (Throwable t) {
            return null;
        }
    }

    private TeamSnapshot extractSnapshot(Class<?> teamClass, Object teamObj) throws Exception {
        String name = "Unknown";
        try {
            Method getName = teamClass.getMethod("getName");
            Object res = getName.invoke(teamObj);
            if (res != null) name = res.toString();
        } catch (NoSuchMethodException ignored) {
        }

        String id = name.toLowerCase();
        try {
            Method getId = teamClass.getMethod("getID");
            Object res = getId.invoke(teamObj);
            if (res != null) id = res.toString();
        } catch (NoSuchMethodException ignored) {
        }

        List<UUID> uuids = new ArrayList<>();
        try {
            Method getMembers = teamClass.getMethod("getMembers");
            Object memberSetObj = getMembers.invoke(teamObj);
            if (memberSetObj != null) {
                Method getClone = memberSetObj.getClass().getMethod("getClone");
                Object cloneCol = getClone.invoke(memberSetObj);
                if (cloneCol instanceof Collection<?> col) {
                    for (Object tm : col) {
                        Method getPlayerUuid = tm.getClass().getMethod("getPlayerUUID");
                        Object u = getPlayerUuid.invoke(tm);
                        if (u instanceof UUID uuid) {
                            uuids.add(uuid);
                        }
                    }
                }
            }
        } catch (NoSuchMethodException ignored) {
        }

        return new TeamSnapshot(id, name, uuids);
    }
}
