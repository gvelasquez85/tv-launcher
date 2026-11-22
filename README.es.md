# tv-launcher

🇬🇧 [Read in English](README.md)

Launcher propio y descargador de torrents **dentro de la TV** para **Android TV**, que se instala desde tu computador en pocos minutos.
Desarrollado y probado en una Sony BRAVIA (Android 9); el instalador es genérico, pero mira [Compatibilidad](#compatibilidad).

- **Launcher** (`app.tvlauncher`): apps y entradas HDMI como botones, lo popular de cada plataforma con fondo, sinopsis, reparto y calificación (TMDB), botones del control reasignables.
- **Descargas** (`app.tvtorrent`): descarga con un motor torrent propio **en la misma TV**, guarda en un USB, reproduce con Kodi/VLC/tu reproductor y sirve un **administrador web** que abres desde cualquier computador o celular de tu red (descargas, botones del control, ajustes).

> Úsalo solo con contenido que tengas derecho a descargar y ver.

## Inicio rápido

**1. Activa la depuración por red en la TV.**
Ajustes → Preferencias del dispositivo → Acerca de → toca *Compilación* siete veces → atrás → Opciones de desarrollador → activa *Depuración USB* / *por red* (el nombre varía según la marca). Anota la IP de la TV (Ajustes → Red).

**2. Ejecuta el instalador en tu computador** (Windows, macOS o Linux; solo requiere Python 3.8+):

```bash
git clone https://github.com/gvelasquez85/tv-launcher.git
cd tv-launcher
python3 install.py            # en Windows: py install.py
```

El asistente:
1. busca ADB (o descarga las platform-tools oficiales de Google en `./.tools`) y se conecta a tu TV (puede escanear tu red);
2. obtiene los APK (de `./dist`, la última Release de GitHub o compilándolos);
3. pide una [clave API de TMDB](https://www.themoviedb.org/settings/api) gratuita, tu país y tu idioma;
4. instala las dos apps, activa el servicio de botones del control y las abre en la TV;
5. opcionalmente deshabilita apps de telemetría/promoción conocidas (reversible).

Opciones útiles: `--tv 192.168.1.50`, `--tmdb-key CLAVE --region CO --lang es-CO -y` (sin preguntas), `--build` (compilar), `--dry-run`.
Otros comandos: `status`, `debloat`, `revert`, `uninstall`.

**3. Abre el administrador.** La app *Descargas* de la TV muestra la dirección (`http://<IP_TV>:8800`), el usuario `admin` y una clave; fija la tuya en *Ajustes*.

## Compatibilidad

| | |
|---|---|
| Android | 9 (API 28) o superior |
| CPU | ARM 32/64 bits (el motor torrent solo incluye librerías ARM) |
| Probado | Sony BRAVIA 4K GB (Android 9). Otras marcas deberían funcionar pero no están probadas: abre un issue. |
| Computador | Windows, macOS, Linux, en la misma red que la TV |

Depende de la TV, no del proyecto: el launcher de Google conserva la tecla HOME (abre este desde Apps → "Inicio" o asígnale un botón del control); en algunas marcas la reasignación de botones por accesibilidad puede estar restringida; y la llamada para apagar la pantalla del *Modo descarga* (`GLOBAL_ACTION_LOCK_SCREEN`) puede comportarse distinto en otro firmware.

## Compilar desde el código

Requiere JDK 17 y el SDK de Android.

```bash
# local.properties (nunca se sube):
#   sdk.dir=/ruta/al/android-sdk
#   tmdb.key=...  region=US  lang=en-US     # valores por defecto opcionales; normalmente se fijan en la TV
./gradlew :app:assembleRelease :torrent:assembleRelease
```

Los dos APK deben firmarse con la **misma clave** (hablan por un ContentProvider protegido con permiso de firma). Sin configurar nada se usa la clave de depuración de tu equipo (vale para uso personal). Para releases define `signing.keystore`, `signing.storePassword`, `signing.keyAlias`, `signing.keyPassword` en `local.properties` (o las variables `TVL_*` del workflow `.github/workflows/release.yml`; al subir una etiqueta `v*` se compilan y publican los APK).

## Launcher (`app/`)

- Inicio con apps, entradas HDMI, "Continuar viendo" (abre el launcher de Google) y Ajustes.
- Al enfocar una app muestra lo popular de esa plataforma (TMDB por proveedor; región e idioma se fijan en la TV: instalador o administrador web → Ajustes), con fondo, sinopsis, reparto, calificación y duración/temporadas.
- Stremio abre la ficha exacta (`stremio:///detail/<series|movie>/<imdb>`). Las demás plataformas no ofrecen un id público: solo se abre la app.
- Los botones del control se reasignan con un servicio de accesibilidad (solo ve pulsaciones, no lee la pantalla y no registra nada): abrir una app, cambiar de entrada, abrir Ajustes, ir al inicio o "no hacer nada".
- Límites conocidos: un launcher de terceros no puede leer el "continuar viendo" de otras apps, y la tecla HOME no se puede reemplazar.


- Al reinstalar `app.tvlauncher` Android desactiva su servicio de accesibilidad. Reactívalo con
  `adb shell settings put secure enabled_accessibility_services app.tvlauncher/app.tvlauncher.KeyMapService`
  (la app también se repara sola si se concedió `WRITE_SECURE_SETTINGS` con `adb shell pm grant`).
- El launcher de Google conserva el HOME. Entra a este con el botón de Google Play del control (asignado a "ir al inicio"), desde el inicio de Google (Apps → "Inicio") o con `adb shell am start -n app.tvlauncher/.MainActivity`.

## Descargas en la TV (`torrent/`)

- Motor libtorrent4j + administrador web NanoHTTPD en `http://<IP_TV>:8800` (usuario `admin`; la clave se ve en la pantalla *Descargas* de la TV y puedes fijar la tuya).
- Añade contenido desde el administrador web o abriendo un enlace `magnet:` en la TV
  (`adb shell am start -a android.intent.action.VIEW -d "magnet:..." app.tvtorrent`).
- **Por descarga** (TV: seleccionar una fila; web: botones de la tarjeta): pausar/reanudar, pedir más conexiones (re-anuncio a trackers y DHT), orden secuencial, verificar archivos, subir en la cola, omitir/incluir archivos, quitar (conserva archivos) y cancelar y borrar. *Detalles* muestra velocidades, semillas/peers, archivos, trackers y las mejores conexiones.
- **Modo descarga** (botón en la TV y en el administrador): el servicio toma un WakeLock parcial y un WifiLock y el servicio de accesibilidad del launcher apaga la pantalla (`GLOBAL_ACTION_LOCK_SCREEN`). Verificado: con la TV en `Asleep` siguen llegando datos y el administrador responde. Al terminar se sueltan los bloqueos y la TV se deja apagar; si alguien la está viendo no se apaga, solo se avisa con una notificación. Tope de seguridad: 12 h.
- **Datos de reanudación** en `files/resume/` de la app (metadatos + piezas verificadas): un reinicio no vuelve a pedir el enlace ni a verificar archivos enteros.
- **Reproducción**: Kodi (predeterminado) o VLC. En esta TV VLC muestra franjas verdes; Kodi se ve limpio. La app abre el archivo directo del USB, así la reproducción no depende de que el administrador siga activo.
- **Pestañas del administrador**: Descargas, Botones (programar el control desde el navegador, con aprendizaje de teclas) y Ajustes (clave fija, reproductor, "siempre activo", estado del almacenamiento).
- La interfaz de la TV está en `Ui.kt` (estilo común), `DownloadsActivity.kt` y `TorrentDetailActivity.kt`.

### Seguridad

Clave (Basic auth), cabecera `X-Portal` en cada acción (anti-CSRF), comprobación de Host, solo enlaces magnet, archivos servidos con una clave aparte y ContentProvider de configuración protegido con permiso de firma. No expongas el puerto 8800 a internet.

### Almacenamiento: USB *extraíble*, no adoptado

- Una app instalada en un USB *adoptado* perdió el acceso a su carpeta de datos (EACCES) tras desmontajes/reinicios en esta Sony: la clave cambiaba en cada arranque y se perdía la lista.
- Solución: formatear el USB como FAT32 extraíble (`sm partition disk:8,0 public`, `sm format public:8,1`, `sm mount public:8,1`) e instalar la app en memoria interna (`pm install --install-location 1`). Las descargas van a `/storage/<uuid>/Android/data/app.tvtorrent/files/downloads`, carpeta elegida una vez y recordada.
- Si la carpeta desaparece (USB desconectado) el motor se detiene y reanuda solo al volver; nunca escribe en otro sitio.
- Límite de FAT32: ningún archivo individual puede pasar de 4 GB. Tampoco admite archivos dispersos: los torrents grandes se rellenan con ceros al reservarse y saturan el USB un rato.

### IP fija

En la TV: Ajustes → Red → Ajustes de IP → Estática. Elige una dirección fuera del rango DHCP del router.


## Lecciones aprendidas

- Todo trabajo de motor y disco va fuera del hilo principal: un USB lento en el principal hace que Android mate la app (ANR).
- El apagado por inactividad del servicio web debe contar los videos abiertos; si no, las reproducciones largas por él mueren al cumplirse el tiempo.
- Las filas de la lista de la TV se actualizan en su sitio (no se recrean) para no perder el foco del control.
