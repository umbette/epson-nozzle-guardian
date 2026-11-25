"""
Optical Nozzle Check Sheet Scanner (Lighting-Adaptive & Multi-Mode)
===================================================================
Analyzes smartphone photos or desktop scans of Nozzle Guardian test sheets
(both 4-channel CMYK/CMY sheets and monochrome K-only sheets):
  - Normalizes EXIF rotation automatically.
  - Estimates illuminated paper white (wr, wg, wb) and strips dark desk/table
    backgrounds outside the printed sheet.
  - Classifies subtractive C/M/Y/K inks relative to local paper white so warm or
    cool indoor lighting never washes out Yellow or Cyan detection.
  - Auto-detects K-only vs CMY vs CMYK sheets and evaluates only active channels.
"""

from datetime import datetime, timezone
import io
import logging
from typing import Any, Dict, List, Optional, Tuple
from PIL import Image, ImageOps

logger = logging.getLogger("nozzle_guardian.scanner")

CHANNELS_ORDER = ["cyan", "magenta", "yellow", "black"]


def _estimate_paper_white(pixels: Any, sw: int, sh: int) -> Tuple[float, float, float]:
    """Samples the central crop to estimate illuminated white paper RGB (85th-95th pct)."""
    samples: List[Tuple[float, int, int, int]] = []
    step_x = max(1, sw // 48)
    step_y = max(1, sh // 48)
    for y in range(int(sh * 0.08), sh, step_y):
        for x in range(int(sw * 0.12), int(sw * 0.88), step_x):
            r, g, b = pixels[x, y]
            lum = (r + g + b) / 3.0
            samples.append((lum, r, g, b))
    if not samples:
        return 255.0, 255.0, 255.0
    samples.sort(key=lambda t: t[0])
    start = int(len(samples) * 0.78)
    end = max(start + 1, int(len(samples) * 0.96))
    bright = samples[start:end]
    wr = sum(t[1] for t in bright) / len(bright)
    wg = sum(t[2] for t in bright) / len(bright)
    wb = sum(t[3] for t in bright) / len(bright)
    return max(80.0, wr), max(80.0, wg), max(80.0, wb)


def _find_paper_bounds(pixels: Any, sw: int, sh: int, lum_white: float) -> Tuple[int, int, int]:
    """Trims dark non-paper background rows at top and dark margins on sides."""
    paper_thresh = lum_white * 0.65
    cx0, cx1 = int(sw * 0.25), max(int(sw * 0.25) + 4, int(sw * 0.75))
    stride_x = max(2, (cx1 - cx0) // 40)

    paper_top = 0
    streak = 0
    for y in range(sh):
        bright_cnt = 0
        total_cnt = 0
        for x in range(cx0, cx1, stride_x):
            r, g, b = pixels[x, y]
            total_cnt += 1
            if (r + g + b) / 3.0 >= paper_thresh:
                bright_cnt += 1
        if total_cnt and (bright_cnt / total_cnt) >= 0.65:
            streak += 1
            if streak >= 4:
                paper_top = max(0, y - 3)
                break
        else:
            streak = 0

    probe_y0 = min(sh - 1, paper_top + 4)
    probe_y1 = min(sh, paper_top + max(20, int((sh - paper_top) * 0.55)))
    stride_y = max(2, (probe_y1 - probe_y0) // 25)

    def _is_paper_col(x: int) -> bool:
        cnt = 0
        tot = 0
        for y in range(probe_y0, probe_y1, stride_y):
            r, g, b = pixels[x, y]
            tot += 1
            if (r + g + b) / 3.0 >= paper_thresh:
                cnt += 1
        return bool(tot and (cnt / tot) >= 0.55)

    paper_x0 = 0
    while paper_x0 < int(sw * 0.35) and not _is_paper_col(paper_x0):
        paper_x0 += 2
    paper_x1 = sw - 1
    while paper_x1 > int(sw * 0.65) and not _is_paper_col(paper_x1):
        paper_x1 -= 2

    if paper_x1 - paper_x0 < int(sw * 0.4):
        paper_x0, paper_x1 = 0, sw - 1
    return paper_top, paper_x0, paper_x1 + 1


def _classify_pixel(r: int, g: int, b: int, wr: float, wg: float, wb: float) -> Optional[str]:
    """Maps an illuminated pixel to 'cyan', 'magenta', 'yellow', or 'black' relative to paper white."""
    nr = r / wr
    ng = g / wg
    nb = b / wb

    max_c = max(nr, ng, nb)
    min_c = min(nr, ng, nb)
    chroma = max_c - min_c
    lum_ratio = (nr + ng + nb) / 3.0

    if (min(ng, nb) - nr) > 0.16 and nr < 0.78 and min(ng, nb) > 0.52:
        return "cyan"
    if (min(nr, nb) - ng) > 0.16 and ng < 0.78 and min(nr, nb) > 0.50:
        return "magenta"
    if (min(nr, ng) - nb) > 0.14 and nb < 0.80 and min(nr, ng) > 0.60:
        return "yellow"
    if lum_ratio < 0.76 and chroma < 0.18:
        return "black"
    return None


def _row_matches(
    pixels: Any,
    x0: int,
    x1: int,
    y: int,
    wr: float,
    wg: float,
    wb: float,
    target_ch: Optional[str] = None,
    step_x: int = 2,
) -> int:
    hits = 0
    for x in range(x0, x1, step_x):
        r, g, b = pixels[x, y]
        cls = _classify_pixel(r, g, b, wr, wg, wb)
        if target_ch is None:
            if cls is not None:
                hits += 1
        elif cls == target_ch:
            hits += 1
    return hits


def _locate_ladder_band(
    pixels: Any,
    x0: int,
    x1: int,
    y_start: int,
    y_end: int,
    wr: float,
    wg: float,
    wb: float,
) -> Optional[Tuple[int, int, int, int]]:
    """
    Separates top solid purge bars from the staircase ladder below.
    Returns (ladder_y0, ladder_y1, active_x0, active_x1) or None if blank.
    """
    step_x = max(1, (x1 - x0) // 220)
    samples_per_row = max(1, (x1 - x0) // step_x)
    row_density: List[float] = []
    for y in range(y_start, y_end):
        hits = _row_matches(pixels, x0, x1, y, wr, wg, wb, target_ch=None, step_x=step_x)
        row_density.append(hits / float(samples_per_row))

    dense_rows = [y_start + i for i, d in enumerate(row_density) if d >= 0.32]
    bar_cluster: List[int] = []
    if dense_rows:
        run: List[int] = []
        clusters: List[List[int]] = []
        for yr in dense_rows:
            if not run or yr - run[-1] <= max(14, int((y_end - y_start) * 0.03)):
                run.append(yr)
            else:
                clusters.append(run)
                run = [yr]
        if run:
            clusters.append(run)
        for cl in clusters:
            if len(cl) >= 4:
                bar_cluster = cl
                break
        if not bar_cluster:
            bar_cluster = clusters[0]

    active_x0, active_x1 = x0, x1
    if bar_cluster:
        last_bar_y = max(bar_cluster)
        col_hits = []
        span_step = max(2, (x1 - x0) // 250)
        sample_ys = bar_cluster[:: max(1, len(bar_cluster) // 8)]
        for x in range(x0, x1, span_step):
            if any(_classify_pixel(*pixels[x, by], wr, wg, wb) is not None for by in sample_ys):
                col_hits.append(x)
        if len(col_hits) >= 10:
            active_x0 = col_hits[0]
            active_x1 = min(x1, col_hits[-1] + span_step)
        search_from = last_bar_y + max(3, int((y_end - y_start) * 0.015))
    else:
        search_from = y_start

    segments: List[List[int]] = []
    cur_seg: List[int] = []
    gap_tolerance = max(6, int((y_end - y_start) * 0.015))
    for y in range(search_from, y_end):
        d = row_density[y - y_start]
        if d >= 0.012:
            if not cur_seg or (y - cur_seg[-1]) <= gap_tolerance:
                cur_seg.append(y)
            else:
                segments.append(cur_seg)
                cur_seg = [y]
    if cur_seg:
        segments.append(cur_seg)

    significant = [seg for seg in segments if (seg[-1] - seg[0]) >= max(16, int((y_end - y_start) * 0.045))]
    chosen = significant[0] if significant else (max(segments, key=lambda s: s[-1] - s[0]) if segments else [])
    if len(chosen) < 6:
        return None
    return chosen[0], chosen[-1] + 1, active_x0, active_x1


def _evaluate_ladder_steps(
    pixels: Any,
    x0: int,
    x1: int,
    ly0: int,
    ly1: int,
    ch: str,
    wr: float,
    wg: float,
    wb: float,
) -> Dict[str, Any]:
    span_h = max(10, ly1 - ly0)
    col_w = max(4, x1 - x0)
    ix0 = x0 + max(1, int(col_w * 0.03))
    ix1 = max(ix0 + 2, x1 - max(1, int(col_w * 0.03)))
    step_x = 1 if (ix1 - ix0) < 260 else 2
    step_h = span_h / 10.0
    min_band_hits = max(2, int((ix1 - ix0) / step_x * 0.025))

    missing_steps: List[int] = []
    for s in range(10):
        band_top = ly0 + s * step_h
        band_bot = ly0 + (s + 1) * step_h
        sy0 = int(round(band_top + step_h * 0.18))
        sy1 = max(sy0 + 1, int(round(band_bot - step_h * 0.18)))
        band_hits = 0
        for y in range(sy0, min(ly1, sy1)):
            band_hits += _row_matches(pixels, ix0, ix1, y, wr, wg, wb, target_ch=ch, step_x=step_x)
        if band_hits < min_band_hits:
            missing_steps.append(s + 1)

    ch_health = round(((10 - len(missing_steps)) / 10.0) * 100.0, 1)
    return {"present": True, "healthy": len(missing_steps) == 0, "health_pct": ch_health, "missing_steps": missing_steps}


def analyze_nozzle_sheet(image_bytes: bytes) -> Dict[str, Any]:
    """
    Parses a smartphone photo or desktop scanner image of a Nozzle Guardian printout.
    Handles EXIF rotation, room lighting variations, background margins, and K-only vs CMYK sheets.
    """
    now_iso = datetime.now(timezone.utc).isoformat()
    try:
        raw_img = Image.open(io.BytesIO(image_bytes))
        img = ImageOps.exif_transpose(raw_img).convert("RGB")
    except Exception as e:
        logger.error(f"Failed to open image for nozzle analysis: {e}")
        return {
            "success": False,
            "error": f"Invalid image format: {e}",
            "overall_health_pct": 0.0,
            "sheet_mode": "unknown",
            "channels": {},
            "missing_rungs": [],
            "recommended_action": "none",
            "recommended_actions": [],
            "target_channel": None,
            "target_step": None,
            "timestamp": now_iso,
            "message": f"Impossibile leggere l'immagine: {e}",
        }

    orig_w, orig_h = img.size
    crop_h = max(240, int(orig_h * 0.48))
    sample_img = img.crop((0, 0, orig_w, min(orig_h, crop_h)))

    if sample_img.width > 1400:
        scale = 1400.0 / float(sample_img.width)
        sample_img = sample_img.resize(
            (1400, max(1, int(sample_img.height * scale))),
            Image.Resampling.BILINEAR,
        )

    sw, sh = sample_img.size
    pixels = sample_img.load()

    wr, wg, wb = _estimate_paper_white(pixels, sw, sh)
    lum_white = (wr + wg + wb) / 3.0
    paper_top, paper_x0, paper_x1 = _find_paper_bounds(pixels, sw, sh, lum_white)

    band = _locate_ladder_band(pixels, paper_x0, paper_x1, paper_top, sh, wr, wg, wb)
    if band is None:
        return {
            "success": False,
            "error": "No nozzle check pattern found",
            "overall_health_pct": 0.0,
            "sheet_mode": "unknown",
            "channels": {},
            "missing_rungs": [],
            "recommended_action": "none",
            "recommended_actions": [],
            "target_channel": None,
            "target_step": None,
            "timestamp": now_iso,
            "message": "Nessun pattern Nozzle Guardian rilevato nella fascia superiore del foglio.",
        }

    ly0, ly1, active_x0, active_x1 = band

    top_band_counts: Dict[str, int] = {ch: 0 for ch in CHANNELS_ORDER}
    scan_step_x = max(1, (active_x1 - active_x0) // 180)
    scan_step_y = max(1, (ly1 - paper_top) // 90)
    for y in range(paper_top, ly1, scan_step_y):
        for x in range(active_x0, active_x1, scan_step_x):
            cls = _classify_pixel(*pixels[x, y], wr, wg, wb)
            if cls is not None:
                top_band_counts[cls] += 1

    has_cmy = any(top_band_counts[c] >= 12 for c in ("cyan", "magenta", "yellow"))
    has_k = top_band_counts["black"] >= 12

    channels_report: Dict[str, Any] = {}
    active_channels: List[str] = []

    if not has_cmy and has_k:
        sheet_mode = "k"
        active_channels = ["black"]
        active_w = active_x1 - active_x0
        ladder_x0 = active_x0 + int(active_w * 0.09)
        ladder_x1 = active_x1 - int(active_w * 0.09)
        channels_report["black"] = _evaluate_ladder_steps(pixels, ladder_x0, ladder_x1, ly0, ly1, "black", wr, wg, wb)
        for ch in ("cyan", "magenta", "yellow"):
            channels_report[ch] = {"present": False, "healthy": None, "health_pct": None, "missing_steps": []}
    else:
        sheet_mode = "cmyk" if has_k else "cmy"
        matrix_channels = ["cyan", "magenta", "yellow", "black"] if has_k else ["cyan", "magenta", "yellow"]
        active_channels = list(matrix_channels)
        active_w = active_x1 - active_x0
        mat_x0 = active_x0
        col_w = max(4, (active_x1 - mat_x0) // len(matrix_channels))
        for idx, ch in enumerate(matrix_channels):
            cx0 = mat_x0 + idx * col_w
            cx1 = active_x1 if idx == len(matrix_channels) - 1 else (cx0 + col_w)
            res = _evaluate_ladder_steps(pixels, cx0, cx1, ly0, ly1, ch, wr, wg, wb)
            if idx == 0 or res["health_pct"] < 100.0:
                shift = int(active_w * 0.03)
                sc_w = max(4, (active_x1 - (active_x0 + shift)) // len(matrix_channels))
                alt_x0 = active_x0 + shift + idx * sc_w
                alt_x1 = active_x1 if idx == len(matrix_channels) - 1 else (alt_x0 + sc_w)
                alt = _evaluate_ladder_steps(pixels, alt_x0, alt_x1, ly0, ly1, ch, wr, wg, wb)
                if alt["health_pct"] > res["health_pct"]:
                    res = alt
            channels_report[ch] = res
        if not has_k:
            channels_report["black"] = {"present": False, "healthy": None, "health_pct": None, "missing_steps": []}

    all_missing_rungs: List[Dict[str, Any]] = []
    total_healthy_steps = 0
    total_possible_steps = max(10, len(active_channels) * 10)
    worst_channel: Optional[str] = None
    worst_step: Optional[int] = None
    lowest_channel_pct = 101.0

    for ch in active_channels:
        ch_res = channels_report[ch]
        missing_steps = ch_res["missing_steps"]
        for ms in missing_steps:
            all_missing_rungs.append({"channel": ch, "step": ms})
        if missing_steps and ch_res["health_pct"] < lowest_channel_pct:
            lowest_channel_pct = ch_res["health_pct"]
            worst_channel = ch
            worst_step = missing_steps[0]
        total_healthy_steps += 10 - len(missing_steps)

    overall_pct = round((total_healthy_steps / float(total_possible_steps)) * 100.0, 1)

    rec_action = "none"
    rec_actions_list: List[str] = []
    if worst_channel and worst_step:
        rec_action = "surgical_purge"
        rec_actions_list.append(f"surgical_purge_{worst_channel}_s{worst_step}")
    if overall_pct < 40.0:
        rec_action = "full_purge"
        rec_actions_list.append(f"full_purge_{sheet_mode}")

    if rec_action == "none":
        mode_desc = "solo Nero (K)" if sheet_mode == "k" else ("CMY" if sheet_mode == "cmy" else "CMYK")
        msg = f"Foglio {mode_desc}: ugelli integri e puliti ({overall_pct}%). Nessuna ostruzione rilevata."
    else:
        msg = f"Rilevato gradino mancante in {worst_channel.upper()} ugello #{worst_step}. Consigliato spurgo mirato."

    return {
        "success": True,
        "overall_health_pct": overall_pct,
        "sheet_mode": sheet_mode,
        "channels": channels_report,
        "missing_rungs": all_missing_rungs,
        "recommended_action": rec_action,
        "recommended_actions": rec_actions_list,
        "target_channel": worst_channel,
        "target_step": worst_step,
        "timestamp": now_iso,
        "message": msg,
    }
