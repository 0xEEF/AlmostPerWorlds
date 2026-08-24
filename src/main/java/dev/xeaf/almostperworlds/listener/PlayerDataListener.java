package dev.xeaf.almostperworlds.listener;

import dev.xeaf.almostperworlds.data.PlayerSnapshot;
import dev.xeaf.almostperworlds.group.GroupManager;
import dev.xeaf.almostperworlds.group.WorldGroup;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Swaps a player's inventory (and related state) whenever they cross a world-group boundary.
 * <p>
 * Folia safety: every operation here only ever touches the single player who triggered the
 * event. File I/O is dispatched to the async scheduler (never touches game state, so it's
 * always safe off-thread); applying the loaded snapshot back onto the player is dispatched
 * through {@code player.getScheduler()}, which runs on whatever region currently owns that
 * player - exactly the pattern Folia expects for entity-scoped work. Nothing here ever reaches
 * into a second world or a second entity while holding onto the first, which is what made the
 * original plugin's world-wide sync features unsafe.
 */
public final class PlayerDataListener implements Listener {

    private final Plugin plugin;
    private final GroupManager groupManager;
    private final boolean syncGameMode;

    /** The world each online player was in as of the last poll, so we can detect a change. */
    private final Map<UUID, String> lastKnownWorld = new ConcurrentHashMap<>();
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask pollingTask;

    public PlayerDataListener(Plugin plugin, GroupManager groupManager, boolean syncGameMode) {
        this.plugin = plugin;
        this.groupManager = groupManager;
        this.syncGameMode = syncGameMode;
    }

    /**
     * Starts polling for world changes. This exists because {@code PlayerTeleportEvent} and
     * {@code PlayerChangedWorldEvent} both turned out not to reliably fire on Folia for
     * cross-region world changes at all (confirmed: Folia deliberately doesn't restore these
     * events for teleports whose destination region/world may need to be created or loaded,
     * since doing so safely across the region-threading model was rejected upstream as too
     * fragile to support). Since there's no event to hook, this checks each online player's
     * current world against their last-known world on a short timer instead - each check runs
     * on that player's own entity scheduler, so it's Folia-safe with no reliance on events at all.
     */
    public void start() {
        pollingTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            for (var player : Bukkit.getOnlinePlayers()) {
                player.getScheduler().run(plugin, scheduledTask -> checkForWorldChange(player), null);
            }
        }, 10L, 10L); // every 10 ticks (0.5s) - latency vs. overhead tradeoff, tune if needed
    }

    public void stop() {
        if (pollingTask != null) pollingTask.cancel();
    }

    private void checkForWorldChange(Player player) {
        var uuid = player.getUniqueId();
        var current = player.getWorld().getName();
        var previous = lastKnownWorld.put(uuid, current);
        if (previous == null || previous.equals(current)) return; // no change (or first observation)

        var fromGroup = groupManager.resolve(previous);
        var toGroup = groupManager.resolve(current);

        plugin.getLogger().info("[apw-debug] " + player.getName() + " world-poll detected change: "
                + previous + " (group '" + fromGroup.name() + "') -> "
                + current + " (group '" + toGroup.name() + "')");

        if (fromGroup.name().equals(toGroup.name())) {
            plugin.getLogger().info("[apw-debug] same group, skipping swap");
            return;
        }

        var outgoing = PlayerSnapshot.capture(player, syncGameMode);
        persistAndLoad(outgoing, uuid, fromGroup, toGroup, player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        // Seed the baseline immediately so the poller doesn't mistake "just joined" for a change.
        lastKnownWorld.put(player.getUniqueId(), player.getWorld().getName());

        var group = groupManager.resolve(player.getWorld());
        var file = snapshotFile(group, player.getUniqueId());

        plugin.getLogger().info("[apw-debug] " + player.getName() + " joined into "
                + player.getWorld().getName() + " (group '" + group.name() + "'), forced game mode = "
                + group.defaultGameMode().map(Enum::name).orElse("none"));

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                var snapshot = PlayerSnapshot.load(file, syncGameMode);
                plugin.getLogger().info("[apw-debug] join snapshot for group '" + group.name()
                        + "' present = " + snapshot.isPresent());
                // Nothing to do if there's no stored data for this group AND no forced game mode.
                if (snapshot.isEmpty() && group.defaultGameMode().isEmpty()) return;

                player.getScheduler().run(plugin, scheduledTask -> {
                    if (!player.isOnline()) return;
                    snapshot.ifPresent(s -> s.apply(player));
                    // Forced game mode always wins over whatever the snapshot (or lack of one) set.
                    group.defaultGameMode().ifPresent(player::setGameMode);
                    plugin.getLogger().info("[apw-debug] applied join state for " + player.getName());
                }, null);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "[apw-debug] join handling failed", e);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        lastKnownWorld.remove(player.getUniqueId());

        var group = groupManager.resolve(player.getWorld());
        var snapshot = PlayerSnapshot.capture(player, syncGameMode);
        var file = snapshotFile(group, player.getUniqueId());

        plugin.getLogger().info("[apw-debug] " + player.getName() + " quit from "
                + player.getWorld().getName() + " (group '" + group.name() + "'), saving to " + file);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                snapshot.save(file);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "[apw-debug] quit save failed", e);
            }
        });
    }

    private void persistAndLoad(PlayerSnapshot outgoing, UUID uuid, WorldGroup fromGroup, WorldGroup toGroup, Player player) {
        var outgoingFile = snapshotFile(fromGroup, uuid);
        var incomingFile = snapshotFile(toGroup, uuid);

        plugin.getLogger().info("[apw-debug] persisting to " + outgoingFile + ", loading from " + incomingFile);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                outgoing.save(outgoingFile);
                var incoming = PlayerSnapshot.load(incomingFile, syncGameMode);
                plugin.getLogger().info("[apw-debug] saved outgoing, incoming snapshot present = " + incoming.isPresent());

                player.getScheduler().run(plugin, scheduledTask -> {
                    if (!player.isOnline()) return;
                    // No stored data yet for the destination group: clear so the player doesn't
                    // carry the previous group's items into a group that's never seen them before.
                    incoming.ifPresentOrElse(s -> s.apply(player), () -> clear(player));
                    // Forced game mode always wins over whatever the snapshot (or clearing) set.
                    toGroup.defaultGameMode().ifPresent(player::setGameMode);
                    plugin.getLogger().info("[apw-debug] applied incoming state for " + player.getName()
                            + ", now in group '" + toGroup.name() + "'");
                }, null);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "[apw-debug] persistAndLoad failed", e);
            }
        });
    }

    private void clear(Player player) {
        player.getInventory().clear();
        player.getEnderChest().clear();
    }

    private File snapshotFile(WorldGroup group, UUID uuid) {
        return new File(groupManager.dataFolder(group), uuid + ".yml");
    }
}
