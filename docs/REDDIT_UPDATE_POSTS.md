# Reddit Update Drafts — Nozzle Guardian (v1.4.0)

Target thread: `https://www.reddit.com/r/printers/s/egVLtkWnQP`

---

## 1. Edit / Update Section to Append to Your Existing `r/printers` Post (English — Recommended)

```markdown
---

### 🚀 Big Update (v1.4.0): Native Android App, Optical Nozzle Scanner, 6-Tank Photo & Canon/HP Support!

Thanks everyone for all the feedback on this thread! Since the first release a few days ago, the project has grown a lot based on what you asked for:

* **Standalone 1-Click Apps (Zero Python / Zero Server needed for casual users):**
  * **Android App (APK / Play Store AAB):** Automatically discovers Wi-Fi printers via mDNS, groups them by **Location / Home** (with optional Wi-Fi SSID matching), monitors ink levels & B/W vs Color page counters, runs background checks, and sends micro-purges straight from your phone.
  * **Windows / macOS / Linux Desktop Tray App:** 1-click bundle with system tray icon, auto-start toggle, and support for **USB-only printers** (like Canon G2770 / PIXMA).
  * **Docker & Home Assistant Add-on:** Still first-class for homelabbers.
* **Optical Camera Scanner + Surgical Single-Nozzle Unclogger:** Snap a photo of the micro-purge sheet and Nozzle Guardian analyzes all color bars (steps `1–10`) to pinpoint missing nozzles and fire targeted surgical passes on only the clogged step.
* **6-Tank Photo Printers (`ET-8550` / `L8180`):** Dedicated `CMYK6` and `PB+GY` modes plus IPP `media-type` hints (`photographic-glossy` / `photographic-matte`) so Photo Black and Gray nozzles don't dry out either.
* **Thermal BubbleJet Safety (Canon MegaTank & HP Smart Tank):** Respects thermal printhead duty cycles and safe spacing (`0.34 mm`), alongside Acoustic Micro-Piezo support for Epson/Brother.
* **Automatic Laser/Toner Safeguard:** If a Laser printer is detected, ink purges are blocked automatically while still showing toner & page counters.
* **Multi-Language & Privacy-First:** System-language support (`EN / IT / ES / FR / DE`), zero cloud required, zero telemetry by default (optional Opt-In anonymized diagnostics only).

**GitHub & Downloads (Desktop + Android APK):** https://github.com/umbette/nozzle-guardian
```

---

## 2. Follow-Up Comment for the Thread (`r/printers`)

```markdown
Quick update for anyone following the project: we just pushed **v1.4.0**!

Major additions since the OP:
1. **Native Android App (`v1.4.0`)** — Zero server needed for Wi-Fi printers, multi-printer + Home/Office location groups, per-model idle threshold, mDNS discovery, ink/page counters, and dark-mode Material 3 UI.
2. **Optical Nozzle Scanner & Surgical 1–10 Matrix** — Snap a test sheet photo to target only clogged steps instead of flushing the whole head.
3. **6-Tank Photo (`C/M/Y/BK/PB/GY`) + Thermal BubbleJet Safety (`Canon`/`HP`) + Laser auto-block**.
4. **Standalone Desktop Apps (`.exe` / `.app` / Linux)** for USB-only printers (`Canon G2770` etc.), plus updated PWA & Home Assistant add-on.

Grab the latest Desktop binaries or Android APK under GitHub Releases: https://github.com/umbette/nozzle-guardian/releases/latest
```

---

## 3. Standalone Update Post (for `r/printers` / `r/AndroidApps` / `r/selfhosted`)

**Title:**  
`Nozzle Guardian v1.4.0 — Open-source driverless Inkjet anti-clogging (0.006 ml micro-purges) now has a native Android app, Optical Camera Scanner, 6-Tank Photo & Canon/HP support`

**Body:**  
Use section #1 above together with the test-pattern comparison render (`docs/assets/nozzle-pattern-render.png`) and comparison diagram (`docs/assets/comparison-diagram.svg`) as post images.
