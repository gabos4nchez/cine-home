# Cine Home

Una apariencia para el Home de Android TV / Google TV, basada en las soluciones de
[cleanyourtv](https://github.com/Gathin23/cleanyourtv).

![El Home con una app seleccionada](docs/home.jpg)

> **Cine Home es solo una apariencia. Las soluciones son de
> [cleanyourtv](https://github.com/Gathin23/cleanyourtv), de [Gathin](https://github.com/Gathin23).**
> El método de limpieza, las herramientas ADB y sus controles de seguridad vienen de ese proyecto, bajo licencia MIT.
> Lo que aporta este repositorio es el aspecto del Home y la lista de un **onn. 4K Streaming Box**. Detalle en
> [CREDITS.md](CREDITS.md). No es un proyecto oficial de cleanyourtv, onn., Walmart ni Google.

**In English:** Cine Home is a full-screen look for the Android TV / Google TV home screen, based on the solutions
from [cleanyourtv](https://github.com/Gathin23/cleanyourtv). The APK is about 132 KB, with no background services, no
blur and no video. Behind the title, each app shows pictures that take turns: your own (pushed from the computer or
linked from the theme), or else its dark TV screenshots from its Play Store page, fetched while Home is on screen.
`"auto_pictures": false` in the theme turns all network use off. It is built from source with Docker, and the debloat
is reversible because apps are disabled, never uninstalled. Tested only on an onn. 4K Streaming Box (Google TV 14). The docs are in Spanish;
issues and pull requests in English are welcome.

## Qué hace

- **Home a pantalla completa:** título con degradado en el color de la app seleccionada, fondo fluido y una imagen
  destacada o el logo grande de la app.
- **Imágenes de cada app que rotan:** las tuyas, subidas desde el PC o enlazadas en el tema, o si no las capturas de TV
  oscuras de su página de Play Store, también para las apps que instales después. Cambian con un fundido cada 8
  segundos. Ver [Imagen destacada](#imagen-destacada).
- **Canales por páginas:** seis por página en una sola fila, con flechas que indican si hay más.
- **Botón de Wi-Fi** arriba a la derecha.
- **Menú al mantener OK** sobre una app: Abrir, Mover, Actualizar (si vino de Play Store), Información y
  Desinstalar (si no es del sistema). **Mover** levanta la app para llevarla con ◀ ▶; OK o Atrás guarda el orden.
- **Play Store y Ajustes siempre al final:** las apps nuevas entran antes que ellas. Solo cambian de lugar si tú las
  mueves con Mover.
- **Ligero:** APK de unos 131 KB en Java. Sin servicios en segundo plano, sin desenfoque ni video. Su único uso de
  internet son esas imágenes, y se apaga desde el tema.
- **Limpieza reversible:** las apps se deshabilitan y nunca se desinstalan.

Probado en un onn. 4K Streaming Box (YOC), Google TV 14 (SDK 34), build `URO4.260304.092.16202347`. En otros equipos
debería funcionar con Android 8 o superior, pero no se ha probado.

## Requisitos

- Windows con PowerShell y Python 3.
- Docker Desktop encendido, para compilar sin instalar JDK ni Android SDK.
- En el TV: Ajustes > Sistema > Opciones de desarrollador > **Depuración USB** activa. Acepta el aviso «¿Permitir
  depuración USB?» cuando aparezca.
- El repositorio en una ruta corta, como `C:\cine-home`. Con rutas muy largas falla el cliente ADB de Python.

Una sola vez, desde la carpeta del repositorio:

```powershell
python -m venv tools\venv
tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt
```

## Instalar

Antes de empezar, guarda el estado del TV como explica el
[procedimiento de cleanyourtv](https://github.com/Gathin23/cleanyourtv/blob/main/PROMPT.md).

### 1. Limpiar por tandas

Revisa primero las listas de [`device/onn-4k`](device/onn-4k): están hechas para un dueño concreto. Quita cualquier
app que uses. Aplica una tanda, mira que el TV siga bien y pasa a la siguiente:

```powershell
powershell -ExecutionPolicy Bypass -File tools\disable.ps1 -TvHost <ip del tv> device\onn-4k\batch-1-telemetry.txt
powershell -ExecutionPolicy Bypass -File tools\disable.ps1 -TvHost <ip del tv> device\onn-4k\batch-2-preloaded.txt
powershell -ExecutionPolicy Bypass -File tools\disable.ps1 -TvHost <ip del tv> device\onn-4k\batch-3-streaming.txt
powershell -ExecutionPolicy Bypass -File tools\disable.ps1 -TvHost <ip del tv> device\onn-4k\batch-4-installed.txt
```

### 2. Instalar el Home

Con Docker Desktop encendido:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-and-install.ps1 -TvHost <ip del tv>
```

Compila, instala, comprueba que Home quedó en el launcher nuevo y solo entonces deshabilita el original. Si algo
falla, lo vuelve a activar.

### 3. Última tanda

El asistente de configuración también responde a Home, así que va después del launcher. `disable.ps1` lo omite si es
dueño de tu red Wi-Fi guardada, porque deshabilitarlo te dejaría sin Wi-Fi:

```powershell
powershell -ExecutionPolicy Bypass -File tools\disable.ps1 -TvHost <ip del tv> device\onn-4k\batch-5-setupwraith.txt
```

Reinicia el TV y prueba el control remoto real.

### 4. Cerrar ADB

Apaga la Depuración USB al terminar: el puerto 5555 va sin cifrar. Comprueba después del reinicio que `closed` sea la
respuesta:

```powershell
tools\venv\Scripts\python.exe -I tools\adb-closed.py <ip del tv>
```

## Deshacer todo

```powershell
powershell -ExecutionPolicy Bypass -File scripts\restore.ps1 -TvHost <ip del tv>
```

Rehabilita lo deshabilitado y devuelve Home al launcher original de Google. Usa los registros que `disable.ps1` guardó
en `%USERPROFILE%\.cleanyourtv\`, ligados a la IP del TV y a la ruta de cada lista. Si moviste la carpeta o cambió la
IP, el script avisa y habilita todos los paquetes de las listas, incluidos los que ya estuvieran deshabilitados de
antes.

**Antes de un restablecimiento de fábrica, ejecuta este script.** El asistente y el launcher original deben estar
habilitados.

Para volver a habilitar una sola app:

```powershell
$env:TV_HOST = '<ip del tv>'
tools\venv\Scripts\python.exe -I tools\tv.py "pm enable com.netflix.ninja"
```

## Personalizar

### Canales

Edita [`theme/config.json`](theme/config.json) y aplícalo, sin recompilar:

```powershell
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost <ip del tv> theme
```

| Clave | Para qué sirve |
|---|---|
| `dock_apps` | El orden de los canales, seis por página |
| `app_labels` | El nombre de cada canal: `"paquete=Nombre"` |
| `app_descriptions` | El texto bajo el título: `"paquete=Texto"` |
| `app_colors` | El color de una app: `"paquete=#1E3A8A"`. Sin él, se toma de su banner |
| `hidden_apps` | Apps que no se muestran |
| `app_pictures` | Enlaces a imágenes propias de una app: `"paquete=https://… https://…"`. Ver [Tus imágenes](#tus-imágenes) |
| `auto_pictures` | `false` para que el launcher no use internet: ni Play Store ni enlaces. Por defecto `true` |

Una app nueva con versión para TV aparece sola en la última página. Una sin versión para TV no aparece en el Home, y
se abre desde Ajustes > Apps.

### Imagen destacada

**Automática.** Mientras el Home está en pantalla, el launcher abre la página de Play Store de cada app, una a la vez,
y guarda hasta 6 de sus capturas de TV: solo las horizontales y oscuras, para que no encandilen detrás del título. Si
hay dos o más, rotan cada 8 segundos. Se renuevan cada 7 días, y las de una app desinstalada se borran.

- Una app que no está en Play Store, o que solo tiene capturas claras de menús, muestra su logo grande sobre un
  resplandor de su color, como antes.
- La página de Play Store no es una API: si Google la cambia, las apps vuelven a su logo hasta que se ajuste
  [`PlayPictures.java`](launcher/app/src/main/java/com/gathin/tvhome/PlayPictures.java).
- Al pedir esas páginas, Google ve qué apps tienes instaladas, igual que cuando las abres en Play Store. Para que el
  launcher no use internet, pon `"auto_pictures": false` en el `config.json` de tu tema.

### Tus imágenes

Puedes darle a cualquier app tus propias imágenes, también a las que no están en ninguna tienda. Tienen prioridad sobre
las de Play Store, y si una app tiene dos o más, rotan cada 8 segundos. Cada una se aclara u oscurece según su brillo,
para que no quede ni apagada ni encandilando.

**1. Averigua el nombre del paquete de la app.** Es el nombre con el que Android la conoce, como `com.esaba.downloader`.
Para ver los de las apps que aparecen en el Home:

```powershell
$env:TV_HOST = '<ip del tv>'
tools\venv\Scripts\python.exe -I tools\tv.py "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"
```

Cada línea es `paquete/actividad`: lo que va antes de la `/` es el paquete.

**2. Prepara la carpeta.** Crea una carpeta para tus imágenes y, dentro, una carpeta por app con el nombre de su
paquete:

```text
mis-imagenes\
  com.esaba.downloader\
    01-fondo.jpg
    02-otro.png
  com.otra.app.jpg        (una sola imagen fija, también vale)
```

- **Formatos:** `.jpg`, `.jpeg`, `.png` o `.webp`. Android 12 o superior también abre AVIF, aunque el archivo termine en
  `.jpg`.
- **Tamaño:** mejor de 1920x1080 o más, horizontales y con lo importante a la derecha, porque a la izquierda va el
  título. Las más grandes se reducen solas al abrirse. Una pequeña, como 670x460, se ve borrosa.
- **Orden:** rotan por nombre, y las mayúsculas van antes que las minúsculas. Los números al inicio (`01-`, `02-`) te
  dejan elegir el orden.
- **Dónde guardarla:** puede ser [`hero/`](hero), dentro del repositorio, donde git las ignora. Si prefieres no
  tenerlas cerca de algo que se publica, guárdalas en cualquier otra carpeta de tu PC.

**3. Súbelas al TV.** Con la depuración USB activa y desde la carpeta del repositorio:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\push-heroes.ps1 -TvHost <ip del tv>
```

Si tus imágenes no están en `hero/`, añade `-Folder` con la ruta de tu carpeta:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\push-heroes.ps1 -TvHost <ip del tv> -Folder C:\mis-imagenes
```

**4. Míralas.** Vuelve al Home desde cualquier app y selecciona la app.

**Para cambiarlas o quitarlas,** añade o borra imágenes de tu carpeta y vuelve a ejecutar el paso 3. Cada carpeta
reemplaza a la del TV, así que el TV queda igual que tu PC. Una imagen suelta (`paquete.jpg`) no se borra sola del TV.
Si ya no la quieres, bórrala con:

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py "rm /sdcard/Android/data/com.gathin.tvhome/files/hero/<paquete>.jpg"
```

**Si una imagen no aparece:**

- Comprueba que llegó al TV:

  ```powershell
  tools\venv\Scripts\python.exe -I tools\tv.py "ls -R /sdcard/Android/data/com.gathin.tvhome/files/hero"
  ```

- Revisa que la carpeta tenga exactamente el nombre del paquete.
- Si el archivo no es una imagen que Android pueda abrir, en su turno se queda la imagen anterior.

**Con enlaces, sin el PC.** En lugar de subirlas, puedes poner sus direcciones en el `config.json` de tu tema, con
`app_pictures`: una entrada por app y las direcciones `https://` separadas por espacios. Aplica el tema como en
[Canales](#canales).

```json
"app_pictures": [
  "com.esaba.downloader=https://ejemplo.com/fondo-1.jpg https://ejemplo.com/fondo-2.jpg"
]
```

El launcher las descarga con el Home en pantalla, hasta 10 por app. Vuelve a descargarlas cada 7 días o en cuanto
cambias la lista, y si quitas la entrada borra lo descargado. Las imágenes subidas y las enlazadas se suman. Usa
imágenes que tengas derecho a usar y cuyas direcciones no cambien. Con `"auto_pictures": false` no se descargan.

Tus imágenes nunca se publican: `hero/` está en `.gitignore` y los enlaces van en tu tema. No las subas a GitHub si
son de terceros, como carteles de series o publicidad.

### Colores del fondo

Están en [`ArtView.java`](launcher/app/src/main/java/com/gathin/tvhome/ArtView.java) y hay que recompilar:

- `BASE`: el azul marino de la izquierda.
- `DEFAULT_COLOR`: el resplandor de las apps sin color propio.
- `PICTURE_TARGET`: el brillo medio al que se llevan tus imágenes, entre `STORE_DIM` y `PICTURE_LIFT`.
- `STORE_DIM`: cuánto se oscurecen las de Play Store.

El acento coral es `accent` en
[`values.xml`](launcher/app/src/main/res/values/values.xml).

## Lo que conviene saber

- **Comparte paquete con el launcher de cleanyourtv:** `com.gathin.tvhome`. Las herramientas lo esperan así, de modo
  que uno sustituye al otro y no pueden convivir.
- **Clave de firma:** el APK se firma con una clave de depuración que vive en el volumen de Docker
  `cleanyourtv-gradle`. Si lo borras, la siguiente instalación falla con `INSTALL_FAILED_UPDATE_INCOMPATIBLE`:
  desinstala primero `com.gathin.tvhome`, y con él su tema, sus imágenes y el orden de las apps. Guarda una copia
  fuera del repositorio, nunca en él:
  `docker run --rm -v cleanyourtv-gradle:/gradle -v "<carpeta>:/out" cleanyourtv-android-build cp /gradle/.android/debug.keystore /out/`.
  Por eso no se publican APK; cada quien compila el suyo.
- **Chromecast:** funcionó con el asistente deshabilitado. YouTube y Netflix no abren por Cast mientras sus apps sigan
  deshabilitadas.
- **Botones del control:** el engranaje y los botones de apps pueden no hacer nada en este Home.
- **Si algo sale mal:** consulta la
  [guía de problemas de cleanyourtv](https://github.com/Gathin23/cleanyourtv/blob/main/docs/TROUBLESHOOTING.md). Los
  mensajes de `disable.ps1` citan esa guía y sus incidencias.

## Seguridad

Las herramientas se niegan a deshabilitar paquetes básicos de Android, y comprueban Home y el Wi-Fi antes de tocarlos.
Aun así, la limpieza es bajo tu responsabilidad, sin garantía. No publiques capturas ni copias de tu TV que muestren
tu Wi-Fi, tu IP, tu cuenta o el número de serie.

## Colaborar

El proyecto es abierto: puedes abrir un issue o enviar un pull request con mejoras al Home, listas para otros TV o
documentación. Empieza por [CONTRIBUTING.md](CONTRIBUTING.md). Lo que afecte a las herramientas o al método de
limpieza va a [cleanyourtv](https://github.com/Gathin23/cleanyourtv).

## Licencia

[MIT](LICENSE). Conserva los avisos de copyright de Gathin y de este proyecto.
