# Actualizaciones en línea de JK TV y JK Mobile

Cada aplicación consulta la lista de releases de:

`https://api.github.com/repos/gmacastil/jkanime/releases?per_page=30`

La TV acepta únicamente assets `jkanime-tv-*.apk`; el móvil acepta
`jkanime-mobile-*.apk`. El tag se compara por versión semántica, ignorando el
sufijo `-tv` o `-mobile`.

En TV existe una comprobación automática con caché de seis horas. El menú de
ambas aplicaciones contiene **Actualizar**: en móvil ejecuta la comprobación
bajo demanda y en TV fuerza una nueva consulta aunque la comprobación automática
ya haya ocurrido. Esto permite recuperar una actualización aunque el diálogo
inicial haya perdido el foco o se haya cerrado.

Si hay una versión nueva, la app descarga la APK y abre el instalador del
sistema Android. La instalación siempre requiere confirmación del usuario.

## Errores de la comprobación (1.5.6 / 1.5.7)

En 1.5.6 y 1.5.7 el menú móvil mostraba **siempre** «No se pudo comprobar si
hay actualizaciones». La causa no era la red: el disparador era un booleano
usado como clave de `LaunchedEffect`, y el propio efecto lo ponía a `false`
como primera instrucción, lo que **cancelaba su propia comprobación**. El
`CancellationException` resultante lo capturaba `runCatching(...).onFailure`,
que lo interpretaba como error de red.

Reglas que quedan en el código a partir de 1.5.8:

- El disparador es un **contador** (`updateCheckRequests++`), nunca un booleano
  que el efecto reinicie.
- `CancellationException` **no** se trata como error: se vuelve a lanzar.
- `UpdateManager.check` propaga los fallos de red/JSON en lugar de devolver
  `null`, para poder distinguir «no hay versión nueva» de «no se pudo
  consultar». La marca de caché de 6 h solo se guarda si la consulta tuvo
  éxito.
- El menú **Actualizar** de la TV pasa `force = true`: antes respetaba la caché
  de la comprobación del arranque y podía responder «ya tienes la última
  versión» sin consultar.

Quien tenga instalada la 1.5.6 o la 1.5.7 **no puede actualizar desde la app**:
hay que instalar una vez `jkanime-tv-1.5.8.apk` / `jkanime-mobile-1.5.8.apk`
desde la página de releases.

## Crear una versión

La versión se define en `gradle.properties`:

```properties
VERSION_NAME=1.2
```

El tag debe coincidir con ese valor y usar el prefijo `v`:

```bash
git tag v1.2
git push origin v1.2
```

También se puede hacer desde el script local:

```bash
./tools/build-apk.sh --version 0.1.2 --release --publish
```

Esto construye el APK local y publica `v0.1.2`; el workflow genera después el
APK release firmado y crea el GitHub Release. Para solo generar un APK local
sin publicar:

```bash
./tools/build-apk.sh --version 0.1.2 --release
```

El workflow `.github/workflows/release.yml` construye el APK release y crea el
GitHub Release automáticamente.

## Firma obligatoria

Android solo permite actualizar una app si el APK nuevo está firmado con la
misma clave. Configurar en GitHub Actions estos secretos:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `JK_SUPABASE_URL`
- `JK_SUPABASE_ANON_KEY` (publishable, nunca `sb_secret_...`)

Para generar una clave permanente una sola vez (PKCS12, el formato por
defecto: exige que la contraseña de la clave sea **la misma** que la del
almacén, y el alias queda como se indique aquí — el actual es
`jkanimetv_release`):

```bash
keytool -genkeypair -v \
  -keystore jkanimetv-release.keystore \
  -alias jkanimetv_release \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '<contraseña>' -keypass '<misma contraseña>'
base64 -w0 jkanimetv-release.keystore
```

Si el alias o la contraseña no coinciden con los secretos, el workflow falla
en `packageRelease` con `KeytoolException: Failed to read key ...`.

El `versionCode` de los builds (local y CI) es `100 + nº de commits`, para
que siempre crezca y supere al ya instalado en cada TV.

La primera instalación que vaya a recibir actualizaciones debe ser el APK
firmado con esta clave. Si la TV ya tiene instalada una versión debug firmada
con otra clave, será necesario desinstalarla una vez e instalar el primer APK
release firmado; después las actualizaciones serán en línea.

En Android TV también puede ser necesario permitir temporalmente que
`jkanimetv` instale aplicaciones desconocidas.
