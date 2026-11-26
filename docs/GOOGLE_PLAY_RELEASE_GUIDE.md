# Google Play Console Release & Launch Checklist (`com.umbette.nozzleguardian`)

## 1. Build Artifacts & Signing
1. **Binary**: Download `NozzleGuardian-Android-PlayStore.aab` from the latest GitHub Release or GitHub Actions artifact (`Build Android App (APK & AAB)`).
2. **Play App Signing**:
   - Currently `android/app/build.gradle.kts` signs release bundles with the debug config unless an upload keystore is provided. **Before uploading to Production on Google Play Console**, configure a dedicated release upload keystore (`upload-keystore.jks`) in GitHub Secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASS`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASS`) and opt in to **Play App Signing** in Google Play Console.
3. **Current Version**: `versionCode = 4`, `versionName = "1.4.0"`, `minSdk = 26`, `targetSdk = 35`.

---

## 2. Store Listing Copy (Ready to Paste)

### English (en-US / Default)
- **App name**: `Nozzle Guardian`
- **Short description (<=80 chars)**:  
  `Prevent dried inkjet nozzles with 0.006ml micro-purges. No PC, no ink waste.`
- **Full description**:
  > Stop wasting 8–15 ml of ink on forced printer head cleanings! **Nozzle Guardian** keeps EcoTank, MegaTank, Smart Tank, and cartridge inkjet printers healthy using **~0.006 ml driverless CMYK micro-purges** directly over your home Wi-Fi (IPP / PWG-Raster).
  >
  > **Free Forever:**
  > • Automatic mDNS discovery + manual IP setup (Zero cloud or server required)
  > • Real-time ink tank levels & B/W + Color page counters (IPP / SNMP / EWS)
  > • Free idle-days reminder alerts & manual CMYK / 6-Tank Photo micro-purges
  > • Free manual surgical unclogging matrix (steps 1–10)
  > • Built-in Laser/Toner detection: safely skips ink purges and monitors toner & page counters
  >
  > **Lifetime Pro (One-Time Purchase — No Monthly Subscription):**
  > • Silent background Auto-Purge every 12h when your phone is on home Wi-Fi
  > • Optical Camera Scanner to automatically detect missing nozzles (1–10)
  > • Multi-Printer & Location groups (Home / Office / Studio with Wi-Fi SSID matching)
  > • Device Workspace Backup & Sync

### Italiano (it-IT)
- **Nome app**: `Nozzle Guardian`
- **Descrizione breve (<=80 car.)**:  
  `Previeni ugelli secchi con micro-spurghi da 0,006 ml via Wi-Fi. Zero sprechi.`
- **Descrizione completa**:
  > Basta buttare 8–15 ml di inchiostro con le pulizie testina forzate! **Nozzle Guardian** protegge stampanti EcoTank, MegaTank, Smart Tank e a cartucce inviando micro-spurghi mirati da ~0,006 ml direttamente dal telefono via Wi-Fi (IPP / PWG-Raster), senza PC e senza driver.
  >
  > **Gratis per sempre:**
  > • Ricerca mDNS automatica, livelli inchiostro e contatori B/N + Colori
  > • Avvisi di inattività gratuiti, micro-spurghi manuali CMYK / 6-Tank Photo e matrice chirurgica
  > • Riconoscimento automatico stampanti Laser/Toner (spurghi disattivati, monitoraggio toner attivo)
  >
  > **Pro Lifetime (Acquisto unico — nessun abbonamento):**
  > • Auto-spurgo automatico in background ogni 12h sul Wi-Fi di casa (protegge la stampante di tutta la famiglia)
  > • Scanner ottico con fotocamera per individuare i singoli ugelli ostruiti (1–10)
  > • Gestione Multi-Stampante e Luoghi (Casa / Ufficio / Studio) + Backup/Sync

---

## 3. Monetization (Play Console → Products → In-app Products)
Create **one managed one-time product** matching `EntitlementPolicy.SKU_PRO_LIFETIME`:
- **Product ID**: `nozzle_guardian_pro_lifetime`
- **Type**: Non-consumable One-Time Purchase (`INAPP`)
- **Recommended launch price**: `€4.99` (or `€6.99` standard)
- *(Optional legacy support)*: no need to publish recurring subscriptions unless migrating pre-existing subscribers.

---

## 4. App Content & Data Safety Form
1. **Privacy Policy URL**: Host `docs/PRIVACY_POLICY.md` on GitHub Pages (`https://umbette.github.io/nozzle-guardian/privacy`) or your custom domain and paste the public HTTPS URL into Play Console.
2. **Data Safety Declarations**:
   - *Does your app collect or share required user data?* -> **No data collected automatically** (only optional data if the user turns on Opt-In Telemetry or enters an Account/Sync URL).
   - *Data types to mark Optional*:
     - **App activity / Diagnostics** -> *Optional*, *Encrypted in transit (HTTPS)*, *User can request deletion*, Purpose: *Analytics / App functionality* (covers Opt-In Telemetry).
     - **Purchase history** -> Collected via Google Play Billing library (*App functionality*).
3. **Permissions Justification (if prompted)**:
   - `POST_NOTIFICATIONS`: Local preventive purge & empty-tray alerts.
   - Camera / Photo picker (`FileProvider`): On-device optical analysis of user-captured nozzle test printouts.
4. **Personal Developer Account Closed Testing Rule**:
   - Note that new personal Google Play developer accounts require a **14-day closed test with at least 12 opted-in testers** before unlocking Production publishing.

---

## 5. Future Recommended Upgrades Before Scale
1. **Passwordless Independent Auth (Zero Google/Social SDK)**: Implement a lightweight `/auth/request-code` + `/auth/verify-code` (6-digit Email OTP / Magic Link) or device Pairing Token so accounts remain 100% independent from Google, Apple, or social OAuth providers (ensuring compatibility with de-Googled Android / F-Droid / sideload builds too).
2. **Production Sync & Telemetry Hosting**: Keep your Dell homelab for staging/self-hosting and deploy a small GDPR-compliant EU VPS / Cloudflare Worker (`https://api.nozzleguardian.app`) for Play Store users' `/telemetry` and `/sync/workspace` traffic.
