package dev.xeaf.almostperworlds;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.xeaf.almostperworlds.command.GroupCommand;
import dev.xeaf.almostperworlds.group.GroupManager;
import dev.xeaf.almostperworlds.listener.PlayerDataListener;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * AlmostPerWorlds - a small, Folia-safe fork of PerWorlds that does exactly one thing:
 * keeps each configured world group on its own inventory/ender-chest/XP/health, so worlds
 * managed by "Worlds" (or anything else) don't share a single inventory.
 * <p>
 * Everything PerWorlds did that requires touching more than one world/region at a time
 * (time, weather, difficulty, game rules, world border syncing) was deliberately left out -
 * that's the part that isn't safe to run on Folia's per-region threading model, and it isn't
 * needed here since "Worlds" already owns world management.
 */
public final class AlmostPerWorlds extends JavaPlugin {

    private GroupManager groupManager;
    private PlayerDataListener playerDataListener;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        saveDefaultConfig();

        var syncGameMode = getConfig().getBoolean("sync-game-mode", false);
        var debug = getConfig().getBoolean("debug", false);

        groupManager = new GroupManager(this);
        groupManager.load();

        playerDataListener = new PlayerDataListener(this, groupManager, syncGameMode, debug);
        getServer().getPluginManager().registerEvents(playerDataListener, this);
        playerDataListener.start();

        var groupCommand = new GroupCommand(groupManager);

        // Registered via Paper's Brigadier lifecycle API rather than plugin.yml: this avoids
        // relying on the plugin-yml Gradle plugin's "commands" DSL, and it's the officially
        // supported, reflection-free way to register commands on modern Paper/Folia.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var literal = Commands.literal("almostperworlds")
                    .executes(ctx -> {
                        groupCommand.execute(ctx.getSource().getSender(), new String[0]);
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .suggests((ctx, builder) -> {
                                var tokens = builder.getRemaining().split(" ", -1);
                                var options = groupCommand.suggest(ctx.getSource().getSender(), tokens);
                                var prefix = tokens[tokens.length - 1];
                                var offset = builder.createOffset(builder.getInput().length() - prefix.length());
                                options.forEach(offset::suggest);
                                return offset.buildFuture();
                            })
                            .executes(ctx -> {
                                var raw = StringArgumentType.getString(ctx, "args");
                                var args = raw.isEmpty() ? new String[0] : raw.split(" ");
                                groupCommand.execute(ctx.getSource().getSender(), args);
                                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                            }))
                    .build();

            event.registrar().register(literal, "Manage AlmostPerWorlds world groups", List.of("apw"));
        });
    }

    @Override
    public void onDisable() {
        if (playerDataListener != null) playerDataListener.stop();
        if (groupManager != null) groupManager.save();
    }

    public GroupManager groupManager() {
        return groupManager;
    }
}
