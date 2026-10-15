# Developer Documentation Hub 🛠️

Welcome to the internal technical documentation of **MultiverseProgramming**. This section details the complete software architecture, codebase structure, subsystem lifecycles, and the communication bridge connecting the Paper Minecraft plugin with the standalone Web Portal.

> 🌐 Versión en español: [wiki/es/dev/README.md](../../es/dev/README.md)

---

## 📚 Technical Documentation Guides

1. **[Core Architecture & Subsystems](Architecture.md)**
   - Plugin lifecycle (`MultiverseProgrammingPlugin`)
   - Sandboxed Lua runtime (`LuaRunner`) & instruction limiting
   - Threading model & synchronous dispatching (`SyncDispatcher`)
   - Floppy disk storage & duplication exploit security
   - NBT parsing (`NbtReader`) and 3D Blueprint rotation math (`BlueprintRotator`)

2. **[Web Portal & Communication Bridge](Web-Portal-and-Bridge.md)**
   - Embedded HTTP Server (`WebServerManager`) & port failover
   - Single-Page Application (SPA) architecture (`dashboard.html`, Three.js 3D WebGL engine)
   - REST API specification (`/api/blueprints`, `/api/upload`, `/api/turtles`, `/api/build`, etc.)
   - Bidirectional communication flow between browser and Minecraft server tick loop
   - Base64 payload decoding, validation, and storage quotas

3. **[Turtles, Quarry Engine & Peripherals](Turtles-and-Peripherals.md)**
   - Turtle state machine, inventory mechanics & fuel accumulator
   - Lateral Quarry Engine upgrade: physical lockstep attachment, dual holographic chests & auto-pause
   - Peripheral discovery (`PeripheralManager`) and deep dive into the 9 custom peripherals
   - Claim protection integrations (WorldGuard, ProtectionStones, BentoBox)
   - CoreProtect audit logging bridge (`CoreProtectBridge`)

---

## 🧭 Repository Map

```text
MultiverseProgramming/
├── src/main/java/com/multiverse/programming/
│   ├── MultiverseProgrammingPlugin.java  # Main plugin entry point (JavaPlugin)
│   ├── ConfigManager.java                # config.yml reader, defaults, validation
│   ├── DiskManager.java                  # Floppy disk NBT, item creation & program IO
│   ├── LuaRunner.java                    # Sandboxed LuaJ VM, instruction counter hook
│   ├── ComputerCommand.java              # /mvprog command handler & tab completions
│   ├── ComputerListener.java             # Computer block GUI & execution handler
│   ├── ItemSecurityListener.java         # Duplication exploit & container protection
│   ├── RecipeManager.java                # Dynamic shapeless/shaped crafting recipes
│   ├── blueprint/                        # NBT stream reading, litematica parser, rotator
│   │   ├── Blueprint.java
│   │   ├── BlueprintManager.java
│   │   ├── BlueprintParser.java
│   │   ├── BlueprintRotator.java
│   │   └── NbtReader.java
│   ├── peripheral/                       # Peripherals API & 9 custom machine adapters
│   │   ├── Peripheral.java
│   │   ├── PeripheralManager.java
│   │   ├── SyncDispatcher.java           # Async-to-Sync Bukkit bridge
│   │   ├── MonitorPeripheral.java
│   │   ├── CrafterPeripheral.java
│   │   ├── TransposerPeripheral.java
│   │   ├── SpeakerPeripheral.java
│   │   ├── ScannerPeripheral.java
│   │   ├── CartographerPeripheral.java
│   │   ├── AlchemistPeripheral.java
│   │   ├── FarmerPeripheral.java
│   │   └── NpcPeripheral.java
│   ├── protection/                       # Third-party claim hooks & CoreProtect logging
│   │   ├── ProtectionManager.java
│   │   └── CoreProtectBridge.java
│   ├── turtle/                           # Autonomous mobile turtle block & quarry upgrade
│   │   ├── Turtle.java
│   │   ├── TurtleManager.java
│   │   ├── TurtlePeripheral.java
│   │   ├── TurtleGUI.java
│   │   └── TurtleListener.java
│   └── web/                              # Embedded HTTP server & dashboard router
│       ├── WebServerManager.java
│       └── WebDashboardHtml.java
├── src/main/resources/
│   ├── config.yml                        # Server configuration template
│   ├── plugin.yml                        # Paper/Spigot plugin descriptor
│   └── dashboard.html                    # Single-Page Web Portal (Three.js WebGL)
└── wiki/
    ├── en/                               # English documentation
    │   └── dev/                          # English developer guides
    └── es/                               # Spanish documentation
        └── dev/                          # Spanish developer guides
```
