# Security Policy

## Local-First Execution Model

**Nozzle Guardian™** runs 100% inside your LAN, Home Server, Desktop PC, or Android/Linux node with **zero external telemetry**:
- Printer IP addresses, SNMP/IPP counters, and photographed nozzle test sheets stay strictly on your local storage.
- The FastAPI API/Web Dashboard uses **fail-closed `X-API-Key` authentication** (auto-generating a `0600` 32-byte key in `data/api_key.secret` unless explicitly configured or accessed via Home Assistant Supervisor Ingress).

## Reporting a Vulnerability

Please **do not** disclose exploitable vulnerabilities publicly before remediation. Use GitHub Private Vulnerability Reporting under `github.com/umbette/nozzle-guardian/security`.
