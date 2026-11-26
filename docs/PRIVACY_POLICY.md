# Privacy Policy — Nozzle Guardian

**Last updated:** 2026-11-26  
**Controller / Developer:** Umberto (`umbette`)  
**Repository:** https://github.com/umbette/nozzle-guardian  
**Contact email:** `[INSERT_SUPPORT_EMAIL]`  

---

## English

Nozzle Guardian is designed around **local-first printer maintenance**. Core functionality operates directly between your smartphone and your local Wi-Fi printer over IPP/IPPS, mDNS, SNMP, and local EWS without requiring a user account or cloud servers.

### 1. Data We Do NOT Collect
- We **never** read, upload, or collect the documents you print, PDF/image filenames, camera photos from your gallery, paper contents, or printer serial numbers.
- We **never** collect your home Wi-Fi SSID/password, router MAC address, or precise GPS location (Location labels such as "Home" or "Office" and optional Wi-Fi SSID filters are stored **only inside local app preferences on your device**, used locally by Android `WorkManager` to avoid running local network checks on office/public Wi-Fi).

### 2. Permissions Used
- **Local Network / Wi-Fi State (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`)**: Used on your local LAN to discover IPP printers via mDNS and send tiny preventive CMYK micro-purge patterns directly to your printer.
- **Camera (`android.media.action.IMAGE_CAPTURE`) / Photo Picker (Pro feature)**: Used solely when you tap *Take Photo* or *Gallery* in the Optical Scanner tab to analyze test-sheet nozzle bars **locally on-device**. Images stay in local app cache and are never uploaded.
- **Notifications (`POST_NOTIFICATIONS`)**: Used locally to alert you when an inkjet printer goes idle beyond your configured threshold or when the paper tray is empty.

### 3. Google Play Billing
In-app Lifetime Pro purchases (`nozzle_guardian_pro_lifetime`) are processed exclusively by **Google Play**. Nozzle Guardian receives only your entitlement status and product identifier from Play Billing and never accesses payment card numbers or billing addresses.

### 4. Optional Account & Cross-Device Sync
Creating or saving a profile in *Pro & Info* is **strictly optional** and independent from Google or social login providers. If you enter an optional email/pairing token or configure a custom sync server URL, workspace printer configurations are transmitted only to the endpoint you explicitly configure.

### 5. Optional Opt-In Compatibility Telemetry ("Help Us Improve")
Telemetry is **OFF by default**. If you explicitly enable *"Share anonymous compatibility stats"*, the app sends small anonymous technical events containing **only**:
- A randomly generated resettable UUID (`installId`), app version, and Free/Pro tier flag;
- Generic printer brand and sanitized model family (e.g., `EPSON ET-2810 Series`, stripped of parenthesized IDs);
- Head technology (`PIEZO`, `THERMAL`, `LASER`), ink profile (`cmyk`/`cmyk6`), supported document format, threshold days, event type (e.g., `manual_purge_cmyk`), boolean success, and optional pre/post optical health percentage.

You can disable telemetry or regenerate/reset your `installId` at any time inside *Pro & Info*.

### 6. Data Retention & Deletion (GDPR / UK / US)
- Local app data is deleted immediately by uninstalling the app or clearing app storage.
- To request deletion of any optional synced profile or telemetry records associated with an email or `installId`, contact `[INSERT_SUPPORT_EMAIL]`.

---

## Italiano

**Nozzle Guardian** nasce con approccio **local-first**: tutte le funzioni principali operano direttamente in rete locale tra telefono e stampante (IPP/IPPS, mDNS, SNMP, EWS) senza richiedere account o cloud obbligatori.

### 1. Dati che NON raccogliamo mai
- Non leggiamo né inviamo **mai** ciò che stampi, nomi di file, fotografie dei test ugelli, seriali della stampante o coordinate GPS.
- I nomi dei Luoghi (es. *Casa*, *Ufficio*) e l'eventuale filtro SSID Wi-Fi restano **salvati esclusivamente in locale sul dispositivo** per evitare controlli su reti esterne.

### 2. Permessi richiesti
- **Rete locale / Wi-Fi**: scoperta stampanti mDNS e invio micro-spurghi IPP in LAN.
- **Fotocamera / Galleria** *(solo Scanner Pro)*: analisi ottica eseguita **localmente sul dispositivo** sui fogli test; le foto restano nella cache locale.
- **Notifiche**: avvisi locali su inattività ugelli o vassoio carta vuoto.

### 3. Pagamenti Google Play e Account Opzionale
- L'acquisto Pro Lifetime (`nozzle_guardian_pro_lifetime`) è gestito interamente da Google Play Billing; l'app non vede dati di pagamento.
- L'eventuale profilo / server Sync è **100% facoltativo e indipendente da Google o social login esterni**.

### 4. Telemetria Anonima Opt-In ("Aiutaci a migliorare")
- **Disattivata di default.** Solo se attivi manualmente lo switch, vengono inviati eventi tecnici anonimi privi di IP, SSID Wi-Fi, email o file (solo ID casuale rigenerabile, famiglia modello generica, tecnologia testina, formato IPP, soglia giorni, esito spurgo ed eventuale salute `%`).
- Puoi disattivarla o rigenerare l'ID anonimo in qualsiasi momento nella scheda *Pro & Info*, o richiedere la cancellazione scrivendo a `[INSERT_SUPPORT_EMAIL]`.
