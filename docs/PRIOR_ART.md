# Nozzle Guardian™ — Defensive Publication & Technical Prior Art Record

**Document ID:** NG-PRIOR-ART-2026-01  
**Author & Original Inventor:** Umberto (`umbette`)  
**First Public Release Lineage:** `https://github.com/umbette/nozzle-guardian` (`2026`)  
**Legal Purpose:** Defensive Publication establishing undeniable state of the art under **Article 54 EPC (European Patent Convention)** and **35 U.S.C. § 102 (U.S. America Invents Act)**, and asserting copyright + hardware commercial protections (`AGPL-3.0-only` + `PolyForm-Noncommercial-1.0.0`).

---

## 1. Protected Technical Innovations Disclosed & Timestamped Hereby

This public disclosure permanently bars any third party from obtaining valid patent exclusivity over any standalone software agent, Home Assistant add-on, desktop utility, OR dedicated **USB/Wi-Fi Hardware Dongle** implementing the following technical architectures authored by **Umberto (`umbette`)**:

1. **Channel-Decoupled Driverless Printhead Hydration (`K` vs `CMY` vs `total_pages`):**
   - Independent dormancy tracking of Monochrome (`K`) and Color (`Cyan/Magenta/Yellow`) printhead nozzle banks via IPP attributes (`RFC 8011`), embedded printer web/XML endpoints (`Epson EWS`, `HP ProductUsageDyn XML`, `Canon`, `Brother`), and `SNMPv1 Printer-MIB` (`prtMarkerLifeCount` + `prtMarkerSupplies`), with automatic fallback when split counters are unavailable.
   - Prevents unnecessary color purging on B/W-heavy printers and eliminates wasteful peristaltic "Power Cleaning" cycles.

2. **Minimal Full-Width Micro-Stripe & Reusable Paper Engineering:**
   - Top-margin (`~3 mm`) page-spanning horizontal purges consuming `< 0.01 ml` per pass across 100% of carriage nozzles, leaving >85% of the sheet blank for scrap-paper reuse.
   - Multi-media (`iso_a4_210x297mm` and `na_letter_8.5x11in`) pure-Python **PWG-Raster (`PWG 5102.4`)**, `PDF`, `PNG`, and `JPEG` generation with zero CUPS/Ghostscript binary dependencies.

3. **Hardware Safety Interlocks (Laser Guard, `media-empty`, Thermal BubbleJet Clamping):**
   - Automatic classification of **Laser/LED** toner printers (aborting ink purges), `media-empty` tray detection inhibiting automated jobs before carriage dry-fire/thrashing, and actuation physics adaptation (**Thermal BubbleJet** low-duty anti-kogation clamping vs **Micro-Piezo** high-frequency bursts).

4. **Dual-Vision Numbered Staircase Sheet & Lighting-Adaptive EXIF Optical Scanner (`src/scanner.py`):**
   - Numbered `1–10` per-channel nozzle ladder blocks framed by corner calibration marks, scanned by any desktop scanner or smartphone camera with EXIF orientation auto-transposition, ambient white-point calibration, missing-rung coordinate localization, and targeted single-nozzle surgical bursts (`execute_surgical_purge`).

5. **Autonomous USB/Wi-Fi Predictive Drying Smart Dongle (`firmware/` & `hardware/`):**
   - Low-power microcontroller appliance (`ESP32-C3` / `ESP32-S3`) integrated with an ambient microclimate sensor (`SHT40` / `AHT20`), dynamically computing meniscus evaporation acceleration via the **Antoine Vapor Pressure & Vapor Pressure Deficit (VPD)** equations (`firmware/predictive_drying.py`), performing `mDNS` IPP wake-up probes (`firmware/ipp_client.py`), captive-portal provisioning, and enclosed in parametric housing (`hardware/enclosure.scad`).

---

## 2. Commercial Hardware & Licensing Boundary

While the core Python daemon, CLI, Home Assistant Add-on, and desktop utilities are licensed under **GNU AGPLv3** (`LICENSE`), the **Smart Dongle Firmware (`firmware/`), Mechanical Enclosure CAD (`hardware/enclosure.scad`), and Hardware Appliance Packaging** are strictly governed by **`PolyForm Noncommercial License 1.0.0`** (`LICENSE-COMMERCIAL-HARDWARE`).

> **No third party may manufacture, bundle, pre-flash, or sell hardware dongles, router appliances, or commercial services based on this architecture or codebase without an executed Commercial OEM License from Umberto (`umbette`).**
