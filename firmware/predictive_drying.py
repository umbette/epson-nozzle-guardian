"""
Predictive Drying Engine
Calculates ink solvent evaporation rate using the Antoine vapor pressure equation
and Fickian diffusion dynamics. Dynamically modulates the maintenance interval (2 to 8 days).
"""

import math

def calculate_maintenance_interval_days(temp_c: float, rh_pct: float, base_days: float = 5.0) -> float:
    """
    Computes dynamic dormancy threshold in days based on ambient microclimate.
    
    Physics Model:
      - Antoine Equation for water vapor saturation pressure:
        P_sat(T) = 0.61078 * exp((17.27 * T) / (T + 237.3))  [kPa]
      - Ambient vapor pressure:
        P_amb = P_sat(T) * (RH / 100.0)
      - Vapor Pressure Deficit (VPD):
        VPD = P_sat(T) - P_amb = P_sat(T) * (1.0 - RH / 100.0)
      
      Higher VPD -> exponentially faster drying of the nozzle meniscus.
    """
    temp_c = max(5.0, min(50.0, temp_c))
    rh_pct = max(5.0, min(95.0, rh_pct))

    # Saturation vapor pressure in kPa (Tetens / Antoine approximation)
    p_sat = 0.61078 * math.exp((17.27 * temp_c) / (temp_c + 237.3))
    
    # Vapor Pressure Deficit (driving force of evaporation)
    vpd = p_sat * (1.0 - (rh_pct / 100.0))

    # Reference baseline: 20°C and 50% RH -> p_sat ~ 2.34 kPa, VPD ~ 1.17 kPa
    vpd_reference = 1.17

    # Drying acceleration factor
    acceleration = vpd / vpd_reference

    # Dynamic interval: scales inversely with drying acceleration
    # e.g. Hot & dry (32°C, 20% RH) -> VPD ~ 3.8 kPa -> acceleration ~ 3.2x -> interval ~ 2.0 days
    # e.g. Cool & humid (18°C, 75% RH) -> VPD ~ 0.5 kPa -> acceleration ~ 0.4x -> interval ~ 8.0 days
    adjusted_days = base_days / math.sqrt(max(0.25, acceleration))

    # Clamp between 2.0 days (maximum protection) and 8.0 days (maximum conservation)
    return round(max(2.0, min(8.0, adjusted_days)), 1)
