# WearNav

Navegador ligero para Wear OS (probado en la idea para Galaxy Watch 5 Pro, pantalla redonda 450x450).

- Una sola pestaña (menos RAM), WebView con ajustes seguros
- Barra flotante que se oculta al bajar y reaparece al subir
- Anillo de progreso que sigue el borde redondo
- Bloqueo de anuncios por dominio, modo sin imágenes, modo lectura, modo oscuro web
- Favoritos / historial, búsqueda por texto o voz, zoom de texto, ancho virtual
- Gestión de RAM: pausa al salir, destruye el WebView a los 45 s en segundo plano (restaura el historial al volver), reacciona a onTrimMemory y al cierre del renderizador

## Compilar
Sube todo a GitHub (rama `main`) -> pestaña **Actions** -> "Build APK" -> descarga el artefacto `WearNav-apk`.

## Instalar en el reloj
Reloj: Ajustes > Opciones de desarrollador > Depuración ADB + Depuración por Wi-Fi
```
adb pair IP:PUERTO_EMPAREJAMIENTO
adb connect IP:PUERTO
adb install -r app-release.apk
```

## Si se cierra al abrir
WearNav guarda el motivo del cierre y lo muestra en pantalla al reabrir. Si quieres el log completo:
```
adb logcat -b crash
```
Si el error menciona "WebView", actualiza/instala *Android System WebView* desde Play Store en el reloj.
