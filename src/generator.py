import logging
import subprocess
from pathlib import Path
from PIL import Image, ImageDraw

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
        self.assets_dir = Path(assets_dir)
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

    def generate_png(self, mode: str) -> Path:
        """
        Generates an A4 PNG image with minimal horizontal stripes.
        Modes:
          - 'cmy': RGB image with Cyan, Magenta, Yellow (Black untouched)
          - 'k': 8-bit Grayscale image with Black only (Colors untouched, prevents composite black)
          - 'cmyk': RGB image with all 4 channels
        """
        out_path = self.assets_dir / f"purge_{mode}.png"

        if mode == "k":
            # Grayscale image (mode 'L') to enforce single-channel pure black
            img = Image.new("L", (self.width_px, self.height_px), 255)
            draw = ImageDraw.Draw(img)
            y0 = self.top_margin_px
            y1 = y0 + self.stripe_height_px
            x0 = self.side_margin_px
            x1 = x0 + self.stripe_width_px
            draw.rectangle([x0, y0, x1, y1], fill=0) # 0 is black in grayscale
            img.save(out_path, format="PNG")
            logger.info(f"Generated Grayscale PNG pattern: {out_path} (1 black stripe)")
            return out_path

        # RGB for CMY and CMYK
        img = Image.new("RGB", (self.width_px, self.height_px), COLOR_WHITE)
        draw = ImageDraw.Draw(img)

        colors_to_draw = []
        if mode == "cmy":
            colors_to_draw = [COLOR_CYAN, COLOR_MAGENTA, COLOR_YELLOW]
        elif mode == "cmyk":
            colors_to_draw = [COLOR_CYAN, COLOR_MAGENTA, COLOR_YELLOW, COLOR_BLACK]
        else:
            raise ValueError(f"Unknown mode: {mode}")

        current_y = self.top_margin_px
        x0 = self.side_margin_px
        x1 = x0 + self.stripe_width_px

        for color in colors_to_draw:
            y0 = current_y
            y1 = y0 + self.stripe_height_px
            draw.rectangle([x0, y0, x1, y1], fill=color)
            current_y = y1 + self.stripe_spacing_px

        img.save(out_path, format="PNG")
        logger.info(f"Generated RGB PNG pattern: {out_path} ({len(colors_to_draw)} stripes)")
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
        cmd = [
            "cupsfilter",
            "-m", "image/pwg-raster",
            "-o", "PageSize=A4",
            "-o", "media=iso_a4_210x297mm",
            "-o", f"ColorModel={color_model}",
            str(png_path)
        ]

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

