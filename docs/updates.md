# Actualizaciones en línea de jkanimetv

La app consulta cada seis horas el release más reciente de:

`https://github.com/gmacastil/jkanime/releases/latest`

Si el tag es mayor que la versión instalada y el release contiene un APK,
muestra un aviso en Android TV, descarga el archivo y abre el instalador del
sistema.

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
