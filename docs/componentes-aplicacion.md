# Componentes de la aplicación

Referencia para especificar cambios de UI y navegación de forma precisa.

## Pantalla principal

| Componente | Descripción |
|---|---|
| `HomeScreen` | Pantalla principal de la aplicación. |
| `Header` | Barra superior con menú, marca y buscador. |
| `SearchField` | Campo de búsqueda de anime. |
| `SideDrawer` | Cajón lateral izquierdo principal. |
| `AvatarDrawerHeader` | Avatar, nombre del perfil y estado de sincronización. |
| `AvatarDrawerSection` | Título visual de una sección del cajón. |
| `DrawerItem` | Fila individual de una opción del menú. |
| `AvatarDrawerMain` | Opciones internas de configuración y sincronización. |
| `PosterCard` | Tarjeta visual de un anime. |
| `CardsGrid` | Grilla de animes recientes. |
| `SearchGrid` | Grilla de resultados de búsqueda. |
| `FavoritesGrid` | Grilla de favoritos. |
| `SectionHeader` | Encabezado de una sección de contenido. |

## Estructura del cajón izquierdo

```text
SideDrawer
├── AvatarDrawerHeader
│   ├── ProfileAvatar
│   ├── Nombre del perfil
│   └── Estado de sincronización
├── Sección Anime
│   ├── Recent
│   ├── Browse
│   └── Favorites
└── Config
    ├── Sync now
    ├── Generate pairing code
    ├── Set linking key
    ├── Unlink
    ├── Switch profile
    └── Language
```

## Configuración y sincronización

| Elemento | Descripción |
|---|---|
| `Sync now` | Ejecuta una sincronización manual. |
| `Generate pairing code` | Genera un código temporal para vincular otro dispositivo. |
| `Set linking key` | Define o cambia la clave permanente de vinculación. |
| `Unlink` | Desvincula el perfil del espacio de sincronización. |
| `SyncKeypad` | Teclado para introducir códigos o claves. |
| `AvatarMenu` | Submenú auxiliar para formularios de pairing y clave. |

## Perfiles e idioma

| Componente | Descripción |
|---|---|
| `ProfileGateScreen` | Pantalla inicial de selección o creación de perfil. |
| `ProfileTile` | Tarjeta de un perfil existente. |
| `NewProfileTile` | Tarjeta para crear un perfil nuevo. |
| `ProfileAvatar` | Avatar visual del perfil. |
| `LanguagePickerDialog` | Diálogo de selección de idioma. |
| `LanguageOptions` | Lista de idiomas disponibles. |
| `LanguageRow` | Fila individual de idioma. |

## Anime y navegación

| Pantalla | Componente | Función |
|---|---|---|
| Home | `CardsGrid` | Muestra animes recientes. |
| Home | `SearchGrid` | Muestra resultados del buscador. |
| Home | `FavoritesGrid` | Muestra favoritos pendientes y al día. |
| Browse | `DirectorioScreen` | Directorio con filtros y paginación. |
| Browse | `FiltersPanel` | Panel de filtros del directorio. |
| Browse | `ResultsGrid` | Resultados del directorio. |
| Browse | `PaginationFooter` | Navegación entre páginas. |
| Detalle | `DetailScreen` | Información del anime y capítulos. |
| Detalle | `LeftPanel` | Póster y metadatos del anime. |
| Detalle | `SortButton` | Orden de capítulos. |
| Detalle | `FavoriteStar` | Estado de favorito. |

## Reproductor

| Componente | Descripción |
|---|---|
| `PlayerScreen` | Pantalla completa de reproducción. |
| `NativePlayer` | Reproductor nativo mediante Media3/ExoPlayer. |
| `WebPlayer` | Reproductor basado en WebView. |
| `Overlay` | Controles superpuestos del reproductor. |
| `SeekBar` | Barra de progreso y navegación temporal. |

## Ejemplos para solicitar cambios

```text
En HomeScreen, dentro de SideDrawer, mueve Config al fondo sin mover Anime.
```

```text
En AvatarDrawerMain, reduce el alto de los DrawerItem de Sync now y Unlink.
```

```text
En ProfileGateScreen, cambia el estilo de ProfileTile, pero no modifiques NewProfileTile.
```

```text
En PlayerScreen, modifica únicamente Overlay y conserva el comportamiento de NativePlayer.
```
