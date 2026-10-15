# Configuración

## config.yml

El archivo de configuración se genera automáticamente la primera vez que arranca el plugin en `plugins/MultiverseProgramming/config.yml`.

```yaml
# ==============================================================================
# MultiverseProgramming Configuration
# ==============================================================================

# Tipos de bloques asignados a computadoras y periféricos
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

# Activadores de recetas de crafteo (habilitar/deshabilitar por máquina)
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

# Activadores de funcionalidad (habilitar/deshabilitar mecánicas por máquina)
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

# Integración con protección de regiones (WorldGuard & ProtectionStones)
worldguard-protection-check: true
protection-stones-require-owner: false

# Límites de ejecución y seguridad
execution-timeout-ms: 3000
advanced-execution-timeout-ms: 0
max-instructions-per-run: 1000000
drop-disks-on-break: true
prevent-spam-delay-ms: 500

# Parámetros de automatización de tortugas
turtle-fuel-required: false
turtle-fuel-per-action: 1
turtle-initial-fuel: 1000
turtle-max-fuel: 100000
turtle-build-delay-ticks: 1
turtle-require-materials: false

# Portal Web y Almacenamiento de Esquemáticas
web-portal-enabled: true
web-portal-host: "0.0.0.0"
web-portal-port: 8080
web-public-url: ""
blueprint-player-quota-mb: 15.0
```

### Tabla de Opciones de Configuración

| Clave | Tipo | Por defecto | Descripción |
|---|---|---|---|
| `computer-block` | `string` | `LECTERN` | Material usado como bloque de computadora estándar |
| `advanced-computer-block` | `string` | `ENCHANTING_TABLE` | Material usado como bloque de computadora avanzada |
| `quarry-block` | `string` | `BLAST_FURNACE` | Material usado como Mejora de Motor de Cantera (funciona exclusivamente con la tortuga) |
| `enable-recipe-*` | `boolean` | `true` | Interruptor independiente para activar/desactivar la receta de crafteo de cada máquina |
| `enable-*` | `boolean` | `true` | Interruptor independiente para activar/desactivar la funcionalidad de cada máquina |
| `worldguard-protection-check` | `boolean` | `true` | Evita que las tortugas y canteras construyan, minen o se muevan en regiones de WorldGuard sin permisos |
| `protection-stones-require-owner` | `boolean` | `false` | Si es `true`, exige que el dueño de la tortuga sea propietario (no solo miembro) en ProtectionStones |
| `turtle-fuel-required` | `boolean` | `false` | Si es `true`, genera un cofre de combustible con holograma `"Place fuel here"` y consume combustible |
| `turtle-require-materials` | `boolean` | `false` | Si es `true`, genera un cofre de materiales con holograma `"Place construction blocks here"` |
| `execution-timeout-ms` | `long` | `3000` | Tiempo máximo de ejecución para computadoras estándar antes de interrumpir el hilo |
| `advanced-execution-timeout-ms` | `long` | `0` | Tiempo máximo para computadoras avanzadas (`0` = sin límite de tiempo) |
| `max-instructions-per-run` | `long` | `1000000` | Límite seguro de instrucciones de CPU por bloque de tiempo para evitar congelamientos |
| `blueprint-player-quota-mb` | `double` | `15.0` | Cuota máxima de almacenamiento en disco para esquemáticas por jugador (en MB) |

---

## Comandos

Todos los comandos usan el prefijo `/mvprog`.

### Comandos de Jugador
| Comando | Permiso | Descripción |
|---|---|---|
| `/mvprog help` | `multiverseprogramming.use` | Muestra la lista de comandos disponibles |
| `/mvprog web` | `multiverseprogramming.use` | Muestra el enlace al portal web para subir esquemas `.litematic` y `.nbt` |
| `/mvprog get <código\|url>` | `multiverseprogramming.use` | Descarga esquemas desde la nube (Bytebin / GitHub) consumiendo cuota personal |
| `/mvprog quota` | `multiverseprogramming.use` | Consulta el almacenamiento utilizado y la cuota disponible |
| `/mvprog bp list` | `multiverseprogramming.use` | Lista todos los esquemas guardados en el servidor |
| `/mvprog bp quota` | `multiverseprogramming.use` | Muestra el estado de almacenamiento |
| `/mvprog bp delete <id>` | `multiverseprogramming.use` | Elimina un esquema propio para liberar cuota de disco |
| `/mvprog stop [id\|all]` | `multiverseprogramming.use` | Muestra tus tortugas con botones interactivos `[STOP]`, detiene una tortuga propia por ID o detiene todas tus tortugas activas (`all`) |
| `/mvprog turtle [list\|stop]` | `multiverseprogramming.use` | Lista tus tortugas o detiene sus operaciones de construcción y minería en curso |

### Comandos de Administrador
| Comando | Permiso | Descripción |
|---|---|---|
| `/mvprog build <bp\|código> <x> <y> <z> [tortuga] [clear] [orientación]` | `multiverseprogramming.admin` | Ordena a una tortuga construir una esquemática en coordenadas relativas a la tortuga (`~ ~ ~` o `0 0 0` para su posición actual) con rotación (`NORTH`, `EAST`, `SOUTH`, `WEST`, `0`, `90`, `180`, `270`) y despeje de obstáculos |
| `/mvprog stop [id\|all]` | `multiverseprogramming.admin` | Lista todas las tortugas del servidor con el nombre del dueño y estado, detiene cualquier tortuga por ID independientemente del dueño o detiene todas las tortugas del servidor (`all`) |
| `/mvprog quota <jugador>` | `multiverseprogramming.admin` | Consulta el almacenamiento y cuota de un jugador específico |
| `/mvprog getbypass <código\|url>` | `multiverseprogramming.admin` | Descarga esquemáticas omitiendo las cuotas de almacenamiento de jugadores |
| `/mvprog bp clean [días]` | `multiverseprogramming.admin` | Purga esquemáticas no ancladas más antiguas que los días indicados |
| `/mvprog give <objeto>` | `multiverseprogramming.admin` | Otorga cualquier ítem custom del plugin (soporta nombres en inglés y español) |
| `/mvprog reload` | `multiverseprogramming.admin` | Recarga `config.yml` y recetas dinámicamente sin reiniciar el servidor |

---

## Permisos

El plugin registra **tres** nodos de permiso. Si usas un plugin de permisos (LuckPerms, PermissionsEx, etc.), concede estos nodos a tus rangos.

| Nodo de permiso | Por defecto | Qué habilita |
|---|---|---|
| `multiverseprogramming.use` | `true` (todos los jugadores) | Acceso base: usar **computadoras** (abrir la interfaz, validar y ejecutar disquetes) y el comando `/mvprog` con sus subcomandos de jugador (`help`, `web`, `get`, `quota`, `bp ...`, `stop`, `turtle`), descargar esquemáticas y consultar cuotas de almacenamiento. |
| `multiverseprogramming.turtle` | `true` (todos los jugadores) | Acceso a **tortugas**: colocar tortugas, abrir su panel de control, ejecutar scripts Lua en ellas, repostar combustible, usar el terminal de suministros y gestionar la lista de acceso de una tortuga de tu propiedad. |
| `multiverseprogramming.admin` | `op` (operadores del servidor) | Acceso administrativo completo: `/mvprog build`, `give`, `reload`, `getbypass`, `bp clean`; ver y gestionar cuotas y esquemáticas de otros jugadores; detener o romper **cualquier** tortuga independientemente de su dueño; omitir la protección de regiones/reclamos. |

### Herencia de permisos

Los hijos están declarados en `plugin.yml`, así que conceder un nodo padre también concede sus hijos:

- `multiverseprogramming.admin` → concede `multiverseprogramming.use` **y** `multiverseprogramming.turtle`.
- `multiverseprogramming.use` → concede `multiverseprogramming.turtle`.

Basta con conceder el nodo superior a un rango; no hace falta listar cada nodo por separado.

### Propiedad por tortuga

El nodo `multiverseprogramming.turtle` decide si un jugador puede interactuar con tortugas **en absoluto**. Además, cada tortuga aplica su propia propiedad:

- El **dueño** (quien la colocó) siempre puede abrirla, operarla y romperla.
- El dueño puede autorizar a otros jugadores desde el botón **Access Control** del panel; los autorizados pueden abrir y operar **esa** tortuga concreta.
- **Romper / desmontar** una tortuga queda reservado a su dueño o a un administrador — los operadores autorizados no pueden desmontarla. Una tortuga sin dueño registrado solo puede eliminarla un administrador.
- Los administradores (`multiverseprogramming.admin`) omiten todo lo anterior.

> 💡 Si retiras `multiverseprogramming.turtle` a un jugador, el cambio es inmediato: ya no podrá colocar tortugas ni abrir ningún panel, ni siquiera de las tortugas que posea.

---

## Seguridad y Protección de Regiones

El plugin se integra automáticamente con **WorldGuard**, **ProtectionStones** y **CoreProtect**:
1. **Verificación de permisos de región**: Los blueprints y las canteras validan todo su volumen antes de empezar, y cada `dig`, `place` y movimiento de la tortuga comprueba su bloque destino. Si el dueño no tiene permisos de construcción en la región, la acción se cancela. También se comprueban las islas de BentoBox.
2. **Registro de bloques en CoreProtect**: Todas las rupturas de bloques provocadas por tortugas y canteras quedan registradas en el historial de CoreProtect con sus metadatos y coordenadas.
3. **Protección contra duplicación**: Se bloquea la inserción de libros escritos con exploits NBT o contenedores anidados en marcos de ítems, vasijas y atriles para garantizar la estabilidad económica del servidor.