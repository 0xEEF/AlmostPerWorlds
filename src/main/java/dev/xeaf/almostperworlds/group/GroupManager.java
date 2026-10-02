package dev.xeaf.almostperworlds.group;

import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the set of {@link WorldGroup}s and figures out which group a world belongs to.
 * <p>
 * Worlds that aren't explicitly assigned to a group all share one implicit "default" bucket,
 * so inventory state is never silently dropped when a player passes through an unmanaged world -
 * it behaves the same as vanilla multi-world (one shared inventory) for anything you never grouped.
 */
public final class GroupManager {

    /** Name reserved for the implicit bucket that holds every world nobody explicitly grouped. */
    public static final String DEFAULT_GROUP = "default";

    private final Plugin plugin;
    private final File groupsFile;
    private final Map<String, WorldGroup> groups = new LinkedHashMap<>();
    private final WorldGroup defaultGroup = new WorldGroup(DEFAULT_GROUP);

    public GroupManager(Plugin plugin) {
        this.plugin = plugin;
        this.groupsFile = new File(plugin.getDataFolder(), "groups.yml");
    }

    public void load() {
        groups.clear();
        defaultGroup.defaultGameMode(null);

        if (!groupsFile.exists()) {
            save();
            return;
        }

        var config = YamlConfiguration.loadConfiguration(groupsFile);
        var section = config.getConfigurationSection("groups");
        if (section == null) return;

        for (var name : section.getKeys(false)) {
            // The implicit default group has no world list, but its forced game mode must persist.
            var group = name.equalsIgnoreCase(DEFAULT_GROUP) ? defaultGroup : new WorldGroup(name);
            if (group != defaultGroup) {
                for (var world : section.getStringList(name + ".worlds")) {
                    group.addWorld(world);
                }
            }
            var gameMode = section.getString(name + ".game-mode");
            if (gameMode != null) {
                try {
                    group.defaultGameMode(org.bukkit.GameMode.valueOf(gameMode));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown game-mode '" + gameMode + "' for group '" + name + "' in groups.yml, ignoring.");
                }
            }
            if (group != defaultGroup) groups.put(name.toLowerCase(java.util.Locale.ROOT), group);
        }
    }

    public void save() {
        var config = new YamlConfiguration();
        config.set("groups." + DEFAULT_GROUP + ".game-mode",
                defaultGroup.defaultGameMode().map(Enum::name).orElse(null));
        for (var group : groups.values()) {
            config.set("groups." + group.name() + ".worlds", group.worlds().stream().toList());
            config.set("groups." + group.name() + ".game-mode",
                    group.defaultGameMode().map(Enum::name).orElse(null));
        }
        try {
            plugin.getDataFolder().mkdirs();
            config.save(groupsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save groups.yml: " + e.getMessage());
        }
    }

    /**
     * @return the group the given world belongs to, or the implicit default bucket if it isn't
     * assigned to any group. Never empty - callers don't need to special-case "no group".
     */
    public WorldGroup resolve(World world) {
        return resolve(world.getName());
    }

    public WorldGroup resolve(String worldName) {
        for (var group : groups.values()) {
            if (group.contains(worldName)) return group;
        }
        return defaultGroup;
    }

    public WorldGroup defaultGroup() {
        return defaultGroup;
    }

    public Optional<WorldGroup> get(String name) {
        if (name.equalsIgnoreCase(DEFAULT_GROUP)) return Optional.of(defaultGroup);
        return Optional.ofNullable(groups.get(name.toLowerCase(java.util.Locale.ROOT)));
    }

    public boolean create(String name) {
        if (name.equalsIgnoreCase(DEFAULT_GROUP)) return false;
        var key = name.toLowerCase(java.util.Locale.ROOT);
        if (groups.containsKey(key)) return false;
        groups.put(key, new WorldGroup(name));
        save();
        return true;
    }

    public boolean delete(String name) {
        var key = name.toLowerCase(java.util.Locale.ROOT);
        var removed = groups.remove(key) != null;
        if (removed) save();
        return removed;
    }

    public Map<String, WorldGroup> groups() {
        return groups;
    }

    /** Directory holding the per-player snapshot files for the given group. */
    public File dataFolder(WorldGroup group) {
        return new File(new File(plugin.getDataFolder(), "playerdata"), group.name().toLowerCase(java.util.Locale.ROOT));
    }
}
