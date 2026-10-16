# Turtles, Quarry Engine & Peripheral Ecosystem (English) 🐢⚙️

This document covers the internal design of the **Programmable Turtle**, the **Quarry Engine lateral attachment module**, the **Peripherals Architecture**, and the **Third-Party Protection & Audit Bridges**.

---

## 1. Turtle Architecture & State Machine (`Turtle.java`)

A Turtle is an autonomous in-game mobile block capable of navigation, block manipulation, and volumetric structure building.

```
       ┌────────────────────────────────────────────────────────┐
       │                       Status.IDLE                      │
       └──────────────┬──────────────────────────┬──────────────┘
                      │ startBuild()             │ startQuarry()
                      ▼                          ▼
       ┌────────────────────────┐      ┌────────────────────────┐
       │     Status.BUILDING    │      │      Status.MINING     │
       └──────────┬─────────────┘      └──────────┬─────────────┘
                  │ pauseBuild()                  │ pauseQuarry() / Storage Full
                  ▼                               ▼
       ┌────────────────────────────────────────────────────────┐
       │                      Status.PAUSED                     │
       └────────────────────────────────────────────────────────┘
```

### Key Components:
- **Spatial Tracking**: Registered in [`TurtleManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/turtle/TurtleManager.java) in dual lookup tables: `Location -> Turtle` and `id -> Turtle`.
- **Directional Facing**: Sanitized to horizontal axes (`NORTH`, `EAST`, `SOUTH`, `WEST`).
- **Inventory System**: 16 dedicated slots exposed to both the GUI and the Lua sandbox (`turtle.select(slot)`, `turtle.getItemDetail()`).
- **Hologram Displays**: Uses Paper 1.21 `TextDisplay` entities with automatic fallback to invisible `ArmorStand` markers for legacy worlds.
- **Relative Coordinate System**: All build execution endpoints (`turtle.build(bpId, relX, relY, relZ)` in Lua, `/mvprog build` in chat, and `/api/build` via REST) compute the blueprint anchor strictly **relative to the turtle's current world position**:
  $$\text{originX} = \text{turtleX} + relX$$
  $$\text{originY} = \text{turtleY} + relY$$
  $$\text{originZ} = \text{turtleZ} + relZ$$
  Passing `(0, 0, 0)` or `~ ~ ~` anchors the structure directly at the turtle's location rather than attempting to travel to distant global coordinates.

---

## 2. Quarry Engine Lateral Upgrade Module

The Quarry Engine turns the turtle into an autonomous, heavy-duty excavator. It demonstrates the engine upgrade pattern:

### A. Lateral Attachment Validation
Before excavation begins, the turtle validates that a Quarry Engine block (`BLAST_FURNACE` by default) is physically adjacent to its **left** or **right** relative to its horizontal facing:
```java
public LateralSide findLateralQuarrySide() {
    Material qMat = getQuarryBlockMaterial();
    Block b = location.getBlock();
    if (b == null) return null;
    
    BlockFace left = getLeftFace(this.facing);
    if (b.getRelative(left).getType() == qMat) return LateralSide.LEFT;
    
    BlockFace right = getRightFace(this.facing);
    if (b.getRelative(right).getType() == qMat) return LateralSide.RIGHT;
    
    return null;
}
```
If missing, excavation is rejected with `"No Quarry Engine attached to the lateral side of the turtle"`.

### B. Dual Chest Deployment & Holograms
At launch, the turtle automatically deploys:
1. **Mined Blocks Storage Chest**: Located directly behind the turtle, tagged with floating hologram `"§e📦 Mined Blocks Storage"`.
2. **Fuel Chest**: Located adjacent to the storage chest, tagged with `"§6⚡ Place fuel here"`.

### C. Full Storage Auto-Pause
To prevent destroying player blocks when storage is exhausted, [`isQuarryStorageFull()`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/turtle/Turtle.java) checks the chest:
1. If all chest slots are occupied and no items can stack, the turtle pauses immediately (`"Paused: Mined blocks storage chest is full"`).
2. The target block is **not** broken until space is freed.
3. Resuming via `turtle.resumeQuarry()` or the in-game GUI flushes any buffer and continues cleanly.

### D. Physical Lockstep Relocation (`relocateTurtleWithEngine()`)
As the turtle moves and rotates across layers, the attached engine block moves with it:
- During movements (`forward`, `back`, `up`, `down`), both target positions are checked for obstacles.
- During turns (`turnLeft`, `turnRight`), the engine pivots around the turtle to the new lateral coordinate.
- Upon completion, the turtle and engine return to the initial starting coordinates.

### E. +20% Fuel Consumption Rate
The quarry module consumes 20% more fuel (1.20x). To prevent IEEE 754 floating-point rounding errors over millions of operations, fuel is calculated using an integer point accumulator:
```java
quarryFuelPoints += 120; // 120 points per action
int toDeduct = quarryFuelPoints / 100;
quarryFuelPoints %= 100;
this.fuel -= toDeduct;
```

### F. Ownership Persistence & Lifecycle Controls
1. **Persistent Data Container (PDC) & File Storage (`turtles.yml`)**:
   - `turtle_owner` (String UUID) and `turtle_id` are written into the block's `TileState.getPersistentDataContainer()` upon placement and updated during every movement/relocation step (`updateBlockPdc()`).
   - `TurtleManager` persists all turtles in `turtles.yml` on placement, update, and server shutdown (`onDisable`), and restores them on `onEnable`.
2. **Owner Verification & Anti-Griefing**:
   - Regular players can only control and stop turtles where `owner.equals(player.getUniqueId())`.
   - Breaking/disassembling a placed turtle is restricted strictly to the owner or players with `multiverseprogramming.admin`.
3. **Execution Halting (`stopAnyWork()`)**:
   - Calling `turtle.stopAnyWork()` cleanly cancels both active build tasks (`cancelBuild()`) and quarry tasks (`cancelQuarry()`), removes all floating holograms (`cleanupHolograms()`), and resets status to `Status.IDLE`.

---

## 3. Peripheral Architecture (`PeripheralManager.java`)

Peripherals are modular hardware blocks adjacent to a Computer or Turtle. They implement the [`Peripheral`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/peripheral/Peripheral.java) interface:

```java
public interface Peripheral {
    String getType();
    Location getLocation();
    LuaTable toLuaTable();
}
```

When a program runs, `PeripheralManager.bindAll(globals, plugin, location, isTurtle)` scans the 6 adjacent block faces (`NORTH`, `SOUTH`, `EAST`, `WEST`, `UP`, `DOWN`) and injects matching Lua tables into the sandbox:

| Peripheral | Block Material | Lua Variable | Core Functions |
| :--- | :--- | :--- | :--- |
| **Monitor** | `OCHRE_FROGLIGHT` | `monitor` | `write(text)`, `setText(text)`, `setLine(n, text)`, `getLine(n)`, `getText()`, `getLineCount()`, `clear()` (max 20 lines x 512 chars) |
| **Crafter** | `CRAFTER` | `crafter` | `craft()`, `getItems()`, `isCrafting()`, `isSlotDisabled(slot)`, `setSlotDisabled(slot, bool)` |
| **Transposer** | `HOPPER` | `transposer` | `getDirections()`, `getSlotCount(dir)`, `getItem(dir, slot)`, `getAllItems(dir)`, `transferItem(fromDir, toDir, fromSlot, [count], [toSlot])`, `compareItems(...)` |
| **Speaker** | `NOTE_BLOCK` | `speaker` | `playNote(instrument, note)`, `playTone(freqHz)`, `playSound(name, [volume], [pitch])` |
| **Scanner** | `OBSERVER` | `scanner` | `scanEntities(radius)`, `scanPlayers(radius)`, `scanBlocks(radius, mat)`, `inspect(dx, dy, dz)` |
| **Cartographer** | `CARTOGRAPHY_TABLE` | `cartographer` | `getBiome()`, `scanTopography(radius)`, `createMap(scale)`, `project([radius])`, `renderToMonitor(side, [radius], [mode])`, `radar([side], [radius])` (live map, radius 8-64, default 64 = 128×128; radar mode needs a scanner on the same computer), `setHologram(text)`, `clear([side])` |
| **Alchemist** | `BREWING_STAND` | `alchemist` | `brew(type, [modifier], [splash])`, `getRecipes()`, `inspectStand()` |
| **Farmer** | `COMPOSTER` | `farmer` | `inspectCrop(side or dx, dy, dz)`, `harvest(side or dx, dy, dz, [replant])`, `harvestArea(radius, [replant])`, `fertilize(side or dx, dy, dz)` |
| **NPC** | `SCULK_CATALYST` | `npc` | `setName(name)`, `spawn([type])`, `say([player], msg)`, `ask([player], question, options)`, `getLastResponse([player])`, `setHologram(text)`, `despawn()` |

Block materials are the defaults and can be changed in `config.yml`. Positions passed as `dx, dy, dz` are offsets from the peripheral block; scan results return both absolute `x, y, z` and `dx, dy, dz`.

> [!NOTE]
> The **Quarry Engine** (`BLAST_FURNACE`) is **not** a computer peripheral. As detailed in [Section 2](#2-quarry-engine-lateral-upgrade-module), it is exclusively an autonomous lateral upgrade for the Programmable Turtle operated via `turtle.quarry(...)`.

---

## 4. Protection & Audit Logging Integrations

### Claim Protections (`ProtectionManager.java`)
`ProtectionManager` queries active region protection plugins by reflection, as the turtle's owner:
- `canBuildAt(owner, location)`: every single-block `dig`, `place` and movement.
- `checkBuildArea(owner, origin, blueprint)`: the whole volume before a blueprint build.
- `checkRegionArea(owner, min, max)`: the excavation box before a quarry starts.

Supported plugins:
- **WorldGuard**: Checks the `BUILD` flag and region membership (toggle: `worldguard-protection-check`).
- **ProtectionStones**: Checks region membership, or ownership with `protection-stones-require-owner: true`.
- **BentoBox**: Checks island membership.

Admins (`multiverseprogramming.admin`) bypass these checks. If the target is protected, the action fails (returns `false` or halts the task with an error message) and the world is not altered.

### CoreProtect Audit Bridge (`CoreProtectBridge.java`)
All block removals and block placements by Turtles, Computers, and Quarries are logged to CoreProtect:
```java
CoreProtectBridge.logRemoval(owner, turtleId, location, oldMaterial, oldBlockData);
CoreProtectBridge.logPlacement(owner, turtleId, location, newMaterial, newBlockData);
```
Server administrators using `/co inspect` or `/co lookup` can inspect the exact Turtle ID and player owner that modified any block.
