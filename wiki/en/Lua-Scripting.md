# Lua Scripting

The plugin embeds **Luaj 3.0** (pure Java Lua runtime), fully isolated and sandboxed for Paper/Purpur 1.21.11, 26.1 and 26.2.

## Available Libraries

Programs have access to safe standard Lua libraries:

- `string` — `string.format`, `string.sub`, …
- `table` — `table.insert`, `table.concat`, …
- `math` — `math.random`, `math.floor`, …
- `coroutine`
- `bit32`
- `sleep(seconds_or_ms)` — cooperative non-blocking sleep (e.g. `sleep(0.5)` for 500 ms, or `sleep(100)` for 100 ms).
- `peripheral` — discovery and control of connected hardware peripherals.

The global `print(...)` function sends output directly to the player's chat.

## Performance Optimization & Large Codebases

The execution engine has been heavily optimized to allow extensive programs without degrading server TPS:

1. **Bounded Thread Pool:** Scripts run inside a managed worker pool (`MultiverseLua-Worker`), preventing thread exhaustion.
2. **Cooperative Time-Slicing:** Long-running math or simulation loops on advanced computers yield CPU cycles cooperatively when continuous bytecode instructions exceed the slice quota.
3. **Cooperative `sleep`:** Calling `sleep()` pauses the Lua thread cleanly and resets execution quotas.

---

## Hardware Peripherals & Machines

Placing any of these machine blocks **adjacent** (up, down, north, south, east, or west) to a Computer exposes their Lua APIs automatically.

### 1. Display Monitor (`monitor`)

Projects floating multiline text in the world using Minecraft's native `TextDisplay` entities.

```lua
monitor.clear()
monitor.write("&a=== SYSTEM STATUS ===")
monitor.write("&eCPU: &aNormal")
monitor.write("&bMemory: &f100%")

monitor.setLine(2, "&eCPU: &cALERT")
local text = monitor.getText()
```

A monitor holds at most 20 lines of 256 characters. `write` scrolls (drops the oldest line) when full; `setLine` above 20 raises an error.

### 2. Auto-Crafter (`crafter`)

Interfaces with the Minecraft 1.21 **Crafter** block for automated recipe manufacturing.

```lua
local items = crafter.getItems()
for slot, item in pairs(items) do
  print("Slot " .. slot .. ": " .. item.name .. " x" .. item.count)
end

crafter.setSlotDisabled(5, true)

local success = crafter.craft()
if success then
  print("&aItem crafted and dispensed!")
end
```

`craft()` uses the real recipe: remainders such as empty buckets stay in the grid, and special recipes (dyes, shulker recolor, book copies) keep item data.

### 3. Inventory Transposer (`transposer`)

High-speed inspection, sorting, and routing of items between adjacent containers (chests, barrels, hoppers, shulkers).

```lua
local directions = transposer.getDirections()
local size = transposer.getSlotCount("north")

local item = transposer.getItem("north", 1)
if item then
  print("Found: " .. item.name .. " x" .. item.count)
end

-- Transfer 16 items from slot 1 in 'north' to 'south'
local moved = transposer.transferItem("north", "south", 1, 16)
print("Moved: " .. moved)
```

Transfers follow vanilla rules: shulker boxes cannot go inside shulker boxes. Each transfer fires `InventoryMoveItemEvent`, so container-lock plugins can block it (it returns `0`).

### 4. Sound Synthesizer (`speaker`)

Plays musical notes across 2 octaves, raw audio frequencies in Hz, and Minecraft sound effects.

```lua
speaker.playNote("harp", 12)
sleep(0.2)
speaker.playNote("bell", 16)

-- Play frequency in Hz (playable range 185-740 Hz; 370 Hz = F#4 = native pitch)
speaker.playTone(440.0)

-- Play vanilla sound effects: playSound(name, [volume 0-3], [pitch 0.5-2])
speaker.playSound("entity.player.levelup", 1.0, 1.2)
```

### 5. Block & Entity Scanner (`scanner`)

Scans surrounding entities, players, and blocks within a specified radius.

```lua
-- Scan nearby entities (max radius 32)
local entities = scanner.scanEntities(16)
for i, ent in ipairs(entities) do
  print(ent.name .. " (" .. ent.type .. ") at distance " .. math.floor(ent.distance))
end

-- Scan nearby players with health and hunger levels
local players = scanner.scanPlayers(32)
for i, p in ipairs(players) do
  print(p.name .. " HP: " .. p.health .. " Food: " .. p.foodLevel)
end

-- Scan blocks with material name filter (e.g. "DIAMOND", "ORE")
local ores = scanner.scanBlocks(8, "DIAMOND")
for i, b in ipairs(ores) do
  print(b.material .. " at [" .. b.x .. ", " .. b.y .. ", " .. b.z .. "]")
end

-- Inspect one block by offset from the scanner (dx, dy, dz; max 16 blocks).
-- Scan results include dx/dy/dz too, so they can be passed straight in.
-- Returns nil if the chunk is not loaded.
local block = scanner.inspect(ores[1].dx, ores[1].dy, ores[1].dz)
if block then
  print("Material: " .. block.material .. " Data: " .. block.blockData)
end
```

Positions in results always include absolute world coordinates (`x`, `y`, `z`) and the offset from the scanner (`dx`, `dy`, `dz`).

### 6. Cartographer & Map Renderer (`cartographer`)

Inspects biomes, scans topographic elevations, renders ASCII radar views directly to adjacent monitors, and generates filled map items.

```lua
-- Query current biome
local biome = cartographer.getBiome()
print("Biome: " .. (biome or "Unknown"))

-- Render topographic radar view directly onto an adjacent monitor
local ok, err = cartographer.renderToMonitor("north", 6)
if ok then
  print("Topography rendered to monitor!")
end

-- Generate a vanilla filled map item (scale 0 to 4)
-- Consumes one empty map (or paper) from an adjacent container
local success, mapId = cartographer.createMap(1)
if success then
  print("Map #" .. mapId .. " created in adjacent container!")
end
```

### 7. Potion & Alchemical Synthesizer (`alchemist`)

Automates brewing recipes, queries potion stands, and synthesizes potions pulling water bottles and ingredients directly from connected containers.

```lua
-- List all known synthesizable potion recipes
local recipes = alchemist.getRecipes()
for i, r in ipairs(recipes) do
  print(i .. ": " .. r)
end

-- Inspect attached brewing stand fuel and progress
local stand = alchemist.inspectStand()
print("Fuel level: " .. stand.fuelLevel)

-- Synthesize a potion: alchemist.brew(potionType, [modifier], [isSplash])
-- Base: a plain Water Bottle (other potions are never consumed)
-- Modifiers: "normal", "extended" (Redstone -> LONG_ variant), "strong" (Glowstone -> STRONG_ variant)
-- Fails if the potion has no such variant, or if both modifiers are given
local ok, msg = alchemist.brew("SPEED", "extended", false)
if ok then
  print("&a" .. msg)
else
  print("&cSynthesis failed: " .. msg)
end
```

### 8. Farming / Harvesting Attachment (`farmer`)

Inspects crop maturity, automatically harvests mature crops, replants seeds, and fertilizes crops using bone meal from adjacent chests.

```lua
-- Inspect crop maturity (by side, or by offset dx, dy, dz from the farmer)
local crop = farmer.inspectCrop("down")
if crop.isCrop then
  print("Crop: " .. crop.material .. " Mature: " .. tostring(crop.mature))
end

-- Harvest a single crop (replant = true)
local harvested = farmer.harvest("down", true)

-- Harvest by offset: 1 block down, 3 blocks south (max 12 blocks on each axis)
farmer.harvest(0, -1, 3, true)

-- Harvest entire area (radius up to 12) with auto-replant
local count = farmer.harvestArea(4, true)
print("Harvested " .. count .. " mature crops!")

-- Fertilize crop using bone meal from adjacent container
local fertilized = farmer.fertilize("down")
```

### 9. NPC Chatbot & Quest Interposer (`npc`)

Enables interactive dialogues, floating TextDisplay holograms, chat choice prompts, and capturing player responses.

```lua
-- Set floating hologram nameplate
npc.setName("&6[Grand Wizard]")

-- Spawn as another mob: only living, spawnable, non-boss mobs are accepted
-- (anything else, e.g. "TNT" or "ENDER_DRAGON", falls back to VILLAGER)
npc.spawn("ZOMBIE")

-- Send dialogue message to specific player
npc.say("Steve", "Welcome to the enchanted academy!")

-- Ask player a multiple-choice question
local options = {"Accept Quest", "Decline Quest", "Ask for information"}
npc.ask("Steve", "Will you assist in defending our realm?", options)

-- Wait for player's chat response
while true do
  local response = npc.getLastResponse("Steve")
  if response then
    print("Steve responded: " .. response)
    npc.clearResponse("Steve")
    if response == "1" or string.find(response:lower(), "accept") then
      npc.say("Steve", "Splendid! May the arcane winds guide you.")
    end
    break
  end
  sleep(1.0)
end
```

### Generic `peripheral` API

For multi-peripheral configurations:

```lua
local sides = peripheral.getNames() -- {"north", "up"}
local pType = peripheral.getType("up") -- "monitor"

local monitor = peripheral.wrap("up")
monitor.setText("Hello from top monitor")

local crafter = peripheral.find("crafter")
if crafter then
  crafter.craft()
end
```

---

## 10. Programmable Turtle (`turtle`)

The **Programmable Turtle** is a robotic mobile computer & constructor capable of navigating the world, mining blocks, placing blocks, managing a 16-slot inventory, and building entire structures from `.litematic` and `.nbt` blueprint files.

### Movement & Rotation

```lua
turtle.forward()    -- Moves 1 block forward in the facing direction
turtle.back()       -- Moves 1 block backward
turtle.up()         -- Moves 1 block up
turtle.down()       -- Moves 1 block down
turtle.turnLeft()   -- Turns 90 degrees to the left
turtle.turnRight()  -- Turns 90 degrees to the right
```

### Mining & Placing Blocks

```lua
turtle.dig()        -- Mines the block in front (adds to turtle inventory)
turtle.digUp()      -- Mines the block above
turtle.digDown()    -- Mines the block below

turtle.place()      -- Places block from selected slot in front
turtle.placeUp()    -- Places block above
turtle.placeDown()  -- Places block below
```

- Digging, placing and moving respect claims (WorldGuard, ProtectionStones, BentoBox) as the turtle's owner. Inside a claim the owner cannot build in, the action returns `false`.
- Digging a container (chest, barrel...) collects its contents into the turtle inventory.
- `place` refuses items that hold stored contents, such as a filled shulker box, so they are not emptied.

### Inventory & Fuel

```lua
turtle.select(1)           -- Selects slot 1 to 16
local slot = turtle.getSelectedSlot()
local count = turtle.getItemCount(1)
local item = turtle.getItemDetail(1) -- {name = "stone", count = 64}

local fuel = turtle.getFuelLevel()
turtle.refuel(10)          -- Consumes coal or blaze rods to refuel
```

### Blueprint Construction (`.litematic` & `.nbt`)

```lua
-- Load blueprint metadata
local bp = turtle.loadBlueprint("BP-A1B2")
print("Blueprint: " .. bp.name)
print("Dimensions: " .. bp.sizeX .. "x" .. bp.sizeY .. "x" .. bp.sizeZ)
print("Total Blocks: " .. bp.totalBlocks)

-- Start construction at relative coordinates (relX, relY, relZ) from turtle position:
-- turtle.build(bpId, relX, relY, relZ, [clear], [orientation])
-- Use (0, 0, 0) to start construction exactly at the turtle's current position.
-- orientation can be "NORTH", "EAST", "SOUTH", "WEST", or degrees (0, 90, 180, 270)
local ok, err = turtle.build("BP-A1B2", 0, 0, 0, false, "EAST")
if not ok then
  print("Failed to build: " .. err)
end

-- Monitor progress
local prog = turtle.getBuildProgress()
print("Progress: " .. prog.percentage .. "% (" .. prog.current .. "/" .. prog.total .. ")")

-- Execution control
turtle.pauseBuild()
turtle.resumeBuild()
turtle.cancelBuild()
```

> **Construction Supply & Fuel Chests**:
> When `turtle-require-materials` or `turtle-fuel-required` are enabled on the server, the turtle automatically spawns designated supply chests with floating holograms (`"Place construction blocks here"` and `"Place fuel here"`). The turtle pulls blocks and fuel directly from these chests as needed.

### Quarry Engine Upgrade (Autonomous Volumetric Excavation)

> [!IMPORTANT]
> **Exclusive to Turtles:**
> The Quarry Engine is **not** a computer peripheral and cannot be operated from stationary lectern or enchanting table computers. It functions **strictly** as a lateral attachment for Programmable Turtles via the `turtle.quarry` API.

The **Quarry Engine** (`BLAST_FURNACE` by default) attaches directly to the Turtle:

- **Lateral Attachment Requirement:** The Quarry Engine block must be placed directly adjacent to the **left** or **right** of the turtle before launching the program.
- **Physical Movement:** Once activated, the quarry engine attaches to the turtle and travels in lockstep across coordinates and rotations as the turtle excavates.
- **Dual Supply & Storage Chests:** At launch, the turtle automatically places:
  1. `📦 Mined Blocks Storage`: Stores all extracted block drops.
  2. `⚡ Place fuel here`: Replenishes turtle fuel during operation.
- **Full Storage Detection:** If the mined blocks storage chest fills up, the turtle automatically pauses mining (`"Paused: Mined blocks storage chest is full"`) to prevent lost items. Empty the chest and resume via `turtle.resumeQuarry()` or the in-game GUI.
- **+20% Fuel Consumption:** As an active heavy attachment, the turtle consumes 20% more fuel (1.20x rate) during operations.

```lua
-- 1. Check if the lateral engine is attached
if not turtle.hasQuarryEngine() then
  print("Please attach a Quarry Engine to the left or right of the turtle!")
  return
end

-- 2. Start volumetric excavation: turtle.quarry(width, length, targetY, [handleLiquids])
-- Mines a 16x16 column down to layer Y=11, clearing water/lava by default
local ok, err = turtle.quarry(16, 16, 11, true)
if not ok then
  print("Failed to start quarry: " .. err)
  return
end
print("Quarry excavation started!")

-- 3. Query quarry status and progress
local q = turtle.getQuarryStatus()
print("Status: " .. q.status .. " (" .. q.message .. ")")
print("Progress: " .. q.percentage .. "% (" .. q.blocksMined .. "/" .. q.totalBlocks .. ")")
print("Current Layer: Y=" .. q.currentY .. " Target: Y=" .. q.targetY .. " Side: " .. (q.side or "none"))

-- 4. Manual pause, resume, and cancel controls
turtle.pauseQuarry()
turtle.resumeQuarry()
turtle.stopQuarry()
```

### Turtle Management, Ownership & Stop Controls

Placed turtles automatically remember the **Player UUID** of the player who placed them. This ownership is persisted in the block's `PersistentDataContainer` (PDC) and stored in `turtles.yml` so it survives server restarts and world unloads.

#### Protection & Anti-Griefing
- **Anti-Break Protection:** Only the owning player or a server administrator (`multiverseprogramming.admin`) can break/disassemble placed turtles.
- **Claim Protection:** `dig`, `place` and movement are checked against WorldGuard, ProtectionStones and BentoBox for the turtle's owner. Turtles without an owner cannot modify claimed land.
- **Inventory & Disk Drops:** When broken by the owner or an admin, all 16 inventory items and the inserted disk are dropped safely on the ground.

#### Halting Tasks & Construction (`/mvprog stop`)
Turtles can be halted at any moment through three mechanisms:
1. **Chat Command (`/mvprog stop`):**
   - Running `/mvprog stop` lists all turtles owned by you, showing their location and status (e.g. `BUILDING (45%)`, `MINING (Y=32)`, `IDLE`) with interactive `[STOP]` clickable buttons.
   - Running `/mvprog stop <turtleId>` (e.g. `/mvprog stop T-001`) immediately halts blueprint building and quarry mining on that specific turtle. Other players cannot stop your turtles.
   - Running `/mvprog stop all` stops all active tasks across all turtles you own.
2. **Administrator Command (`multiverseprogramming.admin`):**
   - Admins running `/mvprog stop` receive a global overview of all server turtles and their respective owners.
   - Admins can stop any turtle by ID (`/mvprog stop <turtleId>`), regardless of who placed it.
   - Admins can halt every active turtle on the server with `/mvprog stop all`.
3. **In-Game GUI Task Button:**
   - Right-clicking the Turtle block opens its 16-slot inventory and control panel.
   - **Slot 4 (Task Control Anvil):** Left-click toggles pause/resume. **Right-click immediately stops/cancels** any running build or excavation.

---

## 11. Web Portal, Cloud Pastebin & Quotas

Players can access the built-in **Web Portal** by executing in-game:
```text
/mvprog web
```

### Web Portal Features:
1. **File Upload Dropzone:** Drag and drop `.litematic` (Litematica) or `.nbt` (Vanilla Structure) files directly from your browser.
2. **Layer-by-Layer Visualizer:** Interactive 3D WebGL (Three.js) slice visualizer with a Y-layer slider, block colors, and 2D sliced layer preview.
3. **Materials Required:** Instant breakdown of needed blocks and item counts.
4. **Remote Dispatch to Turtles:** Select any online Turtle, enter relative coordinates (`~ ~ ~` or `0, 0, 0` for current position), and click **"⚡ INITIATE CONSTRUCTION"**.
5. **Live Job Monitoring:** Real-time progress bar with pause/cancel controls.

### Universal Cloud Pastebin & Storage Quotas:
- **Download Blueprint from Cloud Pastebin / GitHub:**  
  `/mvprog get <code|url>`  
  Instantly fetches blueprints stored on Bytebin, GitHub, or public URLs and caches them locally for turtles to build.
- **Check Storage Quota & Free Space:**  
  `/mvprog quota`  
  Displays your total blueprint disk storage usage (in MB) and remaining available quota.
- **Manage Loaded Blueprints:**  
  `/mvprog bp list` — Lists all registered blueprints and author tags.  
  `/mvprog bp delete <blueprintId>` — Deletes a blueprint owned by you.