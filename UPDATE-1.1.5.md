# MDVGraves 1.1.5

Dos correcciones sobre el código 1.1.4 proporcionado:

- El cuerpo desconectado es inmune al daño SUFFOCATION por defecto. Los jugadores y otras entidades no reciben esta inmunidad. El combate, fuego, ahogamiento y la opción existente de daño por caída mantienen su comportamiento.
- El objeto de menú de MDVSocial se reconoce por la marca `mdvsocial:social_menu_item` de tipo BYTE y valor 1, igual que en MDVSocial. Se conserva en el inventario protegido del dueño y se excluye del contenido de la bolsa del cuerpo, tanto en el inventario como en la mano secundaria. Los libros normales no se filtran por su nombre o material. No requiere instalar otra versión de MDVSocial.

## Configuración

Dentro de la sección existente `logout-body.entity`, junto a `fall-damage`, puedes añadir:

```yaml
    suffocation-damage: false
```

No dupliques las secciones `logout-body` ni `entity`. Si omites la opción, se aplica `false` igualmente. El filtro del libro es automático: deja `protected-items.persistent-data-keys` como lo tienes.

## Instalación

Detén el servidor normalmente, reemplaza el JAR anterior por MDVGraves-1.1.5.jar y vuelve a iniciarlo. Conserva tu config.yml y las bases de datos. No requiere migración de bolsas ni modifica el contenido de bolsas ya creadas; los libros que ya quedaron dentro no se retiran retroactivamente.

La compilación utiliza Java 21 y Paper API 1.21.6. Se incluyen pruebas automatizadas del filtro, de su aplicación al inventario y mano secundaria, y de la inmunidad limitada al sofoco. La comprobación dentro de Minecraft sigue pendiente.
