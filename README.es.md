# tv-launcher

🇬🇧 [Read in English](README.md)

Launcher propio y descargador de torrents **dentro de la TV** para una **Sony BRAVIA (Android TV 9)**, más un administrador web que abres desde el computador o el celular.

Dos apps Android en un mismo proyecto Gradle:

| Módulo | Paquete | Qué es |
|---|---|---|
| `app/` | `app.tvlauncher` | Inicio: apps, entradas HDMI como botones, lo popular de cada plataforma con fondo, sinopsis, reparto y calificación (TMDB). También reasigna los botones del control. |
| `torrent/` | `app.tvtorrent` | Descarga y reproduce **en la propia TV** (motor libtorrent4j, almacenamiento USB) y sirve un administrador web. |

> Úsalo solo con contenido que tengas derecho a descargar y ver.

## Launcher (`app/`)

- Inicio con apps, entradas HDMI, "Continuar viendo" (abre el launcher de Google) y Ajustes.
- Al enfocar una app muestra lo popular de esa plataforma (TMDB por proveedor; región e idioma en `local.properties`), con fondo, sinopsis, reparto, calificación y duración/temporadas.
- Stremio abre la ficha exacta (`stremio:///detail/<series|movie>/<imdb>`). Las demás plataformas no ofrecen un id público: solo se abre la app.
- Los botones del control se reasignan con un servicio de accesibilidad (solo ve pulsaciones, no lee la pantalla y no registra nada): abrir una app, cambiar de entrada, abrir Ajustes, ir al inicio o "no hacer nada".
- Límites conocidos: un launcher de terceros no puede leer el "continuar viendo" de otras apps, y la tecla HOME no se puede reemplazar.

### Compilar e instalar

```bash
# local.properties (no se sube al repositorio):
#   sdk.dir=/ruta/al/android-sdk
#   tmdb.key=TU_CLAVE_API_V3_DE_TMDB
#   tmdb.region=CO        (opcional)
#   tmdb.lang=es-CO       (opcional)
export JAVA_HOME=/ruta/al/jdk17 ANDROID_HOME=/ruta/al/android-sdk
./gradlew :app:assembleDebug :torrent:assembleDebug

adb connect <IP_TV>:5555
adb -s <IP_TV>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <IP_TV>:5555 install -r --install-location 1 torrent/build/outputs/apk/debug/torrent-debug.apk
```

Los dos APK deben firmarse con la misma clave (las apps hablan por un ContentProvider protegido con permiso de firma).

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

## Revertir los cambios del sistema

`revertir_tv.sh` reactiva los paquetes de Sony/Google que se deshabilitaron (nunca se borraron) y restaura las animaciones:

```bash
bash revertir_tv.sh <IP_TV>:5555
```

## Lecciones aprendidas

- Todo trabajo de motor y disco va fuera del hilo principal: un USB lento en el principal hace que Android mate la app (ANR).
- El apagado por inactividad del servicio web debe contar los videos abiertos; si no, las reproducciones largas por él mueren al cumplirse el tiempo.
- Las filas de la lista de la TV se actualizan en su sitio (no se recrean) para no perder el foco del control.
