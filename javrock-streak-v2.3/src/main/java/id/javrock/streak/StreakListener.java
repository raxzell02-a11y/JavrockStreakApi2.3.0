package id.javrock.streak;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public class StreakListener implements Listener {

    private final StreakManagerService streakService;

    public StreakListener(StreakManagerService streakService) {
        this.streakService = streakService;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        streakService.handlePlayerJoin(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof StreakManagerService.StreakGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        int topSize = event.getInventory().getSize();
        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        StreakManagerService.GuiScreen screen = holder.getScreen();
        if (screen == StreakManagerService.GuiScreen.MODE_SELECTOR) {
            if (rawSlot == streakService.getSelectorSlot("solo", 11, topSize)) {
                streakService.openModeRewardsGui(player, StreakGroupData.Mode.SOLO);
            } else if (rawSlot == streakService.getSelectorSlot("duo", 13, topSize)) {
                streakService.openModeRewardsGui(player, StreakGroupData.Mode.DUO);
            } else if (rawSlot == streakService.getSelectorSlot("team", 15, topSize)) {
                streakService.openModeRewardsGui(player, StreakGroupData.Mode.TEAM);
            }
        } else {
            StreakGroupData.Mode mode = switch (screen) {
                case SOLO_REWARDS -> StreakGroupData.Mode.SOLO;
                case DUO_REWARDS -> StreakGroupData.Mode.DUO;
                case TEAM_REWARDS -> StreakGroupData.Mode.TEAM;
                default -> StreakGroupData.Mode.SOLO;
            };
            if (rawSlot == streakService.getBackSlot(mode, topSize)) {
                streakService.openModeSelectorGui(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof StreakManagerService.StreakGuiHolder) {
            event.setCancelled(true);
        }
    }
}
