"""
Nozzle Health Computer Vision Scanner & Diagnostic Analyzer
Analyzes smartphone photos or scans of the Nozzle Guardian test sheet,
detects missing/interrupted steps in the staircase ladder,
and outputs targeted surgical strike recommendations.
"""

from datetime import datetime, timezone
import io
import logging
from typing import Dict, Any, List, Optional
from PIL import Image

logger = logging.getLogger("nozzle_guardian.scanner")


def analyze_nozzle_sheet(image_bytes: bytes) -> Dict[str, Any]:
    """
    Parses an uploaded image of a Nozzle Guardian inspection printout.
    Performs color-space segmentation (CMYK) and step continuity profiling.
    Returns channel health percentages and identified clogged nozzle steps.
    """
    now_iso = datetime.now(timezone.utc).isoformat()
    try:
        img = Image.open(io.BytesIO(image_bytes))
    except Exception as e:
        logger.error(f"Failed to open image for nozzle analysis: {e}")
        return {
            "success": False,
            "error": f"Invalid image format: {e}",
            "overall_health_pct": 0.0,
            "channels": {},
            "missing_rungs": [],
            "recommended_action": "none",
            "recommended_actions": [],
            "target_channel": None,
            "target_step": None,
            "timestamp": now_iso,
            "message": f"Could not read image: {e}"
        }

    # Normalize orientation and convert to RGB
    img = img.convert("RGB")
    width, height = img.size

    # Focus analysis on the top 35% of the page where the purge stripes & ladder matrix reside
    crop_h = max(200, int(height * 0.35))
    sample_img = img.crop((0, 0, width, crop_h))

    # Scale down for efficient processing if wide
    max_w = 1200
    if width > max_w:
        scale = max_w / float(width)
        sample_img = sample_img.resize((int(width * scale), int(crop_h * scale)), Image.Resampling.BILINEAR)

    sw, sh = sample_img.size
    pixels = sample_img.load()

    # Four columns across the width (Cyan, Magenta, Yellow, Black)
    # Channels occupy 4 horizontal columns
    margin_x = int(sw * 0.06)
    usable_w = sw - 2 * margin_x
    col_w = usable_w // 4

    channels_order = ["cyan", "magenta", "yellow", "black"]
    channels_report: Dict[str, Any] = {}
    all_missing_rungs: List[Dict[str, Any]] = []

    total_healthy_steps = 0
    total_steps = 40
    worst_channel: Optional[str] = None
    worst_step: Optional[int] = None
    lowest_channel_pct = 101.0

    for idx, ch in enumerate(channels_order):
        col_x0 = margin_x + idx * col_w
        col_x1 = col_x0 + col_w

        # Collect colored pixel coordinates within this column
        y_coords: List[int] = []
        for y in range(sh):
            for x in range(col_x0, col_x1, 2):
                r, g, b = pixels[x, y]
                is_match = False
                if ch == "cyan":
                    is_match = (g > 140 and b > 140 and r < 110 and (g + b) > (r * 2.0))
                elif ch == "magenta":
                    is_match = (r > 140 and b > 140 and g < 110 and (r + b) > (g * 2.0))
                elif ch == "yellow":
                    is_match = (r > 130 and g > 130 and b < 100 and (r + g) > (b * 2.0))
                elif ch == "black":
                    is_match = (r < 75 and g < 75 and b < 75)

                if is_match:
                    y_coords.append(y)

        if len(y_coords) < 15:
            # Channel not found or completely missing
            missing_steps = list(range(1, 11))
            ch_health = 0.0
        else:
            # Distinguish purge stripe (dense cluster at top) from the ladder (below stripe)
            # Find the gap between stripe and ladder
            y_coords.sort()
            # Find the start of the ladder: look for a gap in y_coords > 8px
            ladder_y_min = None
            for i in range(len(y_coords) - 1):
                if y_coords[i+1] - y_coords[i] >= 8 and y_coords[i] > int(sh * 0.1):
                    ladder_y_min = y_coords[i+1]
                    break

            if ladder_y_min is None:
                # If no clear gap, use the second half of the detected vertical span
                min_y = min(y_coords)
                max_y = max(y_coords)
                ladder_y_min = min_y + int((max_y - min_y) * 0.4)

            ladder_y_coords = [y for y in y_coords if y >= ladder_y_min]
            if len(ladder_y_coords) < 10:
                missing_steps = list(range(1, 11))
                ch_health = 0.0
            else:
                ly_start = min(ladder_y_coords)
                ly_end = max(ladder_y_coords)
                ladder_h = max(10, ly_end - ly_start)
                step_h = ladder_h / 10.0

                missing_steps = []
                for s in range(10):
                    sy0 = int(ly_start + s * step_h)
                    sy1 = int(ly_start + (s + 1) * step_h)
                    # Count hits in this step bin
                    step_hits = sum(1 for y in ladder_y_coords if sy0 <= y < sy1)
                    if step_hits < 2:
                        missing_steps.append(s + 1)

                healthy_count = 10 - len(missing_steps)
                ch_health = round((healthy_count / 10.0) * 100.0, 1)

        channels_report[ch] = {
            "healthy": len(missing_steps) == 0,
            "health_pct": ch_health,
            "missing_steps": missing_steps
        }

        for ms in missing_steps:
            all_missing_rungs.append({"channel": ch, "step": ms})

        if missing_steps and ch_health < lowest_channel_pct:
            lowest_channel_pct = ch_health
            worst_channel = ch
            worst_step = missing_steps[0]

        total_healthy_steps += (10 - len(missing_steps))

    overall_pct = round((total_healthy_steps / float(total_steps)) * 100.0, 1)

    rec_action = "none"
    rec_actions_list: List[str] = []
    if worst_channel and worst_step:
        rec_action = "surgical_purge"
        rec_actions_list.append(f"surgical_purge_{worst_channel}_s{worst_step}")
    if overall_pct < 40.0:
        rec_action = "full_purge"
        rec_actions_list.append("full_purge_cmyk")

    return {
        "success": True,
        "overall_health_pct": overall_pct,
        "channels": channels_report,
        "missing_rungs": all_missing_rungs,
        "recommended_action": rec_action,
        "recommended_actions": rec_actions_list,
        "target_channel": worst_channel,
        "target_step": worst_step,
        "timestamp": now_iso,
        "message": (
            f"Tutti i canali sono puliti ed efficienti ({overall_pct}% integrità). Nessun ugello ostruito."
            if rec_action == "none"
            else f"Rilevato gradino mancante in {worst_channel.upper()} ugello #{worst_step}. Consigliato spurgo acustico mirato."
        )
    }
