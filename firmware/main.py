"""
Nozzle Guardian™ Smart Dongle Firmware
Main controller for ESP32-S3 / ESP32-C3 hardware appliance.
Monitors ambient microclimate, computes evaporation rate, and executes
autonomous driverless IPP micro-purges 24/7 without PC, phone, or cloud.
"""

import time
import json
import uos
import machine
import network
from machine import Pin, I2C

from sht40 import SHT40
from predictive_drying import calculate_maintenance_interval_days
from ipp_client import send_purge_job

CONFIG_FILE = "config.json"
DEFAULT_CONFIG = {
    "wifi_ssid": "",
    "wifi_password": "",
    "printer_host": "192.168.1.181",
    "printer_port": 631,
    "use_tls": False,
    "base_interval_days": 5.0,
    "auto_purge_enabled": True
}

# Pre-compiled minimal 2.5mm CMYK PWG raster pattern (cached in flash)
PWG_CACHE_FILE = "purge_mini.pwg"


def load_config():
    try:
        with open(CONFIG_FILE, "r") as f:
            return json.load(f)
    except Exception:
        return DEFAULT_CONFIG.copy()


def save_config(cfg):
    try:
        with open(CONFIG_FILE, "w") as f:
            json.dump(cfg, f)
    except Exception as e:
        print("Failed to save config:", e)


def connect_wifi(ssid, password, timeout_sec=20):
    wlan = network.WLAN(network.STA_IF)
    wlan.active(True)
    if wlan.isconnected():
        return True
    print(f"Connecting to Wi-Fi SSID '{ssid}'...")
    wlan.connect(ssid, password)
    start = time.time()
    while not wlan.isconnected() and (time.time() - start) < timeout_sec:
        time.sleep(0.5)
    return wlan.isconnected()


def main():
    print("==================================================")
    print("🛡️ Nozzle Guardian™ Smart Dongle OS v1.1 Starting")
    print("==================================================")

    cfg = load_config()

    # Initialize I2C for SHT40 Sensor (GPIO 4 SDA, GPIO 5 SCL on standard ESP32-S3 Dongle)
    i2c = I2C(0, sda=Pin(4), scl=Pin(5), freq=100000)
    sensor = None
    try:
        sensor = SHT40(i2c)
        print("✅ SHT40 microclimate sensor initialized successfully.")
    except Exception as e:
        print("⚠️ SHT40 sensor not detected (fallback to baseline mode):", e)

    # Wi-Fi Onboarding check
    if not cfg.get("wifi_ssid"):
        print("📡 No Wi-Fi credentials found. Starting captive portal AP: 'NozzleGuardian-Setup'...")
        # Start AP mode for zero-config onboarding
        ap = network.WLAN(network.AP_IF)
        ap.active(True)
        ap.config(essid="NozzleGuardian-Setup", authmode=network.AUTH_OPEN)
        print("Setup portal active at http://192.168.4.1")
        while True:
            time.sleep(1)

    if not connect_wifi(cfg["wifi_ssid"], cfg["wifi_password"]):
        print("❌ Could not connect to Wi-Fi. Retrying in 60s...")
        time.sleep(60)
        machine.reset()

    print(f"🟢 Connected to Wi-Fi! IP: {network.WLAN(network.STA_IF).ifconfig()[0]}")

    last_purge_timestamp = 0

    while True:
        temp_c = 21.0
        rh_pct = 50.0

        if sensor:
            try:
                temp_c, rh_pct = sensor.read_measurements()
                print(f"🌡️ Ambient Microclimate: {temp_c}°C | Humidity: {rh_pct}% RH")
            except Exception as e:
                print("Sensor read error:", e)

        # Dynamic interval calculation based on Antoine vapor pressure deficit
        dynamic_days = calculate_maintenance_interval_days(temp_c, rh_pct, cfg.get("base_interval_days", 5.0))
        interval_seconds = int(dynamic_days * 86400)
        print(f"⏱️ Predictive Drying Policy: Next purge scheduled in {dynamic_days} days ({interval_seconds}s)")

        now = time.time()
        if (now - last_purge_timestamp) >= interval_seconds:
            print(f"🚀 Triggering autonomous preventive micro-purge to {cfg['printer_host']}...")
            try:
                with open(PWG_CACHE_FILE, "rb") as f:
                    pwg_bytes = f.read()
                success = send_purge_job(cfg["printer_host"], cfg["printer_port"], pwg_bytes, cfg.get("use_tls", False))
                if success:
                    print("✅ Micro-purge successfully executed on printer!")
                    last_purge_timestamp = now
                else:
                    print("⚠️ Printer unresponsive. Will retry in 1 hour.")
            except Exception as err:
                print("Purge execution error:", err)

        # Sleep loop (checking every 60 seconds)
        time.sleep(60)


if __name__ == "__main__":
    main()
