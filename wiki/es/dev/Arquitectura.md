# Arquitectura del Núcleo y Subsistemas (Español) 🏛️

Este documento detalla el diseño interno, el modelo de hilos (threading), el entorno de seguridad (sandbox) y las estructuras de datos que componen **MultiverseProgramming**.

---

## 1. Ciclo de Vida del Plugin y Visión General de la Arquitectura

`MultiverseProgrammingPlugin` es la clase singleton central que hereda de `org.bukkit.plugin.java.JavaPlugin`. Funciona como la raíz de inyección de dependencias y el coordinador general de todos los subsistemas:

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
  config.yml        NBT Vanilla       .litematic / .nbt   Bloque Móvil      HTTP Embebido
  Recarga en vivo   Disquete         Flujo Binario Gzip   Máquina Estados   Workers / Rutas
```

### Secuencia de Inicialización (`onEnable`):
1. **Configuración**: Inicializa [`ConfigManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/ConfigManager.java), valida las claves, aplica valores predeterminados con retrocompatibilidad y registra los periféricos activados.
2. **Integraciones con Terceros**:
   - Dependencia suave de `CoreProtect` (`CoreProtectBridge`): verifica la API v9+ para el registro de auditoría de bloques.
   - Dependencia suave de plugins de reclamos (`ProtectionManager`): enlaza con `WorldGuard`, `ProtectionStones` y `BentoBox`.
3. **Gestores del Núcleo**:
   - [`DiskManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/DiskManager.java): Prepara la factoría de disquetes e ítems con programas plantilla.
   - [`BlueprintManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/blueprint/BlueprintManager.java): Escanea la carpeta `plugins/MultiverseProgramming/blueprints/`, carga los metadatos en caché e indexa los esquemas comunitarios.
   - [`TurtleManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/turtle/TurtleManager.java): Inicializa el mapa espacial thread-safe (`ConcurrentHashMap<Location, Turtle>`).
   - [`WebServerManager`](file:///c:/Users/danie/OneDrive/Documentos/Github/Personal/MultiverseProgramming/src/main/java/com/multiverse/programming/web/WebServerManager.java): Vincula el servidor HTTP embebido y comienza la escucha de peticiones web.
4. **Comandos y Listeners**:
   - Registra el comando `/mvprog` mediante `ComputerCommand`.
   - Registra los escuchadores de eventos: `ComputerListener`, `TurtleListener` e `ItemSecurityListener`.
   - Registra recetas de crafteo personalizadas en `RecipeManager`.

### Secuencia de Apagado Limpio (`onDisable`):
- Cancela todos los hilos de ejecución de Lua activos.
- Cancela todas las tareas de construcción de las Tortugas y elimina los hologramas del mundo (`TextDisplay` / `ArmorStand`).
- Detiene `WebServerManager` y finaliza el pool de hilos con `executor.shutdownNow()`.
- Da de baja las recetas dinámicas para evitar advertencias de recetas duplicadas al recargar el servidor.

---

## 2. Motor Lua en Entorno Aislado (`LuaRunner.java`)

MultiverseProgramming utiliza **LuaJ** (implementación JSE de Lua 5.2). Para ejecutar scripts arbitrarios escritos por los jugadores de forma segura sin congelar ni corromper el servidor, `LuaRunner` aplica una seguridad estricta:

### A. Lista Blanca de Librerías Estándar
Para prevenir accesos indebidos al sistema operativo o reflexión maliciosa:
- **Permitidas**: `_G`, `table`, `string`, `math`, `bit32`.
- **Bloqueadas**: `io`, `os`, `luajava`, `package`, `debug` (salvo el hook interno para contar instrucciones) y `coroutine`.
- Intentos de llamar a `os.execute`, `io.open` o instanciar clases Java resultan en errores inmediatos en tiempo de ejecución.

### B. Límite de Instrucciones de CPU (Protección contra Bucles Infinitos)
Un riesgo común en bloques programables es un script como:
```lua
while true do end
```
Si esto se ejecutara en el hilo principal, congelaría Minecraft para siempre. Si se ejecuta en un hilo secundario sin cuotas, consumiría los núcleos de la CPU indefinidamente.

`LuaRunner` inyecta un contador de instrucciones mediante la librería `DebugLib` de LuaJ:
```java
// Establece un hook cada 1,000 instrucciones ejecutadas
globals.get("debug").get("sethook").call(
    new ZeroArgFunction() {
        @Override
        public LuaValue call() {
            instructionCounter.addAndGet(1000);
            if (instructionCounter.get() > maxInstructions) { // Por defecto: 1,000,000
                throw new LuaError("Límite de instrucciones excedido (cuota de CPU alcanzada)");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new LuaError("Ejecución cancelada por el usuario");
            }
            return LuaValue.NIL;
        }
    },
    LuaValue.valueOf(""),
    LuaValue.valueOf(1000)
);
```

### C. Validación Sintáctica Previa
Antes de iniciar un hilo de ejecución, `LuaRunner.validate(code)` intenta compilar el código en memoria con `globals.load(code)`. Si existe un error sintáctico (ej. falta un `end`, comillas sin cerrar), el jugador recibe una notificación detallada con la línea y columna del error sin consumir recursos del servidor.

---

## 3. Modelo de Hilos y Despacho Síncrono (`SyncDispatcher.java`)

La API de Bukkit/Paper en Minecraft es estrictamente **monohilo** para modificar el estado del mundo (cambiar tipos de bloque, generar partículas, mover entidades, acceder a inventarios).

Sin embargo, los scripts de Lua deben ejecutarse de forma **asíncrona** para permitir pausas (`sleep`), cálculos prolongados y evitar congelar los ticks del servidor:

```
[ Hilo Asíncrono de Trabajo (LuaRunner) ]
              │
              │  turtle.forward() / llamada a periférico
              ▼
   [ SyncDispatcher.sync() ]
              │
              ├── ¿El hilo actual es el principal de Bukkit?
              │     ├── SÍ: Ejecutar la llamada directamente.
              │     └── NO: Enviar Callable a Bukkit.getScheduler().callSyncMethod()
              ▼
   [ Future.get(1500, TimeUnit.MILLISECONDS) ]
              │
              ├─► [ Hilo Principal del Servidor ] ──► Ejecuta cambios en el mundo
              │
              ▼
   Devuelve resultado a Lua (o lanza LuaError tras 1500 ms de timeout)
```

### Ventajas de este diseño:
- **Cero corrupción de mundos**: Las modificaciones al mundo nunca se ejecutan concurrentemente.
- **Protección contra Bloqueos Mutuos (Deadlocks)**: Cada llamada síncrona está limitada por un **timeout de 1,500 ms**. Si el servidor sufre lag extremo, el script de Lua falla limpiamente en lugar de colgar el hilo de trabajo.

---

## 4. Almacenamiento en Disquetes y Prevención de Duplicaciones

### Estructura de Datos del Disquete (`DiskManager.java`)
Los disquetes se almacenan en ítems vanilla utilizando `Material.WRITTEN_BOOK` (o `BOOK_AND_QUILL`).
- **Almacenamiento de Código**: El código se guarda directamente en las páginas del `BookMeta`. Dado que Minecraft permite hasta 100 páginas de 255 caracteres cada una (~25.5 KB de código), los programas se fragmentan y reconstruyen de manera transparente.
- **Metadatos y PDC**: Los disquetes guardan identificadores únicos en el `PersistentDataContainer` (PDC):
  - `NamespacedKey("multiverseprogramming", "disk_id")`
  - `NamespacedKey("multiverseprogramming", "disk_label")`
  - `NamespacedKey("multiverseprogramming", "disk_author")`

### Defensa contra Exploits de Duplicación (`ItemSecurityListener.java`)
En servidores multijugador, las interfaces con contenedores pueden sufrir exploits de desincronización (glitches con el cursor, shift-click concurrente).
`ItemSecurityListener` supervisa:
- `InventoryClickEvent`, `InventoryDragEvent` e `InventoryMoveItemEvent`.
- Valida los rangos de slots: los jugadores solo pueden interactuar con los slots autorizados (`slot 0` para disquetes).
- Los paneles de cristal decorativos y botones de control son cancelados inmediatamente.
- La protección abarca marcos de ítems (item frames), atriles, libreros cincelados y macetas decoradas, impidiendo duplicar disquetes mediante clics rápidos concurrentes.

---

## 5. Pipeline Binario de Esquemas (`NbtReader`, `BlueprintParser`, `BlueprintRotator`)

### A. Lector Binario NBT Autónomo (`NbtReader.java`)
Los archivos de estructuras de Minecraft están codificados en formato binario NBT (Named Binary Tag). En lugar de depender de pesadas librerías externas, MultiverseProgramming implementa un lector binario nativo:
- Lee flujos Big-Endian de forma optimizada.
- Soporta todos los tipos de etiquetas: `TAG_End` (0), `TAG_Byte` (1), `TAG_Short` (2), `TAG_Int` (3), `TAG_Long` (4), `TAG_Float` (5), `TAG_Double` (6), `TAG_ByteArray` (7), `TAG_String` (8), `TAG_List` (9), `TAG_Compound` (10), `TAG_IntArray` (11), `TAG_LongArray` (12).

### B. Detección Automática de Formatos (`BlueprintParser.java`)
El analizador inspecciona las cabeceras binarias para clasificar el formato:
1. **Litematica (`.litematic`)**:
   - Estructura comprimida en GZIP que contiene `Regions`, `BlockStatePalette` y arreglos de bits empaquetados `BlockStates`.
   - Desempaqueta los bits por bloque calculando el índice correspondiente.
2. **Estructura Vanilla NBT (`.nbt`)**:
   - Etiquetas estándar `palette` y `blocks` con coordenadas relativas `[x, y, z]` y cadenas de estado de bloque.

### C. Rotación Matemática 3D (`BlueprintRotator.java`)
Al solicitar rotar una estructura (ej. 90°, 180°, 270°), los bloques se transponen matemáticamente y sus propiedades direccionales se recalculan:

#### Transformación de Coordenadas:
Para una estructura de dimensiones `[sizeX, sizeY, sizeZ]`:
- **0°**: $(x', z') = (x, z)$
- **90° Sentido Horario**: $(x', z') = (sizeZ - 1 - z, x)$, nuevas dimensiones $(sizeZ, sizeY, sizeX)$
- **180°**: $(x', z') = (sizeX - 1 - x, sizeZ - 1 - z)$
- **270° Sentido Horario**: $(x', z') = (z, sizeX - 1 - x)$, nuevas dimensiones $(sizeZ, sizeY, sizeX)$

#### Reescritura Direccional con Regex:
Los bloques con propiedades direccionales (escaleras, cofres, repetidores, puertas, carteles) reescriben su estado:
- `facing=north` $\to$ `facing=east` (en giro de 90°)
- `axis=x` $\to$ `axis=z`
- `shape=straight` vs `shape=inner_left`, etc.

### D. Filtrado de Seguridad y Bloques Ilegales (`BlueprintSecurityValidator.java`)
Antes de almacenar o enviar esquemas a las tortugas, las estructuras son analizadas por `BlueprintSecurityValidator.sanitizeAndValidate()`:
- **Bloques Peligrosos / Ilegales Eliminados**: Reemplaza bloques prohibidos por AIR para proteger mundos survival contra exploits:
  - `BEDROCK`, `REINFORCED_DEEPSLATE`
  - `BARRIER`, `LIGHT`, `STRUCTURE_BLOCK`, `STRUCTURE_VOID`, `JIGSAW`
  - `COMMAND_BLOCK`, `CHAIN_COMMAND_BLOCK`, `REPEATING_COMMAND_BLOCK`
  - `END_PORTAL`, `END_PORTAL_FRAME`, `END_GATEWAY`
  - `NETHER_PORTAL`
- **Límites de Recursos**: Aplica los topes configurados en `config.yml`:
  - `blueprint-max-file-size-mb` (10 MB por defecto)
  - `blueprint-max-dimension` (512 bloques de ancho/alto/largo)
  - `blueprint-max-blocks` (250,000 bloques)
  - Previene ataques de bombas ZIP GZIP y agotamiento de memoria.
