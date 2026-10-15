# Centro de Documentación para Desarrolladores 🛠️

Bienvenido a la documentación técnica interna de **MultiverseProgramming**. Este apartado detalla a fondo la arquitectura de software, la estructura del código fuente, el ciclo de vida de los subsistemas y el puente de comunicación que conecta el plugin de Paper Minecraft con el Portal Web interactivo.

> 🌐 English version: [wiki/en/dev/README.md](../../en/dev/README.md)

---

## 📚 Guías Técnicas de Documentación

1. **[Arquitectura del Núcleo y Subsistemas](Arquitectura.md)**
   - Ciclo de vida del plugin (`MultiverseProgrammingPlugin`)
   - Entorno aislado de Lua (`LuaRunner`) y cuota de instrucciones de CPU
   - Modelo de hilos y despacho síncrono seguro (`SyncDispatcher`)
   - Almacenamiento en disquetes y prevención de exploits de duplicación
   - Parser binario NBT (`NbtReader`) y matemáticas de rotación 3D (`BlueprintRotator`)

2. **[Portal Web y Puente de Comunicación](Portal-Web-y-Puente.md)**
   - Servidor HTTP embebido (`WebServerManager`) y conmutación por error de puertos
   - Arquitectura SPA del portal (`dashboard.html`, motor Three.js WebGL 3D)
   - Especificación completa de la API REST (`/api/blueprints`, `/api/upload`, `/api/turtles`, etc.)
   - Flujo de comunicación bidireccional entre navegador y el bucle principal de Minecraft
   - Decodificación Base64, validación de esquemas y cuotas por jugador

3. **[Tortugas, Motor de Cantera y Periféricos](Tortugas-y-Perifericos.md)**
   - Máquina de estados de la Tortuga, inventario y acumulador de combustible
   - Mejora de Cantera Lateral (Quarry Engine): acoplamiento físico en lockstep, cofres duales y pausa automática
   - Detección dinámica de periféricos (`PeripheralManager`) y análisis de los 9 periféricos
   - Protección de reclamos (WorldGuard, ProtectionStones, BentoBox)
   - Auditoría y registro con CoreProtect (`CoreProtectBridge`)

---

## 🧭 Mapa del Repositorio

```text
MultiverseProgramming/
├── src/main/java/com/multiverse/programming/
│   ├── MultiverseProgrammingPlugin.java  # Punto de entrada principal (JavaPlugin)
│   ├── ConfigManager.java                # Lector de config.yml, valores por defecto
│   ├── DiskManager.java                  # Disquetes NBT, creación de ítems y lectura/escritura
│   ├── LuaRunner.java                    # Máquina virtual LuaJ aislada y contador de CPU
│   ├── ComputerCommand.java              # Manejador de /mvprog y autocompletado
│   ├── ComputerListener.java             # Interfaz GUI de computadoras y ejecución
│   ├── ItemSecurityListener.java         # Protección anti-duplicación y de contenedores
│   ├── RecipeManager.java                # Recetas de crafteo dinámicas
│   ├── blueprint/                        # Lectura de flujo NBT, litematica y rotador
│   │   ├── Blueprint.java
│   │   ├── BlueprintManager.java
│   │   ├── BlueprintParser.java
│   │   ├── BlueprintRotator.java
│   │   └── NbtReader.java
│   ├── peripheral/                       # API de periféricos y los 9 adaptadores
│   │   ├── Peripheral.java
│   │   ├── PeripheralManager.java
│   │   ├── SyncDispatcher.java           # Puente asíncrono a síncrono para Bukkit
│   │   ├── MonitorPeripheral.java
│   │   ├── CrafterPeripheral.java
│   │   ├── TransposerPeripheral.java
│   │   ├── SpeakerPeripheral.java
│   │   ├── ScannerPeripheral.java
│   │   ├── CartographerPeripheral.java
│   │   ├── AlchemistPeripheral.java
│   │   ├── FarmerPeripheral.java
│   │   └── NpcPeripheral.java
│   ├── protection/                       # Reclamos de terceros y registro CoreProtect
│   │   ├── ProtectionManager.java
│   │   └── CoreProtectBridge.java
│   ├── turtle/                           # Bloque móvil de tortuga y mejora de cantera
│   │   ├── Turtle.java
│   │   ├── TurtleManager.java
│   │   ├── TurtlePeripheral.java
│   │   ├── TurtleGUI.java
│   │   └── TurtleListener.java
│   └── web/                              # Servidor HTTP embebido y enrutador web
│       ├── WebServerManager.java
│       └── WebDashboardHtml.java
├── src/main/resources/
│   ├── config.yml                        # Plantilla de configuración
│   ├── plugin.yml                        # Descriptor del plugin Paper/Spigot
│   └── dashboard.html                    # Portal Web SPA (Three.js WebGL)
└── wiki/
    ├── en/                               # Documentación en inglés
    │   └── dev/                          # Guías para desarrolladores en inglés
    └── es/                               # Documentación en español
        └── dev/                          # Guías para desarrolladores en español
```
