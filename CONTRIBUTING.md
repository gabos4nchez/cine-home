# Cómo colaborar

Cine Home es una apariencia basada en las soluciones de [cleanyourtv](https://github.com/Gathin23/cleanyourtv). Toda
ayuda es bienvenida, también si es tu primer pull request.

## Dónde va cada cosa

| Lo que quieres aportar | Dónde |
|---|---|
| Mejoras al aspecto del Home, navegación con el control, errores del launcher | Aquí: `launcher/` |
| Una lista para otro TV | Aquí: `device/<marca-modelo>/` |
| Un tema de ejemplo, documentación, traducciones | Aquí |
| Scripts de `scripts/` | Aquí |
| Errores en `tools/`, `launcher/build-and-install.ps1`, el método de limpieza o sus reglas de seguridad | En [cleanyourtv](https://github.com/Gathin23/cleanyourtv). Esos archivos son copias sin modificar |
| Dudas sobre limpiar un TV | En las [Discussions de cleanyourtv](https://github.com/Gathin23/cleanyourtv/discussions) |

Para un cambio grande, abre primero un issue y lo conversamos.

## Cómo enviar un cambio

1. Haz un fork y crea una rama.
2. Haz el cambio. Un solo tema por pull request; pequeño está bien.
3. Compila con Docker Desktop encendido:

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\build-and-install.ps1 -SoloCompilar
   ```

4. Pruébalo en un TV real, no solo en un emulador.
5. Abre el pull request y llena la plantilla.

## Reglas del proyecto

Vienen de cleanyourtv y mantienen el Home ligero y seguro:

- **Red mínima:** el único uso de internet son las imágenes de Play Store y las que enlaza el tema
  ([`PlayPictures.java`](launcher/app/src/main/java/com/gathin/tvhome/PlayPictures.java)), solo con el Home en
  pantalla y apagable con `"auto_pictures": false`. Nada de analíticas, telemetría ni otras conexiones.
- **Ligero:** sin servicios en segundo plano, consultas periódicas, desenfoque ni video.
- **Control remoto predecible:** cada botón tiene un solo resultado, probado desde todos los bordes.
- **Sin dependencias nuevas** sin hablarlo antes en un issue.
- **Sin APK ni binarios nuevos en el repositorio:** cada quien compila el suyo.
- **Nunca desinstalar:** las listas solo deshabilitan. Nada de root ni de firmware modificado.
- **`minSdk` 26:** no se baja.

## Listas para otros TV

Crea `device/<marca-modelo>/debloat.txt`. Incluye en la cabecera el modelo, la versión de Android y la build. Pon solo
paquetes que deshabilitaste de verdad y con los que viviste un tiempo. Si la lista sirve para muchos, considera
enviarla también a [cleanyourtv](https://github.com/Gathin23/cleanyourtv), que tiene su propio formato.

## Privacidad

No publiques en commits, issues ni capturas:

- IP, nombres de Wi-Fi, correos o nombres de cuenta.
- Números de serie, Android ID, direcciones MAC o claves ADB.
- El contenido de `backup/`.

En la documentación usa `<ip del tv>`. Tampoco subas imágenes destacadas con derechos de terceros, ni capturas de
Ajustes que muestren tu cuenta o el nombre del equipo.

## Licencia

Lo que aportes se publica bajo la [licencia MIT](LICENSE) del proyecto.
