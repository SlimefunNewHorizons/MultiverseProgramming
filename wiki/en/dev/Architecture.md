# Core Architecture & Subsystems (English) 🏛️

This document details the internal design, threading model, security sandbox, and data structures powering **MultiverseProgramming**.

---

## 1. Plugin Lifecycle & Architecture Overview

`MultiverseProgrammingPlugin` is the central singleton extending `org.bukkit.plugin.java.JavaPlugin`. It acts as the dependency injection root and coordinator for all operational subsystems:

```
                          ┌─────────────────────────────────────┐
                          │    MultiverseProgrammingPlugin      │
                          └──────────────────┬──────────────────┘
                                             │
      ┌──────────────────┬───────────────────┼───────────────────┬──────────────────┐
      ▼                  ▼                   ▼                   ▼                  ▼
┌──────────────┐  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ConfigManager │  │ DiskManager  │   │BlueprintMgr  │   │TurtleManager │   │ WebServerMgr │
└──────────────┘  └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘
      │                  │                   │                   │                  │
      ▼                  ▼                   ▼                   ▼                  ▼
  config.yml       Vanilla NBT        .litematic / .nbt     Mobile Block      Embedded HTTP
  Hot Reload        Floppy Disk       Gzip Binary Stream    State Machine    Workers / Routes
```

### Lifecycle Initialization Sequence (`onEnable`):
1. **Configuration**: Initializes [`ConfigManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/ConfigManager.java), validates keys, applies backward-compatible defaults, and logs loaded peripherals.
2. **Third-Party Integrations**:
   - Soft-depends on `CoreProtect` (`CoreProtectBridge`): verifies API version 9+ for audit logging.
   - Soft-depends on claims plugins (`ProtectionManager`): hooks into `WorldGuard`, `ProtectionStones` and `BentoBox`.
3. **Core Managers**:
   - [`DiskManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/DiskManager.java): Prepares floppy disk factories and template programs.
   - [`BlueprintManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/blueprint/BlueprintManager.java): Scans `plugins/MultiverseProgramming/blueprints/`, loads metadata into cache, and registers default community blueprints.
   - [`TurtleManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/turtle/TurtleManager.java): Initializes thread-safe spatial map (`ConcurrentHashMap<Location, Turtle>`).
   - [`WebServerManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/web/WebServerManager.java): Binds embedded HTTP server and begins listening for web traffic.
4. **Command & Listener Registration**:
   - Registers the `/mvprog` command with `ComputerCommand`.
   - Registers event listeners: `ComputerListener`, `TurtleListener`, and `ItemSecurityListener`.
   - Registers custom shaped/shapeless crafting recipes via `RecipeManager`.

### Graceful Shutdown Sequence (`onDisable`):
- Cancels all running Lua execution threads.
- Cancels all active Turtle build tasks and cleans up in-world holographic displays (`TextDisplay` / `ArmorStand`).
- Stops `WebServerManager` and terminates worker threads with `executor.shutdownNow()`.
- Unregisters dynamic crafting recipes to prevent duplicate recipe warnings on server reload.

---

## 2. Sandboxed Lua Execution Engine (`LuaRunner.java`)

MultiverseProgramming embeds **LuaJ** (JSE implementation of Lua 5.2). To run arbitrary player-written scripts safely without freezing or crashing the server, `LuaRunner` enforces multi-tiered sandboxing:

### A. Whitelist-Only Standard Libraries
To prevent security exploits, filesystem probing, or malicious reflection:
- **Allowed**: `_G`, `table`, `string`, `math`, `bit32`.
- **Blocked**: `io`, `os`, `luajava`, `package`, `debug` (except an internal instruction-counter hook), and `coroutine`.
- Attempts to call `os.execute`, `io.open`, or instantiate Java classes via reflection result in immediate runtime errors.

### B. CPU Instruction Count Quota (Anti-Infinite Loop)
A classic risk in programmable blocks is a script containing:
```lua
while true do end
```
If executed on the main thread, this freezes Minecraft permanently. If executed asynchronously without quotas, it locks CPU cores indefinitely.

`LuaRunner` injects an instruction counter via LuaJ's `DebugLib`:
```java
// Sets an instruction count hook every 1,000 instructions
globals.get("debug").get("sethook").call(
    new ZeroArgFunction() {
        @Override
        public LuaValue call() {
            instructionCounter.addAndGet(1000);
            if (instructionCounter.get() > maxInstructions) { // Default: 1,000,000
                throw new LuaError("Execution instruction limit exceeded (CPU quota reached)");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new LuaError("Execution terminated by user");
            }
            return LuaValue.NIL;
        }
    },
    LuaValue.valueOf(""),
    LuaValue.valueOf(1000)
);
```

### C. Syntax Validation
Before launching an execution thread, `LuaRunner.validate(code)` attempts a dry-run compile using `globals.load(code)`. If a syntax error exists (e.g. missing `end`, unclosed quote), the player receives an immediate syntax error notification with line and column numbers before any game ticks are spent.

---

## 3. Threading Model & Synchronous Dispatching (`SyncDispatcher.java`)

Minecraft's Bukkit/Paper API is strictly **single-threaded** for world state modifications (setting block types, spawning particles, moving entities, accessing container inventories).

However, player Lua scripts must execute **asynchronously** to allow sleep delays, long computations, and non-blocking operation:

```
[ Async Worker Thread (LuaRunner) ]
              │
              │  turtle.forward() / peripheral call
              ▼
   [ SyncDispatcher.sync() ]
              │
              ├── Checks: Is current thread the primary Bukkit thread?
              │     ├── YES: Execute callable directly.
              │     └── NO:  Submit Callable to Bukkit.getScheduler().callSyncMethod()
              ▼
   [ Future.get(1500, TimeUnit.MILLISECONDS) ]
              │
              ├─► [ Main Server Thread ] ──► Executes block change / drops
              │
              ▼
   Returns result to Lua thread (or throws LuaError on 1500ms timeout)
```

### Why this design matters:
- **No World Corruption**: World edits are never executed concurrently from async threads.
- **Deadlock Protection**: Every synchronous call is bounded by a **1,500 ms timeout**. If the server is severely lagging or frozen, the Lua script times out gracefully instead of hanging the async worker pool.

---

## 4. Floppy Disk Storage & Duplication Exploit Defense

### Floppy Disk Data Architecture (`DiskManager.java`)
Floppy disks are stored in vanilla Minecraft items using `Material.WRITTEN_BOOK` (or `BOOK_AND_QUILL`).
- **Program Storage**: Code is stored directly in the `BookMeta` page text. Because Minecraft allows up to 100 pages of 255 characters each (~25.5 KB of code), programs are seamlessly chunked and reconstructed when read or written.
- **Identification & Tagging**: Disks store unique metadata in the item's `PersistentDataContainer` (PDC):
  - `NamespacedKey("multiverseprogramming", "disk_id")`
  - `NamespacedKey("multiverseprogramming", "disk_label")`
  - `NamespacedKey("multiverseprogramming", "disk_author")`

### Duplication Exploit Defense (`ItemSecurityListener.java`)
In multiplayer servers, custom GUIs can be vulnerable to inventory desync exploits (e.g., cursor glitches, shift-click race conditions, placing disks into non-standard storage).
`ItemSecurityListener` monitors:
- `InventoryClickEvent`, `InventoryDragEvent`, and `InventoryMoveItemEvent`.
- Validates slot ranges: players can only insert/extract from designated disk slots (`slot 0` in Computers and Turtles).
- Decorative filler panes (`Material.GRAY_STAINED_GLASS_PANE`) and status buttons are strictly cancelled.
- Duplication exploit protection is applied to item frames, chiseled bookshelves, and decorated pots, ensuring items cannot be cloned via concurrent click timing.

---

## 5. Blueprint Binary Pipeline (`NbtReader`, `BlueprintParser`, `BlueprintRotator`)

### A. Binary Stream Decoding (`NbtReader.java`)
Minecraft structure files are binary NBT (Named Binary Tag). Rather than bundling heavy external NBT libraries, MultiverseProgramming implements an optimized, zero-dependency binary reader:
- Automatically handles Big-Endian byte streams.
- Supported tag types: `TAG_End` (0), `TAG_Byte` (1), `TAG_Short` (2), `TAG_Int` (3), `TAG_Long` (4), `TAG_Float` (5), `TAG_Double` (6), `TAG_ByteArray` (7), `TAG_String` (8), `TAG_List` (9), `TAG_Compound` (10), `TAG_IntArray` (11), `TAG_LongArray` (12).

### B. Format Auto-Detection (`BlueprintParser.java`)
The parser inspects file headers to identify the schematic format:
1. **Litematica (`.litematic`)**:
   - GZIP-compressed compound containing `Regions`, `BlockStatePalette`, and bit-packed `BlockStates` long arrays.
   - Decompresses the bit-packed array based on bits-per-block index calculation.
2. **Vanilla Structure NBT (`.nbt`)**:
   - Standard Minecraft `palette` and `blocks` compound tags with relative `[x, y, z]` integer coordinates and block state strings.

### C. 3D Structure Rotation Math (`BlueprintRotator.java`)
When a player or web user requests rotating a structure (e.g. 90°, 180°, 270°), the blocks must be mathematically transposed and block directional properties updated:

#### Coordinate Transformation:
For a structure with dimensions `[sizeX, sizeY, sizeZ]`:
- **0°**: $(x', z') = (x, z)$
- **90° Clockwise**: $(x', z') = (sizeZ - 1 - z, x)$, with new dimensions $(sizeZ, sizeY, sizeX)$
- **180°**: $(x', z') = (sizeX - 1 - x, sizeZ - 1 - z)$
- **270° Clockwise**: $(x', z') = (z, sizeX - 1 - x)$, with new dimensions $(sizeZ, sizeY, sizeX)$

#### Directional BlockState Regex Rewriting:
Blocks with directional states (stairs, chests, repeaters, doors, signs) have their string states rewritten:
- `facing=north` $\to$ `facing=east` (for 90°)
- `axis=x` $\to$ `axis=z`
- `shape=straight` vs `shape=inner_left`, etc.

### D. Security Filtering & Illegal Blocks (`BlueprintSecurityValidator.java`)
Before blueprints are stored or dispatched to turtles, structures are passed through `BlueprintSecurityValidator.sanitizeAndValidate()`:
- **Stripped Dangerous / Illegal Blocks**: Replaces illegal blocks with AIR to protect survival worlds from exploits:
  - `BEDROCK`, `REINFORCED_DEEPSLATE`
  - `BARRIER`, `LIGHT`, `STRUCTURE_BLOCK`, `STRUCTURE_VOID`, `JIGSAW`
  - `COMMAND_BLOCK`, `CHAIN_COMMAND_BLOCK`, `REPEATING_COMMAND_BLOCK`
  - `END_PORTAL`, `END_PORTAL_FRAME`, `END_GATEWAY`
  - `NETHER_PORTAL`
- **Resource Constraints**: Enforces limits defined in `config.yml`:
  - `blueprint-max-file-size-mb` (10 MB default)
  - `blueprint-max-dimension` (512 blocks)
  - `blueprint-max-blocks` (250,000 blocks)
  - Prevents GZIP zip-bombs and memory exhaustion attacks.
