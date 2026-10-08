# MDVGraves 1.1.9

Corrige la pérdida de MMOItems persistentes al salir desde la pantalla de muerte y reconectar. Requiere Paper/Purpur 1.21.6 y Java 21.

## Causa y corrección

MMOItems retira los objetos con `disable-death-drop: true` de los drops y los devuelve después del respawn. En la versión examinada, esa devolución diferida conserva una referencia al jugador de la conexión anterior. Si el jugador se desconecta antes de reaparecer, la reconexión puede completar el respawn sin recibir esos objetos.

Ahora MDVGraves detecta el marcador NBT `MMOITEMS_DISABLE_DEATH_DROP=true` al comienzo del evento de muerte, retira esos objetos de los drops y los incluye en `PlayerDeathEvent#getItemsToKeep()`. Paper los conserva en el inventario y los guarda con los datos del jugador al desconectarse. MMOItems ya no los encola para una segunda devolución.

Se conservan las pilas completas, incluyendo cantidades, atributos y demás metadatos. La retención respeta el inventario, la armadura y la mano secundaria. Las entradas ya conservadas por otro listener se tienen en cuenta una vez por pila para evitar duplicarlas. Con `keepInventory` se mantiene el comportamiento normal de conservar todo.

Si la fruta reservada para un viaje pendiente también es persistente y el jugador muere durante la carga, su devolución se incluye en la retención nativa. Cada devolución corresponde a una unidad adicional; no reemplaza otra pila idéntica que ya estuviera conservada.

El regreso al lobby con vida normal, la protección de las bolsas frente a fluidos y la corrección de graveback/fruta siguen incluidos. No se restaura el inventario que debía perderse ni se crea otra bolsa.

## Instalación

Con el servidor apagado, reemplaza el JAR anterior por `MDVGraves-1.1.9.jar` y vuelve a iniciar el servidor. Conserva `config.yml`, las bases de datos y el resto de la carpeta del plugin. No hay nuevas opciones ni migraciones de datos.

La corrección protege las próximas muertes. Los objetos que ya se perdieron con la versión anterior requieren una copia de seguridad o reposición administrativa.

## Validación

Compilación `clean package` completada con Java 21. Las 60 pruebas pasan, sin fallos, errores ni omisiones. Incluyen 15 casos nuevos sobre el marcador NBT, cantidades y atributos, pilas repetidas, retención previa, salida desde la pantalla de muerte, reconexión, respawn manual, nLogin, `keepInventory` y la devolución de una fruta persistente. Las 45 regresiones anteriores de bolsas, graveback, ubicación y cuerpos offline también pasan.

Las pruebas modelan el ciclo de eventos y la conservación de los datos del jugador; no ejecutan un servidor real con la combinación instalada de MMOItems y nLogin.

Para comprobarlo en el servidor, lleva MMOItems protegidos en el inventario, armadura y mano secundaria junto a objetos que sí deban perderse. Muere y sal sin pulsar Reaparecer; al reconectar y autenticar deben permanecer sólo los objetos protegidos, y el resto debe estar una única vez en la bolsa. Repite pulsando Reaparecer y con dos pilas idénticas protegidas.

La API exige retirar de los drops los objetos añadidos a la lista de retención: [PlayerDeathEvent de Paper 1.21.6](https://jd.papermc.io/paper/1.21.6/org/bukkit/event/entity/PlayerDeathEvent.html).
