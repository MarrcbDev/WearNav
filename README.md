# WearNav

Navegador para Wear OS (Galaxy Watch 5 Pro, pantalla redonda) basado en **GeckoView** (motor de Firefox).
El reloj no incluye Android WebView, por eso la app trae su propio motor.

- Una sola pestaña, barra flotante que se oculta al hacer scroll, anillo de progreso en el borde
- Favoritos, historial, búsqueda por texto o voz, 3 buscadores
- Bloqueo de anuncios/rastreadores (protección de rastreo de Gecko), modo oscuro web, modo escritorio
- Escala de página y tamaño de texto ajustables
- RAM: pausa la sesión al salir y a los 90 s en segundo plano mata el proceso (guarda la URL); botón "Liberar RAM" con el consumo real (app + procesos de Gecko)

## Compilar
Sube todo a GitHub -> pestaña **Actions** -> "Build APK" -> descarga el artefacto `WearNav-apk`.
Salen dos APK: `app-arm64-v8a-release.apk` y `app-armeabi-v7a-release.apk`.

## Cuál instalar
```
adb shell getprop ro.product.cpu.abilist
```
Si aparece `arm64-v8a` instala el de arm64; si solo hay `armeabi-v7a`, el de v7a.
```
adb install -r app-arm64-v8a-release.apk
```

## Si se cierra al abrir
WearNav guarda el motivo del cierre y lo muestra en pantalla al reabrir. Log completo: `adb logcat -b crash`
