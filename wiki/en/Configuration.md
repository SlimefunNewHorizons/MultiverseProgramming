# Configuration

## config.yml

The configuration file is created automatically at `plugins/MultiverseProgramming/config.yml` on first run.

```yaml
# ==============================================================================
# MultiverseProgramming Configuration
# ==============================================================================

# Computer block types
computer-block: LECTERN
advanced-computer-block: ENCHANTING_TABLE
monitor-block: OCHRE_FROGLIGHT
crafter-block: CRAFTER
transposer-block: HOPPER
speaker-block: NOTE_BLOCK
turtle-block: DISPENSER
scanner-block: OBSERVER
cartographer-block: CARTOGRAPHY_TABLE
alchemist-block: BREWING_STAND
farmer-block: COMPOSTER
quarry-block: BLAST_FURNACE
npc-block: SCULK_CATALYST

# Crafting Recipes Toggles (enable/disable crafting per machine)
enable-recipe-computer: true
enable-recipe-advanced-computer: true
enable-recipe-turtle: true
enable-recipe-crafter: true
enable-recipe-monitor: true
enable-recipe-speaker: true
enable-recipe-transposer: true
enable-recipe-floppy-disk: true
enable-recipe-scanner: true
enable-recipe-cartographer: true
enable-recipe-alchemist: true
enable-recipe-farmer: true
enable-recipe-quarry: true
enable-recipe-npc: true

# Functionality Toggles (enable/disable machine mechanics)
enable-computer: true
enable-advanced-computer: true
enable-turtle: true
enable-crafter: true
enable-monitor: true
enable-speaker: true
enable-transposer: true
enable-scanner: true
enable-cartographer: true
enable-alchemist: true
enable-farmer: true
enable-quarry: true
enable-npc: true

# Region Protection Integration
worldguard-protection-check: true
protection-stones-require-owner: false

# Execution and Safety Limits
execution-timeout-ms: 3000
advanced-execution-timeout-ms: 0
max-instructions-per-run: 1000000
drop-disks-on-break: true
prevent-spam-delay-ms: 500

# Turtle Automation Settings
turtle-fuel-required: false
turtle-fuel-per-action: 1
turtle-initial-fuel: 1000
turtle-max-fuel: 100000
turtle-build-delay-ticks: 1
turtle-require-materials: false

# Blueprint Nexus & Web Server
web-portal-enabled: true
web-portal-host: "0.0.0.0"
web-portal-port: 8080
web-public-url: ""
blueprint-player-quota-mb: 15.0
```

### Configuration Options Reference

| Key | Type | Default | Description |
|---|---|---|---|
| `computer-block` | `string` | `LECTERN` | Material used as standard computer block |
| `advanced-computer-block` | `string` | `ENCHANTING_TABLE` | Material used as advanced computer block |
| `quarry-block` | `string` | `BLAST_FURNACE` | Material used as Turtle Quarry Engine Upgrade (functions exclusively with turtles) |
| `enable-recipe-*` | `boolean` | `true` | Independent toggle to enable or disable crafting recipe for each device |
| `enable-*` | `boolean` | `true` | Independent toggle to enable or disable gameplay functionality for each device |
| `worldguard-protection-check` | `boolean` | `true` | Prevents turtles from building, digging or moving in WorldGuard regions without membership/ownership |
| `protection-stones-require-owner` | `boolean` | `false` | When `true`, requires the turtle owner to be the region owner (not just member) in ProtectionStones |
| `turtle-fuel-required` | `boolean` | `false` | When `true`, spawns a dedicated Fuel Chest with hologram `"Place fuel here"` and requires fuel |
| `turtle-require-materials` | `boolean` | `false` | When `true`, spawns a Supply Chest with hologram `"Place construction blocks here"` |
| `execution-timeout-ms` | `long` | `3000` | Max run time for standard computers before timeout |
| `advanced-execution-timeout-ms` | `long` | `0` | Max run time for advanced computers (`0` = no limit) |
| `max-instructions-per-run` | `long` | `1000000` | Safety instruction ceiling to prevent infinite loops from hanging the thread |
| `blueprint-player-quota-mb` | `double` | `15.0` | Maximum blueprint disk storage allocation per player |

---

## Commands

All commands are prefixed with `/mvprog`.

### Player Commands
| Command | Permission | Description |
|---|---|---|
| `/mvprog help` | `multiverseprogramming.use` | Displays available commands and usage guide |
| `/mvprog web` | `multiverseprogramming.use` | Displays Web Dashboard link for uploading `.litematic` & `.nbt` |
| `/mvprog get <code\|url>` | `multiverseprogramming.use` | Downloads blueprint from cloud nexus (counts towards quota) |
| `/mvprog quota` | `multiverseprogramming.use` | Displays player's blueprint storage usage and remaining quota |
| `/mvprog bp list` | `multiverseprogramming.use` | Lists all saved blueprints and sizes |
| `/mvprog bp quota` | `multiverseprogramming.use` | Shows storage quota status |
| `/mvprog bp delete <id>` | `multiverseprogramming.use` | Deletes a personal blueprint to free up quota |
| `/mvprog stop [id\|all]` | `multiverseprogramming.use` | Displays your placed turtles with interactive `[STOP]` buttons, stops a specific owned turtle by ID, or stops all your active turtles (`all`) |
| `/mvprog turtle [list\|stop]` | `multiverseprogramming.use` | Lists your placed turtles or halts their active build and mining operations |

### Administrator Commands
| Command | Permission | Description |
|---|---|---|
| `/mvprog build <bp\|code> <x> <y> <z> [turtle] [clear] [orientation]` | `multiverseprogramming.admin` | Orders a turtle to construct a blueprint at relative coordinates from turtle (`~ ~ ~` or `0 0 0` for current position) with optional orientation (`NORTH`, `EAST`, `SOUTH`, `WEST`, `0`, `90`, `180`, `270`) and obstruction clearing |
| `/mvprog stop [id\|all]` | `multiverseprogramming.admin` | Lists all server turtles with owner names, stops any turtle by ID regardless of owner, or halts all active turtles across the entire server (`all`) |
| `/mvprog quota <player>` | `multiverseprogramming.admin` | Checks storage quota and disk usage of a specific player |
| `/mvprog getbypass <code\|url>` | `multiverseprogramming.admin` | Downloads blueprints bypassing personal storage quotas |
| `/mvprog bp clean [days]` | `multiverseprogramming.admin` | Purges unpinned blueprints older than specified days |
| `/mvprog give <item>` | `multiverseprogramming.admin` | Gives custom plugin items (`floppydisk`, `computer`, `advancedcomputer`, `turtle`, `monitor`, `crafter`, `transposer`, `speaker`) |
| `/mvprog reload` | `multiverseprogramming.admin` | Reloads `config.yml` and crafting recipes dynamically without server restart |

---

## Permissions

The plugin registers **three** permission nodes. If you use a permissions plugin (LuckPerms, PermissionsEx, etc.), grant these nodes to your ranks.

| Permission node | Default | What it enables |
|---|---|---|
| `multiverseprogramming.use` | `true` (all players) | Base access: using **computers** (opening the GUI, validating and running disks) and the `/mvprog` command with its player subcommands (`help`, `web`, `get`, `quota`, `bp ...`, `stop`, `turtle`), downloading blueprints and checking storage quotas. |
| `multiverseprogramming.turtle` | `true` (all players) | **Turtle** access: placing Turtles, opening their control panel, running Lua scripts on them, refueling, using the Supply Station terminal, and managing the access list of a Turtle you own. |
| `multiverseprogramming.admin` | `op` (server operators) | Full administrative access: `/mvprog build`, `give`, `reload`, `getbypass`, `bp clean`; viewing/managing other players' quotas and blueprints; stopping or breaking **any** Turtle regardless of owner; bypassing region/claim protection. |

### Permission inheritance

Children are declared in `plugin.yml`, so granting a parent node also grants its children:

- `multiverseprogramming.admin` → grants `multiverseprogramming.use` **and** `multiverseprogramming.turtle`.
- `multiverseprogramming.use` → grants `multiverseprogramming.turtle`.

You only need to grant the top-level node for a rank; you do not have to list every node separately.

### Per-Turtle ownership

The `multiverseprogramming.turtle` node decides whether a player can interact with Turtles **at all**. On top of it, every Turtle enforces its own ownership:

- The **owner** (whoever placed it) can always open, operate and break it.
- The owner can authorize other players using the **Access Control** button in the Turtle panel; authorized players can open and operate **that specific** Turtle.
- **Breaking / disassembling** a Turtle is reserved for its owner or an administrator — authorized operators cannot dismantle it. A Turtle with no registered owner can only be removed by an administrator.
- Administrators (`multiverseprogramming.admin`) bypass all of the above.

> 💡 Revoking `multiverseprogramming.turtle` from a player takes effect immediately: they will no longer be able to place Turtles or open any panel, even for Turtles they own.

---

## Region Protection & Security

The plugin automatically hooks into **WorldGuard** and **ProtectionStones** (without hard dependencies):
1. **Region Permission Verification**: Blueprint builds and quarries verify their whole volume before starting, and every single `dig`, `place` and turtle movement checks its target block. If the turtle's owner is not a member or owner of the claim, the action is cancelled. BentoBox islands are checked as well.
2. **Owner-Only Restrictions**: By enabling `protection-stones-require-owner: true`, admins can enforce that only region owners (not regular members) can build using turtles inside claims.
3. **Admin Bypass**: Server administrators with `multiverseprogramming.admin` or `*` bypass claim restrictions.
4. **Dupe & Chunk-Ban Prevention**: Turtles and automated containers strictly prohibit storing nested containers (e.g. shulker boxes inside chests) and writable/written books inside container inventories during construction.