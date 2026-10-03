# AlmostPerWorlds

A minimal, Folia-safe fork of [PerWorlds](https://github.com/TheNextLvl-net/per-worlds) that does
exactly one thing: keeps each configured **world group** on its own inventory, ender chest, XP,
food/health, and (optionally) game mode - so worlds managed by
[Worlds](https://github.com/TheNextLvl-net/worlds) (or anything else) don't share a single
inventory across the whole server.

## WARNING: Made with AI (cuz idk how to use Java)
## PLEASE SUPPORT [THE ORIGINAL DEVELOPER](https://github.com/TheNextLvl-net)

I am a random dev, and you should really not trust random stuff you find on the Internet. Take a look at the source code which is only a few hundred lines long. (click on the GitHub "view source" link on the side). And if you prefer, compile the project yourself with `mvn clean package` (requires Java JDK 21+ and Maven installed).

## What's ported vs. what isn't

**Ported (player-scoped, safe on Folia):**
- Inventory, armor, off-hand
- Ender chest
- XP / level
- Food level, saturation, exhaustion
- Health
- Potion effects
- Location (x/y/z, rotation and which world of the group) - when a player re-enters a group from
  another group they're teleported back to where they last were in it. First visits are never
  teleported. Disable with `restore-location: false` in `config.yml`.
- Game mode - always saved per group, so it never leaks between groups. A player who returns to
  a group gets back the game mode they had there. `/almostperworlds gamemode <group> <mode>` only
  sets the *starting* game mode for players who have never been in that group (e.g. creative in
  your test world); it is never re-applied to returning players.

**Deliberately dropped (world-scoped, unsafe on Folia / not needed since "Worlds" owns this):**
- Time, weather, difficulty/hardcore, game rule, and world border syncing across a group's worlds
- Chat/tablist per-group behavior
- Multiverse-Inventories / MyWorlds importers
- The `net.thenextlvl.*` command framework, i18n bundle, metrics, and version checker the
  original plugin depended on - replaced with a plain `CommandExecutor`/`TabCompleter` and no
  external runtime dependencies besides the Paper API itself, so this builds against nothing but
  `paperweight.paperDevBundle(...)`.

The dropped features all required reading or writing a **second world's** state synchronously
from inside an event fired on a different world/region - that's the part that isn't safe on
Folia's per-region threading model (and is exactly what the original author's commented-out
`// foliaSupported = true` line was flagging). Everything kept here only ever touches the single
player who triggered the event, dispatched through `Bukkit.getAsyncScheduler()` for file I/O and
`player.getScheduler()` to apply the result - the standard Folia-safe pattern for entity-scoped
work. It also runs fine on regular (non-Folia) Paper.

## Commands

```
/almostperworlds create <name>
/almostperworlds delete <name>
/almostperworlds addworld <group> <world>
/almostperworlds removeworld <group> <world>
/almostperworlds list
/almostperworlds info <group>
/almostperworlds gamemode <group> <survival|creative|adventure|spectator|clear>
```
(alias: `/apw`, permission: `almostperworlds.command.group`)

Worlds not assigned to any group all share one implicit "default" bucket - same as vanilla,
so nothing is lost for worlds you never group.

## Building

```
./gradlew shadowJar
```

The output jar will be at `build/libs/almost-per-worlds-<version>-all.jar`.

> # NOTE
> Technically you could also use `./gradlew build` which generates `build/libs/almost-per-worlds-<version>.jar`,
> which is the same as the `-all` version since Shadow isn't actually used in this project for now.

## Install

Requires [Worlds](https://github.com/TheNextLvl-net/worlds) (or your own world manager) to
actually create the worlds - AlmostPerWorlds only groups worlds that already exist and are
loaded when you run `addworld`.
