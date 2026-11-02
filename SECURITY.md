# Seguridad

Este proyecto instala un launcher en un TV por ADB, así que un fallo de seguridad puede llegar a la casa de alguien.
Repórtalo en privado, no en un issue público.

## Cómo reportar

Abre la pestaña **Security** del repositorio y elige **Report a vulnerability**. Incluye qué está afectado, los pasos
para reproducirlo y qué podría hacer un atacante.

Si el fallo está en `tools/`, `launcher/build-and-install.ps1` o el método de limpieza, repórtalo a
[cleanyourtv](https://github.com/Gathin23/cleanyourtv/security), de donde vienen esos archivos.

## Qué cuenta

- El launcher se conecta a algo distinto de las páginas e imágenes de Play Store y los enlaces del tema, expone datos
  a otras apps o abre algo que no debería.
- Los scripts ejecutan comandos en un equipo al que no se apuntó, o manejan mal rutas o datos de entrada.
- Una lista deja un TV que no se puede restaurar con `scripts\restore.ps1`.
- Un pull request ejecuta código en el equipo de quien colabora o revisa.

ADB es poderoso por diseño. Apaga la depuración cuando termines.
