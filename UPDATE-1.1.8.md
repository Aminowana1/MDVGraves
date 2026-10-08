# MDVGraves 1.1.8

Correcciones sobre el código del ZIP proporcionado. Requiere Paper/Purpur 1.21.6 y Java 21.

## Salir desde la pantalla de muerte

La muerte de un jugador real se registra en SQLite sin guardar una copia de su inventario anterior. Si sale sin pulsar Reaparecer, la reconexión completa el respawn en `logout-body.death.lobby-world`, por defecto `world5`, con vida y alimentación normales. Si continúa muerto, primero se solicita un respawn real para que pueda usar el login. La marca persiste hasta completar la autenticación y el período de protección frente a tareas tardías de nLogin.

El respawn manual normal limpia la marca. Los callbacks de una conexión anterior no pueden resolver una conexión nueva. Los objetos y la experiencia de una muerte real siguen la resolución de Minecraft y de los listeners de muerte: no se restaura un snapshot ni se genera una segunda bolsa.

## Bolsas y fluidos

Las bolsas abiertas y cerradas quedan protegidas frente a flujo de agua/lava, cubos vertidos directamente, dispensadores, fuego, formación/extensión de bloques y destrucción indirecta por física de Paper. Se impide la destrucción y el drop de la cabeza decorativa; no se borra el contenido persistente.

`settings.protect-from-fluids` se conserva por compatibilidad, pero la inmunidad ahora se aplica incluso si el valor antiguo era `false`. La rotura manual, el acceso privado y las explosiones mantienen sus reglas configuradas.

## Fruta y graveback

Se acepta una única operación pendiente por jugador y se mantiene una separación mínima de 750 ms entre viajes, también en la ruta administrativa `/graveback <jugador>`. El comando espera al siguiente tick para dejar terminar la interacción de MMOItems, reserva una fruta si MMOItems no la descontó y carga asíncronamente los chunks del destino y su entorno sin generar terreno nuevo. El teletransporte se ejecuta una sola vez, con el destino ya cargado y retenido mediante tickets, y se verifica que el jugador llegó al lugar esperado.

La operación devuelve una fruta al fallar, agotarse el plazo de carga, desconectarse o desactivarse el plugin. Si muere durante la carga, la devolución se incorpora a los drops de esa muerte, o al inventario si corresponde keepInventory. Durante el tick previo a la reserva se bloquean drops, recogida de objetos, cambios de mano y movimientos de inventario para no confundir un objeto movido con uno consumido. Los clics adicionales de fruta se bloquean mientras hay un viaje pendiente y durante el breve bloqueo de uso posterior. El cooldown de graveback se aplica solo tras un viaje exitoso.

Para que el plugin controle el consumo y la devolución, el comando del MMOItem debe ser `mdvgraves deathfruit %player%` desde consola. Se mantiene la recomendación existente de `disable-right-click-consume: true` en MMOItems. Una acción que ejecute `/graveback <jugador>` utiliza el nuevo teletransporte y su protección frente a ráfagas, pero deja el consumo del objeto bajo el control del MMOItem.

## Instalación y comprobación en el servidor

Compilación `clean package` completada con Java 21. Las 45 pruebas automatizadas pasan: 9 de reconexión/respawn, 7 de protección ambiental, 19 de graveback/fruta y 10 regresiones previas de ubicación y cuerpos offline. Sin errores, fallos ni pruebas omitidas.

Con el servidor apagado, reemplaza el JAR anterior por `MDVGraves-1.1.8.jar`. Conserva `config.yml`, `graves.db` y el resto de la carpeta del plugin. No hacen falta nuevas opciones ni borrar las bolsas existentes.

Comprueba estos escenarios en la combinación real de plugins del servidor:

1. Morir, salir desde la pantalla de muerte, reconectar y autenticar: lobby y vida normal. Repetir saliendo durante el período de protección de login y después de un reinicio.
2. Pulsar Reaparecer normalmente y reconectar: conservar el comportamiento habitual del servidor.
3. Verter agua/lava sobre una bolsa cerrada y abierta, y usar un dispensador: la cabeza permanece y el contenido se recupera una sola vez.
4. Usar la fruta en Java y Bedrock hacia una bolsa lejana con chunks descargados. Repetir clics y verificar que sólo se realiza un viaje y se descuenta una unidad.
5. Usar la fruta sin bolsa o con el teletransporte cancelado: no perderla. Morir durante una carga pendiente: la fruta vuelve a los drops/bolsa o al inventario conservado.

Las pruebas automatizadas simulan eventos y tareas y verifican persistencia SQLite. No incluyen un cliente Bedrock real ni la combinación instalada de nLogin, Geyser, Floodgate y MMOItems. El mensaje reportado fue aproximado: `move wrongly` / `move faster`, sin el log literal de expulsión. Paper documenta `moved wrongly` y `moved too quickly` como comprobaciones que registran un aviso y rechazan un movimiento; esos avisos por sí solos no identifican la causa del kick. Se corrigieron las rutas de carga síncrona y de solicitudes repetidas detectadas en el plugin. [Referencia de movimiento de Paper](https://docs.papermc.io/paper/reference/spigot-configuration/).

Referencias de API: [teletransporte y carga de chunks de Paper](https://docs.papermc.io/paper/dev/entity-teleport/), [API World 1.21.6](https://jd.papermc.io/paper/1.21.6/org/bukkit/World.html) y [BlockDestroyEvent](https://jd.papermc.io/paper/1.21.6/com/destroystokyo/paper/event/block/BlockDestroyEvent.html).
