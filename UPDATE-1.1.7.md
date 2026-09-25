# MDVGraves 1.1.7 — ubicación de bolsas en cuevas

Revisión sobre el ZIP proporcionado, que contiene la versión 1.1.6.

La versión recibida ya busca hacia abajo y no consulta la altura máxima del terreno. Sin embargo, sumaba un bloque a la altura de muerte y otro al iniciar la búsqueda: comenzaba dos bloques por encima de los pies. Con un techo bajo podía seleccionar la parte superior del techo o una superficie vecina elevada.

Ahora se inicia en la altura de los pies, redondeada hacia arriba solo para alturas fraccionarias como caminos y losas, y se busca hacia abajo. No se añaden los dos bloques extra. Tanto la muerte de jugadores como la muerte de cuerpos desconectados usan esta misma búsqueda.

Se mantiene el descenso al suelo en muertes aéreas y la búsqueda lateral mediante settings.placement-search-radius. Si una columna queda bloqueada, se prueba otra dentro del radio; no se atraviesa el suelo para buscar una cueva inferior. No se sustituyen bloques sólidos ni otras cabezas de bolsas.

La revisión del flujo confirma que, si no hay ubicación válida, la muerte normal conserva los drops vanilla; en la muerte offline el flujo de seguridad deja pendiente restaurar el inventario. Los drops normales se vacían después de insertar la bolsa en SQLite. Esto es una revisión de esas rutas de código, no una simulación de todos los errores de disco o de interacciones con otros plugins.

Se mantienen los cambios existentes de sofoco y exclusión del libro de MDVSocial. No hace falta añadir nada a config.yml. Reemplaza el JAR con el servidor apagado y conserva configuración y bases de datos. Las bolsas ya creadas no se reubican.

Pruebas añadidas: cueva con techo bajo y superficie superior; altura fraccionaria bajo techo; suelo parcial; muerte aérea con dos pisos; columna bloqueada y alternativa lateral; ausencia de suelo; cuevas con Y negativa y límites del mundo. Las pruebas utilizan una representación simulada del terreno: la comprobación visual y las interacciones con otros plugins requieren un servidor de pruebas.
