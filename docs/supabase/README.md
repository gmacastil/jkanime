# Sincronización multi-dispositivo (Supabase)

Favoritos y progreso compartidos entre todas tus TV y tu celular.

## Por qué Supabase y no Auth / Sheets / Git

- **Sin Supabase Auth**: la app corre en Android TV, donde teclear email +
  password con el D-pad es inviable y Play Services no está garantizado (el
  emulador TV no lo trae). La identidad es un **código de 8 dígitos + token**;
  las RPC de Postgres validan ambos del lado del servidor.
- **No Sheets**: exigiría OAuth con device-code flow y no tiene transacciones
  ni upsert real.
- **No Git**: cada favorito sería un commit, el progreso se guarda cada ~5 s
  (miles de commits) y haría falta un PAT con scope `repo` dentro del APK,
  trivialmente extraíble de un sideload.

## Puesta en marcha (una sola vez)

1. Crea un proyecto en <https://supabase.com> (free tier sobra: el estado
   completo de un usuario son unos pocos KB).
2. Abre el **SQL Editor** y ejecuta `docs/supabase/schema.sql` completo.
3. Copia del proyecto: **Project URL** y la **publishable key**
   (Settings → API keys). Ojo: la que empieza por `sb_publishable_`, **no** la
   `sb_secret_` (esa salta RLS y nunca debe viajar dentro del APK).
4. Añádelas a `local.properties` (ignorado por git) — también se aceptan en
   `gradle.properties` o `~/.gradle/gradle.properties`:

   ```properties
   JK_SUPABASE_URL=https://TU-PROYECTO.supabase.co
   JK_SUPABASE_ANON_KEY=sb_publishable_...
   ```

5. Recompila:

   ```bash
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug
   ```

> La `publishable key` **no es un secreto** (es pública por diseño en Supabase;
> lo que protege los datos son las RPC `SECURITY DEFINER` + el token). El token
> del sync space sí es secreto y nunca se teclea ni se muestra: lo genera el
> servidor y viaja solo dentro del código de emparejamiento.

## Vincular dispositivos

1. En el primer dispositivo: **⇄ Sync → Vincular otro dispositivo**. Genera un
   **código de emparejamiento** de 8 dígitos, de un solo uso y válido 15 min.
2. En los demás: **⇄ Sync → Ya tengo un código**, escribe ese código con el
   teclado en pantalla. El servidor devuelve el token del espacio y el
   dispositivo queda vinculado.

> El token del espacio **nunca se teclea**: sería un hex de 64 caracteres,
> inviable con un D-pad. El código de emparejamiento es lo único que se
> escribe, y caduca solo.

## Recuperar sin el dispositivo anterior

Al crear un espacio, la app pide al usuario definir una **clave de vinculación
de 10 caracteres alfanuméricos**. Usa un alfabeto sin caracteres ambiguos
(`I`, `O`, `0` y `1`). Debe guardarse fuera de la TV (por ejemplo, en un gestor de
contraseñas o un correo propio). No se muestra de nuevo automáticamente.

En un dispositivo nuevo se elige **Tengo una clave de recuperación** y se
introduce con el teclado alfanumérico. El servidor valida el hash bcrypt de la
clave y entrega un token nuevo para ese dispositivo; no hace falta tener la
TV anterior. Desde el menú del avatar se puede generar/rotar la clave si se
dispone de un dispositivo ya vinculado.

La clave de vinculación no es el código del espacio ni el código temporal de
8 dígitos. Es reutilizable hasta que se cambie; al cambiarla, la anterior deja
de funcionar. El servidor nunca almacena la clave plana.

## Cómo funciona el merge

- **Pull → merge local → push.** Primero se trae lo remoto y se aplica a Room
  (gana el `updated_at` más nuevo), y solo después se empuja el estado ya
  mergeado. Al revés, un dispositivo con datos viejos pisaría cambios ajenos
  antes de haberlos visto.
- **Borrado lógico** (`deleted_at`): sin tombstone, un dispositivo que no vio
  el borrado resucitaría el favorito.
- **Guard del lado servidor**: el upsert lleva
  `where excluded.updated_at >= tabla.updated_at`, así que un push
  concurrente nunca pisa algo más reciente, aunque el cliente se equivoque.

## Cuándo sincroniza

| Momento | Qué hace |
|---|---|
| Al abrir la app | pull + push (best-effort, no bloquea la UI) |
| Cada 6 h | `FavoritesSyncWorker`: refresca el catálogo y luego pull + push |
| Botón "Sincronizar ahora" | pull + push manual |

## Tablas

| Tabla | Contenido |
|---|---|
| `sync_spaces` | código + token de cada espacio |
| `favorites` | favoritos por espacio, con `updated_at` y `deleted_at` |
| `progress` | posición por episodio, con `updated_at` |

Las tres tienen RLS activo **sin políticas**: nadie entra directo, solo las
funciones `SECURITY DEFINER` (`sync_create`, `sync_pull`, `sync_push`), que
exigen código + token válidos.
