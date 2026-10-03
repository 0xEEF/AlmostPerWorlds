package dev.xeaf.almostperworlds.command;

import dev.xeaf.almostperworlds.group.GroupManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class GroupCommand {

    private static final String LABEL = "almostperworlds";

    private final GroupManager groupManager;

    public GroupCommand(GroupManager groupManager) {
        this.groupManager = groupManager;
    }

    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("almostperworlds.command.group")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
            return;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "addworld" -> addWorld(sender, args);
            case "removeworld" -> removeWorld(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "gamemode" -> gameMode(sender, args);
            default -> sendUsage(sender);
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /almostperworlds create <name>");
            return;
        }
        if (groupManager.create(args[1])) {
            sender.sendMessage(ChatColor.GREEN + "Created group '" + args[1] + "'.");
        } else {
            sender.sendMessage(ChatColor.RED + "A group with that name already exists (or the name is reserved).");
        }
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /almostperworlds delete <name>");
            return;
        }
        if (groupManager.delete(args[1])) {
            sender.sendMessage(ChatColor.GREEN + "Deleted group '" + args[1] + "'.");
            sender.sendMessage(ChatColor.YELLOW + "Note: stored player data for that group was left on disk untouched.");
        } else {
            sender.sendMessage(ChatColor.RED + "No group with that name exists.");
        }
    }

    private void addWorld(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /almostperworlds addworld <group> <world>");
            return;
        }
        var group = groupManager.get(args[1]).orElse(null);
        if (group == null) {
            sender.sendMessage(ChatColor.RED + "No group with that name exists.");
            return;
        }
        var world = Bukkit.getWorld(args[2]);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "No loaded world named '" + args[2] + "'.");
            return;
        }
        if (group.addWorld(world.getName())) {
            groupManager.save();
            sender.sendMessage(ChatColor.GREEN + "Added '" + world.getName() + "' to group '" + group.name() + "'.");
        } else {
            sender.sendMessage(ChatColor.RED + "That world is already in this group.");
        }
    }

    private void removeWorld(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /almostperworlds removeworld <group> <world>");
            return;
        }
        var group = groupManager.get(args[1]).orElse(null);
        if (group == null) {
            sender.sendMessage(ChatColor.RED + "No group with that name exists.");
            return;
        }
        if (group.removeWorld(args[2])) {
            groupManager.save();
            sender.sendMessage(ChatColor.GREEN + "Removed '" + args[2] + "' from group '" + group.name() + "'.");
        } else {
            sender.sendMessage(ChatColor.RED + "That world isn't in this group.");
        }
    }

    private void list(CommandSender sender) {
        if (groupManager.groups().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No groups defined yet. Every world currently shares one inventory.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Groups: " + ChatColor.RESET +
                String.join(", ", groupManager.groups().values().stream().map(g -> g.name()).toList()));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /almostperworlds info <group>");
            return;
        }
        var group = groupManager.get(args[1]).orElse(null);
        if (group == null) {
            sender.sendMessage(ChatColor.RED + "No group with that name exists.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Group '" + group.name() + "': " + ChatColor.RESET +
                (group.worlds().isEmpty() ? "(no worlds assigned)" : String.join(", ", group.worlds())));
        sender.sendMessage(ChatColor.GOLD + "Starting game mode (first visit only): " + ChatColor.RESET +
                group.defaultGameMode().map(Enum::name).orElse("(none)"));
    }

    private void gameMode(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /almostperworlds gamemode <group> <survival|creative|adventure|spectator|clear>");
            return;
        }
        var group = groupManager.get(args[1]).orElse(null);
        if (group == null) {
            sender.sendMessage(ChatColor.RED + "No group with that name exists.");
            return;
        }
        if (args[2].equalsIgnoreCase("clear")) {
            group.defaultGameMode(null);
            groupManager.save();
            sender.sendMessage(ChatColor.GREEN + "Cleared the starting game mode for group '" + group.name() + "'.");
            return;
        }
        GameMode mode;
        try {
            mode = GameMode.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "Unknown game mode '" + args[2] + "'. Use survival, creative, adventure or spectator.");
            return;
        }
        group.defaultGameMode(mode);
        groupManager.save();
        sender.sendMessage(ChatColor.GREEN + "Group '" + group.name() + "' will now put players who have never joined it in " + mode + ".");
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "/" + LABEL + " create <name>");
        sender.sendMessage(ChatColor.GOLD + "/" + LABEL + " delete <name>");
        sender.sendMessage(ChatColor.GOLD + "/" + LABEL + " addworld <group> <world>");
        sender.sendMessage(ChatColor.GOLD + "/" + LABEL + " removeworld <group> <world>");
        sender.sendMessage(ChatColor.GOLD + "/" + LABEL + " list");
        sender.sendMessage(ChatColor.GOLD + "/" + LABEL + " info <group>");
        sender.sendMessage(ChatColor.GOLD + "/" + LABEL + " gamemode <group> <survival|creative|adventure|spectator|clear>");
    }

    public List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(List.of("create", "delete", "addworld", "removeworld", "list", "info", "gamemode"), args[0]);
        }
        if (args.length == 2 && List.of("delete", "addworld", "removeworld", "info", "gamemode").contains(args[0].toLowerCase())) {
            return filter(groupManager.groups().values().stream().map(g -> g.name()).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("addworld")) {
            return filter(Bukkit.getWorlds().stream().map(World::getName).toList(), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("gamemode")) {
            return filter(List.of("survival", "creative", "adventure", "spectator", "clear"), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        var lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).collect(Collectors.toCollection(ArrayList::new));
    }
}
