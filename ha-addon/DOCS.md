# Nozzle Guardian — Home Assistant Add-on

Mantiene idratate le testine inkjet (**Epson EcoTank, Canon MegaTank, HP Smart Tank, Brother Inkvestment**) senza CUPS né driver binari, direttamente da Home Assistant con pannello in **Ingress**.

## Funzionalità integrate
- Motore puro Python (`RFC 8011` IPP/IPPS, `PWG-Raster`, PDF/PNG/JPEG, SNMPv1 `Printer-MIB`).
- Discovery automatica mDNS (`_ipps._tcp` / `_ipp._tcp`) + `ipp-usb` (`host_network: true`).
- Sicurezza hardware automatica: blocco su stampanti **Laser/Toner**, pausa automatica su `media-empty`, riduzione impulsi su testine termiche **BubbleJet**.
- Scanner ottico da fotocamera/browser + matrice chirurgica `1–10` per singolo ugello.
- Notifiche automatiche via Home Assistant (`SUPERVISOR_TOKEN`), **ntfy** (iOS/Android) o Webhook.
