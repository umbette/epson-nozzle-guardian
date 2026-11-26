# Nozzle Guardian — Home Assistant Add-on

Mantiene idratate le testine inkjet (**Epson EcoTank, Canon MegaTank, HP Smart Tank, Brother Inkvestment**) senza CUPS né driver binari, direttamente da Home Assistant con pannello in **Ingress**.

## Funzionalità integrate
- Motore puro Python (`RFC 8011` IPP/IPPS, `PWG-Raster 5102.4`, PDF/PNG/JPEG, SNMPv1 `Printer-MIB`) con negoziazione automatica DPI (`300`/`360`/`600`), spazio colore (`sgray_8` / `srgb_8`) e fallback automatico del formato.
- Supporto sia **4-Tank CMYK** (`ET-2810`, `ET-4810`, ecc.) sia **6-Tank Claria Photo (`C/M/Y/BK/PB/GY`)** (`ET-8550` / `L8180`) con modalità `cmyk6` / `pb_gy` e instradamento carta IPP `media-col` (`stationery` / `photographic-glossy` / `photographic-matte`).
- Discovery automatica mDNS (`_ipps._tcp` / `_ipp._tcp`) + `ipp-usb` (`host_network: true`).
- Sicurezza hardware automatica: blocco su stampanti **Laser/Toner**, pausa automatica su `media-empty`, riduzione impulsi su testine termiche **BubbleJet**.
- Scanner ottico da fotocamera/browser + matrice chirurgica `1–10` per singolo ugello (inclusi `PB` e `GY`).
- Notifiche automatiche via Home Assistant (`SUPERVISOR_TOKEN`), **ntfy** (iOS/Android) o Webhook.
