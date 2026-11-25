# Nozzle Guardian — Guida Piattaforme (iOS, Android, Home Server, PC, Vecchi Dispositivi)

**Nozzle Guardian™** è basato al 100% su **Python puro (zero CUPS)** e protocolli standard (**RFC 8011 IPP/IPPS, PWG-Raster, SNMPv1**). Questo consente di usarlo su praticamente qualsiasi hardware domestico:

---

## 1. Smartphone Attivo del Proprietario (Android App Nativa & iPhone iOS)

### App Android Nativa (`NozzleGuardian-Android.apk` — Zero PC per Stampanti Wi-Fi)
1. Scarica **`NozzleGuardian-Android.apk`** da **[GitHub Releases](https://github.com/umbette/nozzle-guardian/releases/latest)** (`android/` è compilata in Kotlin + Jetpack Compose + WorkManager).
2. Tocca **Cerca mDNS** per trovare la stampante Wi-Fi in LAN, attiva l'auto-spurgo periodico (controllo in background ogni 12h su Wi-Fi domestico), scansiona il foglio con la fotocamera o spara un singolo ugello 1–10. *(Le stampanti **solo USB** come Canon G2770 richiedono invece l'App Desktop PC/Mac o Home Assistant).*

### PWA Mobile & Scanner Fotocamera (iOS Safari & Android Chrome)
1. Apri `http://<ip-del-nodo>:8086/` dal browser dello smartphone (su Home Assistant si apre direttamente in **Ingress**).
2. Tocca **Condividi ➔ Aggiungi alla schermata Home** (iOS) oppure **Menu ➔ Installa app** (Android).
3. Scatta la foto al foglio di test con **📸 Scansiona con Fotocamera**: lo scanner ottico corregge automaticamente l'orientamento EXIF e seleziona l'ugello da ripristinare.

### Automazione Diretta da iPhone Attivo (`Apple Comandi / Shortcuts`, Zero Server 24/7 Necessario su Mac/PC Acceso)
I browser su iOS (Safari) non possono inviare pacchetti binari `application/ipp` grezzi direttamente verso la stampante per vincoli CORS/sandbox di WebKit. Per pilotare o automatizzare da **iPhone**:
1. Apri l'app nativa **Comandi (Shortcuts)** su iOS ➔ **Automazione** ➔ **Quando iPhone si connette al Wi-Fi di casa**.
2. Aggiungi l'azione **Ottieni contenuto dell'URL** verso `http://<ip-del-tuo-server-o-pc>:8086/check?force=true` (`Metodo: POST`, Header `X-API-Key: <la-tua-chiave>`).
3. Risultato: ogni volta che rientri a casa col telefono attivo, Nozzle Guardian controlla i contatori reali e spurghe solo i canali rimasti inattivi! (Gli endpoint pronti sono anche esposti su `GET /mobile/shortcut-config`).

### Notifiche Push su iPhone / Android senza Home Assistant (`ntfy`)
Imposta nel file `.env` o in `docker-compose.yml`:
```ini
NTFY_URL=https://ntfy.sh/tuo-topic-privato-casuale
DASHBOARD_PUBLIC_URL=http://192.168.1.50:8086
```
Riceverai notifiche push native su **iOS e Android** ogni volta che viene completato uno spurgo preventivo oppure se manca la carta (`media-empty`).

---

## 2. Home Server & Domotica

### Home Assistant Add-on Nativо
1. In Home Assistant: **Impostazioni ➔ Componenti aggiuntivi ➔ Store ➔ Menu (⋮) ➔ Repository**.
2. Aggiungi `https://github.com/umbette/nozzle-guardian`.
3. Installa **Nozzle Guardian**: supporta **Ingress** nella barra laterale, `host_network: true` per discovery mDNS e notifiche native tramite `SUPERVISOR_TOKEN`.

### Docker / CasaOS / Unraid / Proxmox
```bash
git clone https://github.com/umbette/nozzle-guardian.git
cd nozzle-guardian
cp .env.example .env && cp config.example.yaml config.yaml
docker compose up -d
```

---

## 3. App / Companion per PC con 1 Click (Windows `.exe`, macOS `.app`, Linux — Zero Python)

Ideale per chi non vuole né server né terminali Python:
1. Scarica da **GitHub Releases / Actions (`Build 1-Click Desktop Apps`)** il pacchetto per il tuo sistema (`NozzleGuardian-Windows-x64.zip`, `NozzleGuardian-macOS.zip` o `NozzleGuardian-Linux-x64.tar.gz`).
2. Estrai e fai **doppio click** su `NozzleGuardian`: si avvia nell'area di notifica (System Tray), apre la console nel browser, controlla subito la stampante al risveglio (*Wake-Catchup*) e ti permette con **1 click** di attivare **"Avvia con il PC"**.

*(Da sorgente per sviluppatori: `python -m src.cli desktop --open-ui` oppure compila in locale con `pyinstaller packaging/nozzle_guardian.spec`).*

---

## 4. Vecchi Dispositivi (Termux Android senza Root, Raspberry Pi, Vecchio Laptop, TV Box)

A differenza dei gateway DNS di rete, **Nozzle Guardian non richiede alcun permesso di root**: comunica sulla LAN tramite normali socket HTTP/IPP/UDP SNMPv1.
Su un vecchio smartphone Android (via **Termux** + **Termux:Boot**), Raspberry Pi, Armbian TV Box o vecchio portatile Linux:
```bash
chmod +x scripts/setup_old_device.sh
./scripts/setup_old_device.sh
```
## 5. Stampanti Solo USB senza Wi-Fi né `ipp-usb` (es. Canon PIXMA G2770, G2010, Epson L3110)

Molte stampanti tank economiche **non hanno scheda Wi-Fi** e non espongono `ipp-usb` sulla porta `60000` (usano invece il driver USB proprietario Canon/Epson del sistema operativo):
1. Collega il cavo USB al tuo PC (Windows, macOS, Linux o Raspberry Pi) con il driver ufficiale già installato.
2. Avvia `python -m src.cli desktop --open-ui` (oppure `python -m src.cli check`).
3. Clicca **🔎 Scopri LAN/USB**: apparirà la stampante **`local://<NomeCoda>`** (es. `local://Canon_G2770_series`).
4. Nozzle Guardian rileva automaticamente che una Canon G2770 usa testine termiche **Thermal BubbleJet**, traccia la cadenza tramite lo spooler locale e invia micro-spurghi PDF/PNG direttamente al cavo USB.

