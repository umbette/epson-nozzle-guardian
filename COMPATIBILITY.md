# Nozzle Guardian - Hardware Compatibility Matrix

This document tracks tested and community-verified printer models.

| Manufacturer | Family | Model | Connection | Protocol | Status | Notes |
|:---|:---|:---|:---|:---|:---|:---|
| **Epson** | EcoTank | **ET-2814** | Wi-Fi / Ethernet | IPPS (Port 631, TLS) | ✅ Verified | Tested with driverless PWG-Raster (`iso_a4_210x297mm`) |
| **Epson** | EcoTank | **ET-2810 / ET-2820** | Wi-Fi | IPPS (Port 631) | 🧪 Community Tested | Shares ET-2814 print engine |
| **Epson** | EcoTank | **ET-2850 / ET-4800 / ET-4810** | Wi-Fi / Ethernet | IPPS (Port 631) | 🧪 Community Tested | Auto-negotiates PWG DPI (`300`/`360`), `srgb_8` fallback & PDF/JPEG fallback |
| **Epson** | EcoTank Photo (6-Tank) | **ET-8550 / ET-8500 / L8180** | Wi-Fi / Ethernet | IPPS (Port 631) | 🧪 Experimental (`cmyk6` / `pb_gy`) | Supports 6-tank (`C/M/Y/BK/PB/GY`) pattern & `media-type` (`photographic-glossy`/`matte`) routing for Photo Black & Gray |
| **Canon** | MegaTank | **G3000 / G6000 Series** | Wi-Fi | IPP / AirPrint | 🧪 Community Tested | Native IPP Everywhere standard (`thermal` safety limits) |
| **Canon** | MegaTank | **PIXMA G2770 / G2010 / G1010** | **USB Only (No Wi-Fi)** | OS Print Spooler (`local://<queue>`) | ✅ Supported (v1.2) | Plug via USB into PC/Mac/Pi running Nozzle Guardian Desktop + Canon driver |
| **HP** | Smart Tank | **5000 / 7000 Series** | Wi-Fi | IPP / PWG-Raster | 🧪 Community Tested | PCLm / PDF / PWG raster compatible (`thermal` safety limits) |

## Submitting a New Model

If you have tested another EcoTank, MegaTank, Smart Tank, or IPP-compatible inkjet printer:
1. Open an issue using the [Hardware Compatibility Report](https://github.com/umbette/nozzle-guardian/issues/new?template=hardware_report.yml) template.
2. Our automated triage bot will process your submission and open a pull request to include your findings here!
