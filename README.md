# Android Zebra Bridge

Android Bridge-App für die Kommunikation zwischen Web-Apps und Zebra Bluetooth-Druckern.

## Funktionsweise

Diese App startet einen lokalen HTTP-Server auf Port 9100, der die Zebra Browser Print API simuliert. Web-Apps können so über `localhost:9100` mit Bluetooth-Druckern kommunizieren.

```
Web-App (Browser)
      ↓ HTTP POST (localhost:9100)
Android Bridge-App
      ↓ Bluetooth SPP
Zebra Drucker
```

## Installation

### 1. Zebra LinkOS SDK herunterladen

1. Besuche [Zebra Support Downloads](https://www.zebra.com/us/en/support-downloads/printer-software/link-os-multiplatform-sdk.html)
2. Lade das "Link-OS Multiplatform SDK" herunter
3. Extrahiere `ZSDK_ANDROID_API.jar` aus dem SDK
4. Kopiere die JAR-Datei nach `app/libs/ZSDK_ANDROID_API.jar`

### 2. App bauen

```bash
./gradlew assembleDebug
```

### 3. Auf Gerät installieren

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Verwendung

1. **Bluetooth-Drucker koppeln** - Über Android Einstellungen oder Zebra Utilities
2. **App starten** - Zebra Bridge öffnen
3. **Service starten** - "Start" Button drücken
4. **Drucker suchen** - "Drucker suchen" drücken
5. **Web-App nutzen** - Die Web-App kann nun über `http://localhost:9100` drucken

## API Endpunkte

| Endpunkt | Methode | Beschreibung |
|----------|---------|-------------|
| `/` | GET | Verfügbarkeit prüfen |
| `/status` | GET | Server-Status |
| `/devices` | GET | Gefundene Drucker (Browser Print kompatibel) |
| `/default_device` | GET | Standard-Drucker |
| `/printers` | GET | Drucker-Liste |
| `/write` | POST | Daten an Drucker senden (Browser Print kompatibel) |
| `/print` | POST | Druckauftrag senden |
| `/connect` | POST | Mit Drucker verbinden |
| `/scan` | POST | Nach Druckern suchen |

## Web-App Anpassung

In der Web-App muss die SDK-URL von `https://localhost:9100` auf `http://localhost:9100` geändert werden (HTTP statt HTTPS).

```typescript
// Vorher:
const SDK_URL = 'https://localhost:9100/BrowserPrint-3.1.250.min.js';

// Nachher - Direkte HTTP API ohne SDK:
const BRIDGE_URL = 'http://localhost:9100';

async function print(data: string) {
  const response = await fetch(`${BRIDGE_URL}/print`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ data })
  });
  return response.json();
}
```

## Berechtigungen

Die App benötigt folgende Berechtigungen:
- `BLUETOOTH`, `BLUETOOTH_ADMIN` - Bluetooth-Kommunikation
- `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` - Android 12+ Bluetooth
- `INTERNET` - HTTP Server
- `FOREGROUND_SERVICE` - Hintergrund-Service

## Unterstützte Drucker

Alle Zebra Mobile- und Desktop-Drucker die CPCL oder ZPL unterstützen:
- ZQ-Serie (ZQ110, ZQ220, ZQ320, ZQ520, ZQ630)
- ZD-Serie (ZD410, ZD420, ZD620)
- QLn-Serie (QLn220, QLn320, QLn420)
- iMZ-Serie (iMZ220, iMZ320)
- Und weitere...

## Lizenz

MIT License
