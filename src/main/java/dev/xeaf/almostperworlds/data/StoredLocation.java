package dev.xeaf.almostperworlds.data;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A plain-data location (world name + coordinates) that can be held across threads and written
 * to disk without keeping a reference to a live {@link World}.
 */
public record StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {

    public static StoredLocation of(Location location) {
        return new StoredLocation(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public void save(ConfigurationSection section) {
        section.set("world", world);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("yaw", (double) yaw);
        section.set("pitch", (double) pitch);
    }

    /** @return the stored location, or {@code null} if the section is missing or incomplete. */
    public static StoredLocation load(ConfigurationSection section) {
        if (section == null) return null;
        var world = section.getString("world");
        if (world == null) return null;
        return new StoredLocation(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }
}
