# Programación en Lua

El plugin integra **Luaj 3.0** (Lua en Java puro) totalmente aislado y sandboxed para Paper/Purpur 1.21.11, 26.1 y 26.2.

## Librerías disponibles

Los programas pueden usar las librerías estándar de Lua seguras:

- `string` — `string.format`, `string.sub`, …
- `table` — `table.insert`, `table.concat`, …
- `math` — `math.random`, `math.floor`, …
- `coroutine`
- `bit32`
- `sleep(ms_o_segundos)` — pausa cooperativa no bloqueante (ej. `sleep(0.5)` para 500 ms, o `sleep(100)` para 100 ms).
- `peripheral` — detección e interacción con periféricos conectados.

La función global `print(...)` se captura y se envía al chat del jugador que ejecuta.

## Optimización y Ejecución de Códigos Grandes

El motor de ejecución ha sido optimizado con salvaguardas avanzadas para permitir scripts masivos sin afectar el TPS del servidor:

1. **Pool de Hilos Acotado:** Los programas corren en un pool de hilos administrado (`MultiverseLua-Worker`), previniendo agotamiento de recursos JVM.
2. **Cooperative Time-Slicing:** Bucles extensos de cálculo en computadoras avanzadas aplican micro-pausas cooperativas si exceden la cuota de instrucciones continua, permitiendo compartir los núcleos de la CPU con los hilos principales del servidor.
3. **Pausa con `sleep`:** El uso de `sleep(tiempo)` reinicia la cuota de instrucciones y cede el hilo de forma segura.

---

## Periféricos y Máquinas Conectables

Colocar cualquiera de estas máquinas de forma **adyacente** (arriba, abajo, norte, sur, este u oeste) a una Computadora activa sus APIs directamente en Lua.

### 1. Display Monitor (`monitor`)

Proyecta texto flotante en tiempo real en el mundo utilizando entidades nativas `TextDisplay` de Minecraft.

```lua
-- Limpiar pantalla
monitor.clear()

-- Escribir texto (soporta códigos de color con & o §)
monitor.write("&a=== ESTADO DEL SISTEMA ===")
monitor.write("&eCPU: &aNormal")
monitor.write("&bMemoria: &f100%")

-- Modificar una línea específica (1-indexado)
monitor.setLine(2, "&eCPU: &cALERTA")

-- Leer contenido
local texto = monitor.getText()
```

Un monitor guarda como máximo 20 líneas de 256 caracteres. `write` desplaza el texto (borra la línea más antigua) cuando está lleno; `setLine` por encima de 20 lanza un error.

### 2. Auto-Crafter (`crafter`)

Controla el bloque **Crafter** de Minecraft 1.21 para automatizar ensamblaje de recetas.

```lua
-- Inspeccionar objetos en la cuadrícula 3x3
local items = crafter.getItems()
for slot, item in pairs(items) do
  print("Slot " .. slot .. ": " .. item.name .. " x" .. item.count)
end

-- Deshabilitar o habilitar una ranura (1 a 9)
crafter.setSlotDisabled(5, true)

-- Ejecutar fabricación
local exito = crafter.craft()
if exito then
  print("&a¡Objeto crafteado y dispensado!")
end
```

`craft()` usa la receta real: los remanentes, como los cubos vacíos, se quedan en la cuadrícula, y las recetas especiales (tintes, recolorear shulkers, copiar libros) conservan los datos del ítem.

### 3. Inventory Transposer (`transposer`)

Inspecciona y transfiere ítems a alta velocidad entre contenedores adyacentes (cofres, barriles, tolvas, etc.).

```lua
-- Ver direcciones que tienen contenedores conectados
local lados = transposer.getDirections()

-- Obtener cantidad de ranuras de un cofre al norte
local tam = transposer.getSlotCount("north")

-- Ver ítem en el slot 1
local item = transposer.getItem("north", 1)
if item then
  print("Encontrado: " .. item.name .. " x" .. item.count)
end

-- Transferir 16 ítems del slot 1 del cofre 'north' al cofre 'south'
local movidos = transposer.transferItem("north", "south", 1, 16)
print("Movidos: " .. movidos)
```

Las transferencias siguen las reglas vanilla: no se pueden meter shulkers dentro de shulkers. Cada transferencia lanza `InventoryMoveItemEvent`, así que los plugins de candados pueden bloquearla (devuelve `0`).

### 4. Sound Synthesizer (`speaker`)

Reproduce notas musicales con afinación exacta, frecuencias de audio en Hz y efectos de sonido de Minecraft.

```lua
-- Tocar notas (instrumento, nota de 0 a 24 semitonos)
speaker.playNote("harp", 12)
sleep(0.2)
speaker.playNote("bell", 16)

-- Tocar un tono por frecuencia en Hertz (rango tocable 185-740 Hz; 370 Hz = F#4 = tono nativo)
speaker.playTone(440.0)

-- Reproducir efectos de sonido: playSound(nombre, [volumen 0-3], [tono 0.5-2])
speaker.playSound("entity.player.levelup", 1.0, 1.2)
```

### 5. Block & Entity Scanner (`scanner`)

Escanea entidades, jugadores y bloques circundantes dentro de un radio configurable.

```lua
-- Escanear entidades cercanas (radio máximo 32)
local entidades = scanner.scanEntities(16)
for i, ent in ipairs(entidades) do
  print(ent.name .. " (" .. ent.type .. ") a distancia " .. math.floor(ent.distance))
end

-- Escanear jugadores cercanos con su nivel de vida y hambre
local jugadores = scanner.scanPlayers(32)
for i, p in ipairs(jugadores) do
  print(p.name .. " Vida: " .. p.health .. " Hambre: " .. p.foodLevel)
end

-- Escanear bloques filtrando por nombre de material (ej. "DIAMOND", "ORE")
local minerales = scanner.scanBlocks(8, "DIAMOND")
for i, b in ipairs(minerales) do
  print(b.material .. " en [" .. b.x .. ", " .. b.y .. ", " .. b.z .. "]")
end

-- Inspeccionar un bloque por desplazamiento desde el scanner (dx, dy, dz; máximo 16 bloques).
-- Los resultados de escaneo también traen dx/dy/dz, así que se pueden pasar directamente.
-- Devuelve nil si el chunk no está cargado.
local bloque = scanner.inspect(minerales[1].dx, minerales[1].dy, minerales[1].dz)
if bloque then
  print("Material: " .. bloque.material .. " Datos: " .. bloque.blockData)
end
```

Las posiciones en los resultados siempre incluyen las coordenadas absolutas del mundo (`x`, `y`, `z`) y el desplazamiento desde el scanner (`dx`, `dy`, `dz`).

### 6. Cartographer & Map Renderer (`cartographer`)

Inspecciona biomas, escanea elevaciones topográficas, renderiza mapas de radar ASCII directamente en monitores adyacentes y genera mapas de Minecraft con escala configurable.

```lua
-- Consultar bioma actual
local bioma = cartographer.getBiome()
print("Bioma: " .. (bioma or "Desconocido"))

-- Renderizar radar topográfico ASCII directamente en un monitor adyacente
local ok, err = cartographer.renderToMonitor("north", 6)
if ok then
  print("¡Topografía proyectada en el monitor!")
end

-- Generar un ítem de mapa de Minecraft con escala (0 a 4)
-- Consume un mapa vacío (o papel) de un contenedor adyacente
-- El mapa generado se deposita en un cofre adyacente o cae al suelo
local exito, mapId = cartographer.createMap(1)
if exito then
  print("¡Mapa #" .. mapId .. " creado exitosamente!")
end
```

### 7. Potion & Alchemical Synthesizer (`alchemist`)

Automatiza la fabricación de pociones, consulta recetas y sintetiza pociones extrayendo botellas de agua e ingredientes directamente desde cofres o barriles adyacentes.

```lua
-- Listar recetas de pociones disponibles
local recetas = alchemist.getRecipes()
for i, r in ipairs(recetas) do
  print(i .. ": " .. r)
end

-- Inspeccionar el soporte de pociones adyacente
local soporte = alchemist.inspectStand()
print("Nivel de combustible: " .. soporte.fuelLevel)

-- Sintetizar una poción: alchemist.brew(tipoPocion, [modificador], [esArrojadiza])
-- Base: una Botella de Agua normal (nunca se gastan otras pociones)
-- Modificadores: "normal", "extended" / "long" (Redstone -> variante LONG_), "strong" / "ii" (Glowstone -> variante STRONG_)
-- Falla si la poción no tiene esa variante o si se piden ambos modificadores
local ok, msg = alchemist.brew("SPEED", "extended", false)
if ok then
  print("&a" .. msg)
else
  print("&cError en síntesis: " .. msg)
end
```

### 8. Farming / Harvesting Module (`farmer`)

Inspecciona la madurez de los cultivos, cosecha automáticamente cultivos maduros, replanta semillas y fertiliza con polvo de hueso desde contenedores adyacentes.

```lua
-- Inspeccionar madurez de un cultivo (por lado, o por desplazamiento dx, dy, dz desde el farmer)
local cultivo = farmer.inspectCrop("down")
if cultivo.isCrop then
  print("Cultivo: " .. cultivo.material .. " Maduro: " .. tostring(cultivo.mature))
end

-- Cosechar un único cultivo (con replantado automático = true)
local cosechado = farmer.harvest("down", true)

-- Cosechar por desplazamiento: 1 bloque abajo, 3 al sur (máximo 12 bloques por eje)
farmer.harvest(0, -1, 3, true)

-- Cosechar un área completa (radio hasta 12) con replantado
local total = farmer.harvestArea(4, true)
print("¡Cosechados " .. total .. " cultivos maduros!")

-- Fertilizar cultivo usando polvo de hueso de cofres adyacentes
local fertilizado = farmer.fertilize("down")
```

### 9. NPC Chatbot & Quest Interposer (`npc`)

Crea diálogos interactivos, opciones de chat con jugadores, preguntas de opción múltiple y hologramas flotantes `TextDisplay`.

```lua
-- Configurar nombre flotante sobre el bloque
npc.setName("&6[Gran Archimago]")

-- Aparecer como otro mob: solo se aceptan mobs vivos, invocables y que no sean jefes
-- (cualquier otro, p. ej. "TNT" o "ENDER_DRAGON", se convierte en VILLAGER)
npc.spawn("ZOMBIE")

-- Enviar mensaje de diálogo a un jugador específico
npc.say("Steve", "¡Bienvenido a la academia arcana!")

-- Hacer una pregunta con opciones al jugador
local opciones = {"Aceptar Misión", "Rechazar Misión", "Pedir Información"}
npc.ask("Steve", "¿Deseas ayudarnos a defender el reino?", opciones)

-- Esperar la respuesta en el chat del jugador
while true do
  local respuesta = npc.getLastResponse("Steve")
  if respuesta then
    print("Steve respondió: " .. respuesta)
    npc.clearResponse("Steve")
    if respuesta == "1" or string.find(respuesta:lower(), "aceptar") then
      npc.say("Steve", "¡Excelente! Que los vientos arcanos te acompañen.")
    end
    break
  end
  sleep(1.0)
end
```

### API Genérica `peripheral`

Para configuraciones con múltiples periféricos del mismo tipo:

```lua
local nombres = peripheral.getNames() -- {"north", "up"}
local tipo = peripheral.getType("up")  -- "monitor"

local miPantalla = peripheral.wrap("up")
miPantalla.setText("Hola desde la parte superior")

local miCrafter = peripheral.find("crafter")
if miCrafter then
  miCrafter.craft()
end
```

---

## 10. Programmable Turtle (`turtle`)

La **Tortuga Programable** es un autómata robótico móvil y constructor capaz de desplazarse por el mundo, minar, colocar bloques, almacenar ítems en 16 ranuras internas y construir estructuras completas a partir de esquemas `.litematic` y `.nbt`.

### Métodos de Movimiento y Giro

```lua
turtle.forward()    -- Avanza 1 bloque en la dirección que mira
turtle.back()       -- Retrocede 1 bloque
turtle.up()         -- Sube 1 bloque
turtle.down()       -- Baja 1 bloque
turtle.turnLeft()   -- Gira 90 grados a la izquierda
turtle.turnRight()  -- Gira 90 grados a la derecha
```

### Minería y Colocación de Bloques

```lua
turtle.dig()        -- Mina el bloque al frente (guarda en su inventario)
turtle.digUp()      -- Mina el bloque superior
turtle.digDown()    -- Mina el bloque inferior

turtle.place()      -- Coloca el bloque de la ranura seleccionada al frente
turtle.placeUp()    -- Coloca hacia arriba
turtle.placeDown()  -- Coloca hacia abajo
```

- Minar, colocar y moverse respetan los claims (WorldGuard, ProtectionStones, BentoBox) en nombre del dueño de la tortuga. Dentro de un claim donde el dueño no puede construir, la acción devuelve `false`.
- Minar un contenedor (cofre, barril...) guarda su contenido en el inventario de la tortuga.
- `place` rechaza ítems con contenido guardado, como un shulker lleno, para que no se vacíen.

### Inventario y Combustible

```lua
turtle.select(1)           -- Selecciona la ranura 1 a 16
local slot = turtle.getSelectedSlot()
local cant = turtle.getItemCount(1)
local item = turtle.getItemDetail(1) -- {name = "stone", count = 64}

local fuel = turtle.getFuelLevel()
turtle.refuel(10)          -- Consume carbón o varas de blaze para recargar combustible
```

### Construcción de Diseños (`.litematic` y `.nbt`)

```lua
-- Cargar metadatos de un diseño subido al portal web o servidor
local bp = turtle.loadBlueprint("BP-A1B2")
print("Diseño: " .. bp.name)
print("Dimensiones: " .. bp.sizeX .. "x" .. bp.sizeY .. "x" .. bp.sizeZ)
print("Bloques totales: " .. bp.totalBlocks)

-- Iniciar construcción en coordenadas relativas (relX, relY, relZ) respecto a la posición de la tortuga:
-- turtle.build(bpId, relX, relY, relZ, [limpiar], [orientacion])
-- Usa (0, 0, 0) para construir comenzando exactamente en la ubicación de la tortuga.
-- orientacion puede ser "NORTH", "EAST", "SOUTH", "WEST", o grados (0, 90, 180, 270)
local ok, err = turtle.build("BP-A1B2", 0, 0, 0, false, "EAST")
if not ok then
  print("Error al iniciar construcción: " .. err)
end

-- Monitorear progreso
local prog = turtle.getBuildProgress()
print("Progreso: " .. prog.percentage .. "% (" .. prog.current .. "/" .. prog.total .. ")")

-- Control de ejecución
turtle.pauseBuild()
turtle.resumeBuild()
turtle.cancelBuild()
```

> **Cofres de Suministro y Combustible con Hologramas**:
> Si las opciones `turtle-require-materials` o `turtle-fuel-required` están habilitadas en el servidor, la tortuga coloca automáticamente cofres dedicados con hologramas flotantes (`"Coloca los bloques de construcción aquí"` y `"Coloca el combustible aquí"`), absorbiendo los materiales a medida que avanza.

---

### Mejora de Motor de Cantera Lateral (Quarry Engine Upgrade)

> [!IMPORTANT]
> **Exclusivo para la Tortuga:**
> El Motor de Cantera (Quarry Engine) **no** es un periférico de computadora y no funciona con atriles ni mesas de encantamiento normales. Funciona **únicamente** como accesorio lateral acoplado a la Tortuga Programable mediante la API `turtle.quarry`.

El **Motor de Cantera** (`BLAST_FURNACE` por defecto) se acopla directamente a la tortuga como una mejora de excavación móvil:

- **Requisito de Posicionamiento Lateral:** El bloque de la cantera debe colocarse de forma **lateral y adyacente** (directamente a la **izquierda** o a la **derecha** de la tortuga) antes de iniciar la operación.
- **Detección y Validación:** La tortuga valida la presencia del motor mediante `turtle.hasQuarryEngine()`.
- **Desplazamiento Físico en Bloque:** Una vez iniciada la excavación, el motor de la cantera se vincula a la tortuga y se desplaza físicamente junto a ella bloque a bloque y al rotar.
- **Colocación Automática de 2 Cofres con Hologramas:** Al arrancar la cantera, la tortuga genera:
  1. `📦 Almacenamiento de Bloques Minados`: Donde se almacenan todos los bloques y minerales extraídos.
  2. `⚡ Coloca combustible aquí`: Para recargar el combustible de la tortuga durante trabajos prolongados.
- **Pausa Automática por Almacenamiento Lleno:** Si el cofre de almacenamiento se llena por completo, la tortuga detiene inmediatamente la excavación (`"Paused: Mined blocks storage chest is full"`) para evitar pérdida de minerales. Al vaciar el cofre, se reanuda fácilmente mediante `turtle.resumeQuarry()` o desde la interfaz gráfica.
- **+20% de Consumo de Combustible:** Al tratarse de una maquinaria pesada acoplada, la tortuga consume un 20% más de combustible (multiplicador 1.20x) durante las labores de excavación.

```lua
-- 1. Validar que la mejora de motor esté colocada al lateral
if not turtle.hasQuarryEngine() then
  print("¡Debes colocar un Motor de Cantera a la izquierda o derecha de la tortuga!")
  return
end

-- 2. Iniciar excavación: turtle.quarry(ancho, largo, capaYObjetivo, [manejarLiquidos])
-- Excava un área de 16x16 hasta la capa Y=11, drenando agua y lava por defecto
local ok, err = turtle.quarry(16, 16, 11, true)
if not ok then
  print("No se pudo iniciar la cantera: " .. err)
  return
end
print("¡Excavación iniciada!")

-- 3. Consultar progreso y estado
local q = turtle.getQuarryStatus()
print("Estado: " .. q.status .. " (" .. q.message .. ")")
print("Progreso: " .. q.percentage .. "% (" .. q.blocksMined .. "/" .. q.totalBlocks .. ")")
print("Capa Y actual: " .. q.currentY .. " Objetivo: " .. q.targetY .. " Lado: " .. (q.side or "ninguno"))

-- 4. Controles de pausa, reanudación y cancelación
turtle.pauseQuarry()
turtle.resumeQuarry()
turtle.stopQuarry()
```

### Gestión de Tortugas, Propiedad y Controles de Detención

Las tortugas colocadas en el mundo recuerdan automáticamente el **UUID del jugador** que las colocó. Esta propiedad queda persistida en el `PersistentDataContainer` (PDC) del bloque y en el archivo `turtles.yml`, garantizando que nunca se pierda tras reinicios del servidor o descargas de chunks.

#### Protección y Anti-Griefing
- **Protección contra destrucción no autorizada:** Solo el jugador dueño o un administrador del servidor (`multiverseprogramming.admin`) pueden romper o desmontar las tortugas colocadas.
- **Protección de claims:** `dig`, `place` y el movimiento se comprueban contra WorldGuard, ProtectionStones y BentoBox para el dueño de la tortuga. Las tortugas sin dueño no pueden modificar terreno con claim.
- **Caída segura de inventario y disco:** Al desmontar la tortuga, se sueltan en el suelo los 16 ítems de su inventario, el disco insertado y el ítem de la propia tortuga.

#### Detener Tareas y Construcción (`/mvprog stop`)
Las tortugas pueden detenerse de inmediato a través de tres métodos:
1. **Comando de Chat (`/mvprog stop`):**
   - Ejecutar `/mvprog stop` muestra una lista de tus tortugas con coordenadas y estado en vivo (p. ej. `BUILDING (45%)`, `MINING (Y=32)`, `IDLE`) con botones interactivos `[STOP]` en el chat.
   - Ejecutar `/mvprog stop <id>` (p. ej. `/mvprog stop T-001`) cancela inmediatamente la construcción o excavación en esa tortuga específica. Los demás jugadores no pueden detener tus tortugas.
   - Ejecutar `/mvprog stop all` detiene todas las tareas activas de todas las tortugas que te pertenecen.
2. **Comando de Administrador (`multiverseprogramming.admin`):**
   - Los administradores que ejecuten `/mvprog stop` ven todas las tortugas del servidor indicando el nombre de cada dueño.
   - Los administradores pueden detener cualquier tortuga por su identificador (`/mvprog stop <id>`) sin importar de quién sea.
   - Los administradores pueden detener todas las tortugas del servidor a la vez con `/mvprog stop all`.
3. **Botón en la Interfaz Gráfica (GUI):**
   - Haz clic derecho sobre la tortuga para abrir su panel de control e inventario de 16 casillas.
   - **Casilla 4 (Yunque de Control de Tareas):** Clic izquierdo pausa o reanuda. **Clic derecho detiene y cancela inmediatamente** cualquier construcción o minería activa.

---

## 11. Portal Web, Nube Pastebin y Cuotas

Los jugadores pueden interactuar con el sistema web integrado ejecutando en el servidor:
```text
/mvprog web
```

### Características del Portal Web:
1. **Subida de Archivos Drag & Drop:** Arrastra y suelta directamente esquemas `.litematic` (Litematica) o `.nbt` (Vanilla Structure Blocks).
2. **Visor 3D y Desglose de Capas:** Visualiza en Three.js capa por capa con slider interactivo y corte 2D.
3. **Lista de Materiales Requeridos:** Muestra la lista exacta de bloques necesarios.
4. **Despacho Remoto a Tortugas:** Selecciona cualquier tortuga activa, ingresa las coordenadas relativas (`~ ~ ~` o `0, 0, 0` para su posición actual) y presiona **"⚡ INITIATE CONSTRUCTION"**.

### Pastebin Universal y Cuotas de Almacenamiento:
- **Descargar esquemática desde la nube:**  
  `/mvprog get <código|url>`  
  Descarga automáticamente esquemas subidos a Bytebin, GitHub o URLs públicas y los importa al servidor.
- **Consultar espacio y cuota de almacenamiento:**  
  `/mvprog quota`  
  Muestra el uso en MB y el límite restante asignado a tu cuenta.
- **Administrar esquemas cargados:**  
  `/mvprog bp list` — Lista todos los esquemas en memoria.  
  `/mvprog bp delete <id>` — Elimina un esquema de tu propiedad.