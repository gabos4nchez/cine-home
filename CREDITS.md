# Créditos

## cleanyourtv

Cine Home es una apariencia basada en las soluciones de
[cleanyourtv](https://github.com/Gathin23/cleanyourtv), de [Gathin](https://github.com/Gathin23), publicado bajo
licencia MIT. Las guías de ese repositorio piden que un launcher nuevo viva en su propio repositorio, y este es ese
caso.

Tomado de la versión [`fe9ad32`](https://github.com/Gathin23/cleanyourtv/tree/fe9ad3204b945d3aadfb6876ee6ea78c20a1fe73):

| Archivo | Estado |
|---|---|
| `tools/tv.py`, `b64push.py`, `recovery.py`, `theme_restart.py`, `adb-closed.py` | Sin modificar |
| `tools/disable.ps1`, `restore.ps1`, `theme.ps1` | Sin modificar |
| `tools/requirements.txt`, `tools/docker/Dockerfile` | Sin modificar |
| `launcher/build-and-install.ps1` | Sin modificar |
| `launcher/gradlew`, `gradlew.bat`, `gradle/wrapper/`, ajustes de Gradle | Base del launcher original |
| Paquete `com.gathin.tvhome` y formato de `config.json` | Se mantienen por compatibilidad con las herramientas |

También vienen de allí el método de limpieza y sus reglas de seguridad.

## Propio de este proyecto

Por Gabo:

- `launcher/app/`: la apariencia del Home, escrita para este proyecto con el launcher original como referencia.
- `device/onn-4k/`: la lista de limpieza de este equipo.
- `scripts/`, `theme/` y la documentación.

## Terceros

- [Gradle Wrapper](https://gradle.org), licencia Apache 2.0.
- [AndroidX RecyclerView](https://developer.android.com/jetpack/androidx/releases/recyclerview), licencia Apache 2.0.
  Se descarga al compilar.
- [adb-shell](https://github.com/JeffLIrion/adb_shell), licencia Apache 2.0. Se descarga con `requirements.txt`.

Los nombres y logos de apps y marcas que aparecen en las capturas pertenecen a sus dueños.
