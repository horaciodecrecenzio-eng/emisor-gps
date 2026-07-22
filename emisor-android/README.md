# COR Emisor GPS — app Android

App nativa que el chofer instala en su celular para transmitir la ubicación del
ómnibus al servidor de tracking. A diferencia de `emisor.html`, corre como
**servicio en primer plano**: sigue enviando la posición con la pantalla
apagada o con la app en segundo plano.

## Cómo funciona

1. Al abrir, pide la **URL del servidor** (la misma donde corre el backend
   Node) y trae la lista de ómnibus desde `GET /api/config`.
2. El chofer elige su unidad y toca **Iniciar transmisión**.
3. La app lee el GPS cada ~5 s y hace `POST /api/position` con
   `{ busId, lat, lng, speed }` — exactamente el formato que ya espera
   `server.js`. **No hay que cambiar nada en el backend.**
4. Una notificación fija indica que está transmitiendo. **Detener** corta el
   envío.

Usa el GPS del sistema (`LocationManager`), sin depender de Google Play
Services.

## Requisitos de la app en el celular

- Android 7.0 (API 24) o superior.
- Conceder ubicación **"Permitir todo el tiempo"** (si no, solo transmite con
  la app abierta).
- Conviene **desactivar la optimización de batería** para esta app, así el
  sistema no la suspende en viajes largos (Ajustes → Batería → la app → Sin
  restricciones).

---

## Compilar el APK

No necesitás Android Studio. Elegí una de las dos vías.

### Opción A — GitHub Actions (sin instalar nada)

1. Subí esta carpeta a un repositorio de GitHub.
2. En la pestaña **Actions**, corré el workflow **Build APK** (se dispara solo
   en cada push, o a mano con *Run workflow*).
3. Cuando termina, entrá al run y descargá el artifact **cor-emisor-apk**:
   adentro está `app-debug.apk`.

### Opción B — Docker (ya tenés Docker Desktop)

Desde esta carpeta (`emisor-android`):

```powershell
docker build -t cor-emisor .
docker run --rm -v "${PWD}:/export" cor-emisor
```

Queda `app-debug.apk` en la carpeta. (En CMD clásico, reemplazá `${PWD}` por
`%cd%`.)

---

## Instalar en el celular del chofer

1. Pasá el `app-debug.apk` al teléfono (cable, WhatsApp, Drive, etc.).
2. Abrí el archivo; Android va a pedir habilitar **"Instalar apps de origen
   desconocido"** para esa app. Aceptá.
3. Abrí **COR Emisor**, escribí la URL del servidor (ej.
   `https://tudominio.com`) y tocá **Cargar ómnibus**.
4. Elegí la unidad, tocá **Iniciar** y concedé la ubicación **"Permitir todo
   el tiempo"**.

El APK está firmado en modo *debug*: sirve perfecto para instalar por sideload
en un prototipo. Para publicarlo formalmente más adelante se genera un APK
*release* firmado con tu propia clave.

## Notas

- La URL y el ómnibus elegido quedan guardados; la próxima vez solo abrís y
  tocás Iniciar.
- Si tu servidor es `http://` (por ejemplo pruebas en LAN), ya está permitido
  el tráfico en claro. En producción usá `https://` (el que da Hostinger).
- Esta app reemplaza a `emisor.html` para uso diario; el `dashboard.html`
  bueno (el de `cor-web`) no se toca.
