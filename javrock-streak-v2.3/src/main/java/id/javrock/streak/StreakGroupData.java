package id.javrock.streak;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StreakGroupData {

    public enum Mode {
        SOLO,
        DUO,
        TEAM
    }

    private final String id;
    private Mode mode;
    private String displayName;
    private List<UUID> members = new ArrayList<>();
    private Map<UUID, String> memberNames = new HashMap<>();
    private int streak;
    private boolean active;
    private boolean dead;
    private int restoresRemaining;
    private String lastCompletedDate;
    private Set<UUID> todayCheckedIn = new HashSet<>();
    private String todayCheckInDate;
    private Set<Integer> claimedMilestones = new HashSet<>();

    public StreakGroupData(String id, Mode mode, String displayName, int maxRestores) {
        this.id = id;
        this.mode = mode;
        this.displayName = displayName;
        this.streak = 0;
        this.active = false;
        this.dead = false;
        this.restoresRemaining = maxRestores;
        this.lastCompletedDate = "";
        this.todayCheckInDate = "";
    }

    public String getId() {
        return id;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<UUID> getMembers() {
        return members;
    }

    public void setMembers(List<UUID> members) {
        this.members = members != null ? new ArrayList<>(members) : new ArrayList<>();
    }

    public Map<UUID, String> getMemberNames() {
        return memberNames;
    }

    public void setMemberNames(Map<UUID, String> memberNames) {
        this.memberNames = memberNames != null ? new HashMap<>(memberNames) : new HashMap<>();
    }

    public int getStreak() {
        return streak;
    }

    public void setStreak(int streak) {
        this.streak = streak;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isDead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
    }

    public int getRestoresRemaining() {
        return restoresRemaining;
    }

    public void setRestoresRemaining(int restoresRemaining) {
        this.restoresRemaining = restoresRemaining;
    }

    public String getLastCompletedDate() {
        return lastCompletedDate;
    }

    public void setLastCompletedDate(String lastCompletedDate) {
        this.lastCompletedDate = lastCompletedDate != null ? lastCompletedDate : "";
    }

    public Set<UUID> getTodayCheckedIn() {
        return todayCheckedIn;
    }

    public void setTodayCheckedIn(Set<UUID> todayCheckedIn) {
        this.todayCheckedIn = todayCheckedIn != null ? new HashSet<>(todayCheckedIn) : new HashSet<>();
    }

    public String getTodayCheckInDate() {
        return todayCheckInDate;
    }

    public void setTodayCheckInDate(String todayCheckInDate) {
        this.todayCheckInDate = todayCheckInDate != null ? todayCheckInDate : "";
    }

    public Set<Integer> getClaimedMilestones() {
        return claimedMilestones;
    }

    public void setClaimedMilestones(Set<Integer> claimedMilestones) {
        this.claimedMilestones = claimedMilestones != null ? new HashSet<>(claimedMilestones) : new HashSet<>();
    }

    public String getRestoreCommandHint() {
        return switch (mode) {
            case SOLO -> "/streak restore";
            case DUO -> "/streak duo restore";
            case TEAM -> "/streak team restore";
        };
    }

    public String getFormattedLabel() {
        if (mode == Mode.SOLO) {
            if (!members.isEmpty()) {
                return memberNames.getOrDefault(members.get(0), displayName);
            }
            return displayName;
        } else if (mode == Mode.DUO) {
            List<String> names = new ArrayList<>();
            for (UUID u : members) {
                names.add(memberNames.getOrDefault(u, "Player"));
            }
            return String.join(" & ", names);
        } else {
            return "Team " + displayName;
        }
    }

    public String getModeBadge() {
        return switch (mode) {
            case SOLO -> "ꜱᴏʟᴏ";
            case DUO -> "ᴅᴜᴏ: " + getFormattedLabel();
            case TEAM -> "ᴛᴇᴀᴍ: " + displayName;
        };
    }
}
