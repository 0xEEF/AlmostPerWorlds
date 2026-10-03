package dev.xeaf.almostperworlds.listener;

import dev.xeaf.almostperworlds.data.PlayerSnapshot;
import dev.xeaf.almostperworlds.data.StoredLocation;
import dev.xeaf.almostperworlds.group.GroupManager;
import dev.xeaf.almostperworlds.group.WorldGroup;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
    private final boolean restoreLocation;
    private final boolean debug;

    /** The world each online player was in as of the last poll, so we can detect a change. */
    private final Map<UUID, String> lastKnownWorld = new ConcurrentHashMap<>();
    /** Where each online player was as of the last poll/teleport, i.e. their position in the group they're about to leave. */
    private final Map<UUID, StoredLocation> lastKnownLocation = new ConcurrentHashMap<>();
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask pollingTask;

    /**
     * Per-player job chain. Swaps, joins and quits for the same player must run strictly in the
     * order they were triggered; otherwise a quick A -> B -> A hop could load A's snapshot before
     * the previous hop finished saving it (stale data / wrong game mode applied last).
     */
    private final Map<UUID, CompletableFuture<Void>> queues = new ConcurrentHashMap<>();
    private final Executor asyncExecutor;

    public PlayerDataListener(Plugin plugin, GroupManager groupManager, boolean restoreLocation, boolean debug) {
        this.plugin = plugin;
        this.groupManager = groupManager;
        this.restoreLocation = restoreLocation;
        this.debug = debug;
        this.asyncExecutor = r -> Bukkit.getAsyncScheduler().runNow(plugin, t -> r.run());
    }

    private void enqueue(UUID uuid, Runnable job) {
        queues.compute(uuid, (id, previous) -> {
            CompletableFuture<?> base = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return base.handle((r, ex) -> null).thenRunAsync(() -> {
                try {
                    job.run();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "[AlmostPerWorlds] player data job failed", e);
                }
            }, asyncExecutor);
        });
    }

    private void debug(String message) {
        if (debug) plugin.getLogger().info("[apw-debug] " + message);
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

    /**
     * Must run on the player's own thread (the callers below guarantee it). Everything that can
     * throw is read BEFORE the tracking maps are updated, so a failure can never "consume" a
     * world change and silently skip the swap.
     */
    private void checkForWorldChange(Player player) {
        try {
            var uuid = player.getUniqueId();
            var location = player.getLocation();
            var world = location.getWorld();
            if (world == null) return;
            var current = world.getName();
            var here = StoredLocation.of(location);

            var previous = lastKnownWorld.put(uuid, current);
            var lastLocation = lastKnownLocation.put(uuid, here);
            if (previous == null || previous.equals(current)) return; // no change (or first observation)

            // Where the player was in the group they just left (not where they are now).
            var departure = lastLocation != null && lastLocation.world().equals(previous) ? lastLocation : null;

            var fromGroup = groupManager.resolve(previous);
            var toGroup = groupManager.resolve(current);

            debug(player.getName() + " world change detected: "
                    + previous + " (group '" + fromGroup.name() + "') -> "
                    + current + " (group '" + toGroup.name() + "'), departure point = " + departure);

            if (fromGroup.name().equals(toGroup.name())) {
                debug("same group, skipping swap");
                return;
            }

            var outgoing = PlayerSnapshot.capture(player, departure);
            persistAndLoad(outgoing, uuid, fromGroup, toGroup, player);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "[AlmostPerWorlds] world change check failed for " + player.getName(), t);
        }
    }

    /**
     * Reacts immediately where the event does fire (Paper). The poller remains the fallback for
     * Folia; both funnel through {@link #checkForWorldChange}, which de-duplicates via
     * {@code lastKnownWorld}, so a change is never processed twice.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        // Hop onto the player's own scheduler so this is Folia-safe wherever the event fires from.
        var player = event.getPlayer();
        player.getScheduler().run(plugin, task -> checkForWorldChange(player), null);
    }

    /**
     * Remembers the exact departure point when a teleport crosses a group boundary (Paper). On
     * Folia this event may not fire, in which case the poller's last-known location is used.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        var from = event.getFrom().getWorld();
        var to = event.getTo().getWorld();
        if (from == null || to == null || from.equals(to)) return;
        if (groupManager.resolve(from).name().equals(groupManager.resolve(to).name())) return;
        lastKnownLocation.put(event.getPlayer().getUniqueId(), StoredLocation.of(event.getFrom()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        // Seed the baseline immediately so the poller doesn't mistake "just joined" for a change.
        lastKnownWorld.put(player.getUniqueId(), player.getWorld().getName());
        lastKnownLocation.put(player.getUniqueId(), StoredLocation.of(player.getLocation()));

        var group = groupManager.resolve(player.getWorld());
        var file = snapshotFile(group, player.getUniqueId());

        debug(player.getName() + " joined into "
                + player.getWorld().getName() + " (group '" + group.name() + "'), starting game mode = "
                + group.defaultGameMode().map(Enum::name).orElse("none"));

        enqueue(player.getUniqueId(), () -> {
            var snapshot = PlayerSnapshot.load(file);
            debug("join snapshot for group '" + group.name() + "' present = " + snapshot.isPresent());
            // Never been in this group: keep whatever the player has, except apply the group's
            // starting game mode (if any). Returning players just get their own saved state.
            if (snapshot.isEmpty() && group.defaultGameMode().isEmpty()) return;

            player.getScheduler().run(plugin, scheduledTask -> {
                if (!player.isOnline()) return;
                if (snapshot.isPresent()) {
                    applyStored(player, snapshot.get(), group);
                } else {
                    group.defaultGameMode().ifPresent(player::setGameMode);
                }
                debug("applied join state for " + player.getName());
            }, null);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        lastKnownWorld.remove(player.getUniqueId());
        lastKnownLocation.remove(player.getUniqueId());

        var group = groupManager.resolve(player.getWorld());
        var snapshot = PlayerSnapshot.capture(player, StoredLocation.of(player.getLocation()));
        var file = snapshotFile(group, player.getUniqueId());

        debug(player.getName() + " quit from "
                + player.getWorld().getName() + " (group '" + group.name() + "'), saving to " + file);

        var uuid = player.getUniqueId();
        enqueue(uuid, () -> snapshot.save(file));
        var tail = queues.get(uuid);
        if (tail != null) tail.whenComplete((r, ex) -> queues.remove(uuid, tail));
    }

    private void persistAndLoad(PlayerSnapshot outgoing, UUID uuid, WorldGroup fromGroup, WorldGroup toGroup, Player player) {
        var outgoingFile = snapshotFile(fromGroup, uuid);
        var incomingFile = snapshotFile(toGroup, uuid);

        debug("persisting to " + outgoingFile + ", loading from " + incomingFile);

        enqueue(uuid, () -> {
            outgoing.save(outgoingFile);
            var incoming = PlayerSnapshot.load(incomingFile);
            debug("saved outgoing, incoming snapshot present = " + incoming.isPresent());

            player.getScheduler().run(plugin, scheduledTask -> {
                if (!player.isOnline()) return;
                debug("incoming snapshot location = " + incoming.flatMap(PlayerSnapshot::location).orElse(null));
                if (incoming.isPresent()) {
                    applyStored(player, incoming.get(), toGroup);
                    if (restoreLocation) incoming.get().location().ifPresent(loc -> restore(player, loc, toGroup));
                } else {
                    // First visit to this group: start clean, never carry over the previous
                    // group's items or game mode.
                    clear(player);
                    player.setGameMode(toGroup.defaultGameMode().orElse(Bukkit.getDefaultGameMode()));
                }
                debug("applied incoming state for " + player.getName() + ", now in group '" + toGroup.name() + "'");
            }, null);
        });
    }

    /** Applies a stored snapshot, with its saved game mode (legacy files without one fall back to the group's start mode). */
    private void applyStored(Player player, PlayerSnapshot snapshot, WorldGroup group) {
        snapshot.apply(player);
        player.setGameMode(snapshot.gameMode()
                .or(group::defaultGameMode)
                .orElse(Bukkit.getDefaultGameMode()));
    }

    /** Teleports the player back to where they last were in this group (if that spot is still valid). */
    private void restore(Player player, StoredLocation stored, WorldGroup group) {
        var world = Bukkit.getWorld(stored.world());
        if (world == null || !groupManager.resolve(world).name().equals(group.name())) {
            debug("not restoring location for " + player.getName() + ": world '" + stored.world()
                    + "' is unloaded or no longer in group '" + group.name() + "'");
            return;
        }
        debug("restoring " + player.getName() + " to " + stored);
        player.teleportAsync(stored.toLocation(world), PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((ok, ex) -> debug("restore teleport for " + player.getName() + " finished: ok=" + ok
                        + (ex != null ? ", error=" + ex : "")));
    }

    private void clear(Player player) {
        player.getInventory().clear();
        player.getEnderChest().clear();
    }

    private File snapshotFile(WorldGroup group, UUID uuid) {
        return new File(groupManager.dataFolder(group), uuid + ".yml");
    }
}
