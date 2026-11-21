# Nozzle Guardian - Hardware Compatibility Matrix

This document tracks tested and community-verified printer models.

| Manufacturer | Family | Model | Connection | Protocol | Status | Notes |
|:---|:---|:---|:---|:---|:---|:---|
| **Epson** | EcoTank | **ET-2814** | Wi-Fi / Ethernet | IPPS (Port 631, TLS) | ✅ Verified | Tested with driverless PWG-Raster (`iso_a4_210x297mm`) |
| **Epson** | EcoTank | **ET-2810 / ET-2820** | Wi-Fi | IPPS (Port 631) | 🧪 Community Tested | Shares ET-2814 print engine |
| **Epson** | EcoTank | **ET-2850 / ET-4800** | Wi-Fi / Ethernet | IPPS (Port 631) | 🧪 Community Tested | Recommended default settings |
| **Canon** | MegaTank | **G3000 / G6000 Series** | Wi-Fi | IPP / AirPrint | 🧪 Community Tested | Native IPP Everywhere standard |
| **HP** | Smart Tank | **5000 / 7000 Series** | Wi-Fi | IPP / PWG-Raster | 🧪 Community Tested | PCLm / PWG raster compatible |

## Submitting a New Model

If you have tested another EcoTank, MegaTank, Smart Tank, or IPP-compatible inkjet printer:
1. Open an issue using the [Hardware Compatibility Report](https://github.com/umbette/nozzle-guardian/issues/new?template=hardware_report.yml) template.
2. Our automated triage bot will process your submission and open a pull request to include your findings here!
