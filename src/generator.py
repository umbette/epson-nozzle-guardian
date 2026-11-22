import logging
import subprocess
from pathlib import Path
from typing import Any
from PIL import Image, ImageDraw, ImageFont

from .config import settings

logger = logging.getLogger("epson_guardian.generator")

# Pure RGB primaries
COLOR_CYAN = (0, 255, 255)
COLOR_MAGENTA = (255, 0, 255)
COLOR_YELLOW = (255, 255, 0)
COLOR_BLACK = (0, 0, 0)
COLOR_WHITE = (255, 255, 255)


class PatternGenerator:
    def __init__(self, *args, assets_dir: str = "assets", **kwargs):
        if args and isinstance(args[0], (str, Path)):
            assets_dir = str(args[0])
        try:
            p = Path(assets_dir)
            p.mkdir(parents=True, exist_ok=True)
            self.assets_dir = p
        except (PermissionError, OSError):
            self.assets_dir = Path("assets").resolve()
            self.assets_dir.mkdir(parents=True, exist_ok=True)
        self.dpi = settings.pattern.dpi

        # Standard A4 size in mm: 210 x 297
        self.width_px = int(210.0 / 25.4 * self.dpi)
        self.height_px = int(297.0 / 25.4 * self.dpi)

        self.top_margin_px = int(settings.pattern.top_margin_mm / 25.4 * self.dpi)
        self.side_margin_px = int(settings.pattern.margin_side_mm / 25.4 * self.dpi)
        self.stripe_height_px = int(settings.pattern.stripe_height_mm / 25.4 * self.dpi)
        self.stripe_spacing_px = int(settings.pattern.stripe_spacing_mm / 25.4 * self.dpi)

        self.stripe_width_px = self.width_px - (2 * self.side_margin_px)

    def _draw_ladder_pattern(self, draw: ImageDraw.ImageDraw, x0: int, y0: int, col_w: int, col_h: int, color: Any) -> None:
        """
        Draws diagnostic stepped staircase ladders and micro-comb alignment ticks
        for nozzle health verification.
        """
        # Hairline bounding references (2px)
        draw.line([(x0, y0), (x0 + col_w, y0)], fill=color, width=2)
        draw.line([(x0, y0 + col_h), (x0 + col_w, y0 + col_h)], fill=color, width=2)

        num_steps = 10
        pitch = (col_h - 12) / num_steps
        step_len = 16
        step_dx = 12
        ladder_w = num_steps * step_dx + step_len

        curr_x = x0 + 10
        while curr_x + ladder_w <= x0 + col_w - 10:
            for i in range(num_steps):
                sy = int(y0 + 6 + i * pitch)
                sx = int(curr_x + i * step_dx)
                # Horizontal nozzle step (2px line for crisp printhead verification)
                draw.line([(sx, sy), (sx + step_len, sy)], fill=color, width=2)
                # Diagonal riser connecting steps
                if i < num_steps - 1:
                    next_sy = int(y0 + 6 + (i + 1) * pitch)
                    draw.line([(sx + step_len, sy), (curr_x + (i + 1) * step_dx, next_sy)], fill=color, width=1)

            # Comb alignment ticks to test nozzle straightness
            for k in range(8):
                tx = curr_x + ladder_w - 20 + k * 4
                draw.line([(tx, y0 + col_h - 12), (tx, y0 + col_h - 2)], fill=color, width=1)

            curr_x += ladder_w + 24

    def generate_png(self, mode: str) -> Path:
        """
        Generates an A4 PNG image with minimal horizontal stripes AND a nozzle check ladder pattern.
        Modes:
          - 'cmy': RGB image with Cyan, Magenta, Yellow (Black untouched)
          - 'k': 8-bit Grayscale image with Black only (Colors untouched, prevents composite black)
          - 'cmyk': RGB image with all 4 channels
        """
        out_path = self.assets_dir / f"purge_{mode}.png"
        font = ImageFont.load_default()

        if mode == "k":
            # Grayscale image (mode 'L') to enforce single-channel pure black
            img = Image.new("L", (self.width_px, self.height_px), 255)
            draw = ImageDraw.Draw(img)

            # 1. Purge Stripe
            y0 = self.top_margin_px
            y1 = y0 + self.stripe_height_px
            x0 = self.side_margin_px
            x1 = x0 + self.stripe_width_px
            draw.rectangle([x0, y0, x1, y1], fill=0) # 0 is black in grayscale

            # 2. Nozzle Inspection Pattern
            curr_y = y1 + int(3.5 / 25.4 * self.dpi)
            draw.text((x0, curr_y), "BLACK NOZZLE INSPECTION (100% PURE K - CHECK FOR GAPS)", fill=0, font=font)
            curr_y += 16

            col_h_px = int(9.0 / 25.4 * self.dpi)
            self._draw_ladder_pattern(draw, x0, curr_y, self.stripe_width_px, col_h_px, color=0)

            img.save(out_path, format="PNG")
            logger.info(f"Generated Grayscale PNG pattern: {out_path} (1 black stripe + nozzle check)")
            return out_path

        # RGB for CMY and CMYK
        img = Image.new("RGB", (self.width_px, self.height_px), COLOR_WHITE)
        draw = ImageDraw.Draw(img)

        colors_to_draw = []
        if mode == "cmy":
            colors_to_draw = [("CYAN", COLOR_CYAN), ("MAGENTA", COLOR_MAGENTA), ("YELLOW", COLOR_YELLOW)]
        elif mode == "cmyk":
            colors_to_draw = [("CYAN", COLOR_CYAN), ("MAGENTA", COLOR_MAGENTA), ("YELLOW", COLOR_YELLOW), ("BLACK", COLOR_BLACK)]
        else:
            raise ValueError(f"Unknown mode: {mode}")

        current_y = self.top_margin_px
        x0 = self.side_margin_px
        x1 = x0 + self.stripe_width_px

        # 1. Purge Stripes
        for name, color in colors_to_draw:
            y0 = current_y
            y1 = y0 + self.stripe_height_px
            draw.rectangle([x0, y0, x1, y1], fill=color)
            current_y = y1 + self.stripe_spacing_px

        # 2. Nozzle Inspection Pattern
        current_y += int(3.0 / 25.4 * self.dpi)
        draw.text((x0, current_y), "NOZZLE HEALTH INSPECTION (CHECK FOR GAPS OR DEFLECTION)", fill=(80, 80, 80), font=font)
        current_y += 18

        num_cols = len(colors_to_draw)
        gap_px = int(5.0 / 25.4 * self.dpi)
        col_w_px = int((self.stripe_width_px - (num_cols - 1) * gap_px) / num_cols)
        col_h_px = int(9.0 / 25.4 * self.dpi)

        for i, (name, color) in enumerate(colors_to_draw):
            col_x = x0 + i * (col_w_px + gap_px)
            # Text label: make yellow readable
            text_color = (180, 180, 0) if name == "YELLOW" else color
            draw.text((col_x, current_y), f"[{name}]", fill=text_color, font=font)
            self._draw_ladder_pattern(draw, col_x, current_y + 16, col_w_px, col_h_px, color=color)

        img.save(out_path, format="PNG")
        logger.info(f"Generated RGB PNG pattern: {out_path} ({len(colors_to_draw)} stripes + {num_cols} nozzle check columns)")
        return out_path

    def compile_pwg(self, mode: str) -> Path:
        """
        Compiles the PNG pattern into a native PWG-Raster file using cupsfilter.
        Explicitly specifies A4 page size and color model to prevent 'Paper size mismatch'
        and prevent composite black ink mixing.
        """
        png_path = self.generate_png(mode)
        pwg_path = self.assets_dir / f"purge_{mode}.pwg"

        # Explicit options for cupsfilter:
        # - PageSize=A4 and media=iso_a4_210x297mm to prevent paper mismatch prompts
        # - ColorModel=Gray for 'k' to output 1-channel sgray_8
        # - ColorModel=RGB for 'cmy' and 'cmyk'
        color_model = "Gray" if mode == "k" else "RGB"
        
        ppd_path = Path("/app/data/printer.ppd")
        if not ppd_path.exists():
            ppd_path = Path(__file__).resolve().parent.parent / "data" / "printer.ppd"

        cmd = ["cupsfilter"]
        if ppd_path.exists():
            cmd.extend(["-p", str(ppd_path)])
        cmd.extend([
            "-m", "image/pwg-raster",
            "-o", "PageSize=A4",
            "-o", "media=iso_a4_210x297mm",
            "-o", f"ColorModel={color_model}",
            str(png_path)
        ])

        try:
            with open(pwg_path, "wb") as f_out:
                subprocess.run(cmd, stdout=f_out, stderr=subprocess.PIPE, check=True)
            logger.info(f"Successfully compiled PWG-Raster ({color_model}): {pwg_path} ({pwg_path.stat().st_size} bytes)")
            return pwg_path
        except (subprocess.SubprocessError, FileNotFoundError) as e:
            logger.warning(f"cupsfilter compilation failed ({e}). Keeping PNG pattern: {png_path}")
            return png_path

    def ensure_all_patterns(self) -> None:
        """Ensures all 3 purge patterns (k, cmy, cmyk) are pre-rendered."""
        for mode in ("k", "cmy", "cmyk"):
            self.compile_pwg(mode)

    def get_pwg_path(self, mode: str) -> Path:
        return self.assets_dir / f"purge_{mode}.pwg"

    def get_png_path(self, mode: str) -> Path:
        return self.assets_dir / f"purge_{mode}.png"

    def generate_k_pattern_png(self) -> Path:
        return self.generate_png("k")

    def generate_cmy_pattern_png(self) -> Path:
        return self.generate_png("cmy")

    def generate_cmyk_pattern_png(self) -> Path:
        return self.generate_png("cmyk")


# Backwards compatibility alias
PurgePatternGenerator = PatternGenerator

