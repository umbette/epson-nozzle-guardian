"""
SHT40 / SHT4x High-Precision Digital Humidity & Temperature Sensor Driver
MicroPython / CircuitPython driver for Sensirion SHT40 over I2C.
"""

import time
from machine import I2C

SHT40_I2C_ADDR = 0x44
CMD_MEASURE_HIGH_PRECISION = 0xFD


class SHT40:
    def __init__(self, i2c: I2C, addr: int = SHT40_I2C_ADDR):
        self.i2c = i2c
        self.addr = addr

    def read_measurements(self):
        """
        Triggers a high-precision measurement.
        Returns: (temperature_celsius, relative_humidity_pct)
        """
        # Send measure command
        self.i2c.writeto(self.addr, bytes([CMD_MEASURE_HIGH_PRECISION]))
        time.sleep_ms(10)  # High precision measurement takes ~8.2ms

        data = self.i2c.readfrom(self.addr, 6)
        if len(data) < 6:
            raise RuntimeError("Incomplete read from SHT40 sensor")

        # Temperature word & checksum
        t_raw = (data[0] << 8) | data[1]
        temp_c = -45.0 + 175.0 * (t_raw / 65535.0)

        # Humidity word & checksum
        rh_raw = (data[3] << 8) | data[4]
        rh_pct = -6.0 + 125.0 * (rh_raw / 65535.0)
        rh_pct = max(0.0, min(100.0, rh_pct))

        return round(temp_c, 2), round(rh_pct, 2)
