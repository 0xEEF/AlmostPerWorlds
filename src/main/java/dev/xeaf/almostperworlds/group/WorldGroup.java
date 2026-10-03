package dev.xeaf.almostperworlds.group;

import org.bukkit.GameMode;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A named group of worlds that share a single inventory/ender-chest/xp/health "identity".
 * <p>
 * This is intentionally minimal: it only tracks which worlds belong together. It does not
 * sync game rules, difficulty, time, weather or the world border - none of that is safe to
 * do across regions on Folia, and it's handled by "Worlds" anyway.
 */
public final class WorldGroup {

    private final String name;
    private final Set<String> worlds = new LinkedHashSet<>();

    /**
     * When set, players who enter this group for the very first time (no stored data for it yet)
     * start in this game mode. It is never applied again afterwards: each player's own game mode
     * is saved per group and restored on return.
     */
    private GameMode defaultGameMode;

    public WorldGroup(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * @return an unmodifiable-in-spirit view of the world names in this group.
     * Callers should go through {@link #addWorld(String)} / {@link #removeWorld(String)} to mutate.
     */
    public Set<String> worlds() {
        return worlds;
    }

    public boolean addWorld(String worldName) {
        return worlds.add(worldName);
    }

    public boolean removeWorld(String worldName) {
        return worlds.remove(worldName);
    }

    public boolean contains(String worldName) {
        return worlds.contains(worldName);
    }

    public Optional<GameMode> defaultGameMode() {
        return Optional.ofNullable(defaultGameMode);
    }

    /** Pass {@code null} to clear it. */
    public void defaultGameMode(GameMode gameMode) {
        this.defaultGameMode = gameMode;
    }
}
