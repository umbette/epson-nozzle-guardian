"""
Media-Aware & Thermal-Safe Dual-Vision Pattern Generator
========================================================
Generates minimal-ink horizontal micro-stripes + numbered 1-10 nozzle inspection
ladders legible both to the human eye (bold headers, numeric rulers, high-contrast
rungs) and to smartphone cameras (corner calibration marks, clean white separation
gaps, isolated pure-channel CMYK columns).
Compiles directly into PWG-Raster, PDF, PNG, or JPEG in pure Python (zero CUPS).
Enforces Thermal BubbleJet safety limits on Canon/HP heads while enabling full
acoustic resonance bursts on Piezo heads (Epson/Brother).
"""

import logging
from pathlib import Path
from typing import Any, List, Optional, Tuple
from PIL import Image, ImageDraw, ImageFont

from .config import settings
from .pwg import MEDIA_DIMENSIONS_MM, encode_image_for_format

logger = logging.getLogger("nozzle_guardian.generator")

COLOR_CYAN = (0, 255, 255)
COLOR_MAGENTA = (255, 0, 255)
COLOR_YELLOW = (255, 255, 0)
COLOR_BLACK = (0, 0, 0)
COLOR_WHITE = (255, 255, 255)
COLOR_TEXT_NEUTRAL = (55, 55, 55)


class PatternGenerator:
    def __init__(
        self,
        *args,
        assets_dir: Optional[str] = None,
        dpi: Optional[int] = None,
        media_name: Optional[str] = None,
        **kwargs,
    ):
        cfg = settings
        for arg in args:
            if hasattr(arg, "pattern"):
                cfg = arg
            elif isinstance(arg, (str, Path)) and assets_dir is None:
                assets_dir = str(arg)

        base = Path(assets_dir) if assets_dir else (Path(__file__).resolve().parent.parent / "assets")
        base.mkdir(parents=True, exist_ok=True)
        self.assets_dir = base

        self.dpi = int(dpi if dpi is not None else cfg.pattern.dpi)
        pref_media = media_name or getattr(getattr(cfg, "printer", None), "media_size", "iso_a4_210x297mm")
        self.media_name = pref_media if pref_media in MEDIA_DIMENSIONS_MM else "iso_a4_210x297mm"

        width_mm, height_mm, _, _ = MEDIA_DIMENSIONS_MM[self.media_name]
        self.width_px = int(width_mm / 25.4 * self.dpi)
        self.height_px = int(height_mm / 25.4 * self.dpi)
        self.top_margin_px = self._mm(float(getattr(cfg.pattern, "top_margin_mm", 10.0)))
        self.side_margin_px = self._mm(float(getattr(cfg.pattern, "margin_side_mm", 14.0)))
        self.stripe_height_px = self._mm(float(getattr(cfg.pattern, "stripe_height_mm", 3.0)))
        self.stripe_spacing_px = self._mm(max(1.2, float(getattr(cfg.pattern, "stripe_spacing_mm", 1.4))))
        self.stripe_width_px = self.width_px - (2 * self.side_margin_px)

    def _mm(self, val_mm: float) -> int:
        return max(1, int(round(val_mm / 25.4 * self.dpi)))

    def _font(self, size_mm: float, bold: bool = False) -> ImageFont.ImageFont:
        px = max(11, self._mm(size_mm))
        candidates = (
            [
                "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            ]
            if bold
            else [
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            ]
        )
        for path in candidates:
            if Path(path).exists():
                try:
                    return ImageFont.truetype(path, px)
                except Exception:
                    pass
        try:
            return ImageFont.load_default(size=px)
        except TypeError:
            return ImageFont.load_default()

    def _draw_fiducial(self, draw: ImageDraw.ImageDraw, x0_box: int, y0_box: int, color: Any) -> int:
        size = max(8, self._mm(2.8))
        pad = max(2, int(round(size * 0.28)))
        draw.rectangle([x0_box, y0_box, x0_box + size, y0_box + size], fill=color)
        bg = 255 if isinstance(color, int) else COLOR_WHITE
        draw.rectangle([x0_box + pad, y0_box + pad, x0_box + size - pad, y0_box + size - pad], fill=bg)
        return size

    def _draw_ruler_labels(
        self,
        draw: ImageDraw.ImageDraw,
        x: int,
        y0: int,
        col_h: int,
        color: Any,
        align_right: bool = False,
    ) -> None:
        font_step = self._font(2.0, bold=True)
        pitch = col_h / 10.0
        for i in range(10):
            sy = int(y0 + (i + 0.5) * pitch)
            label = str(i + 1)
            bbox = draw.textbbox((0, 0), label, font=font_step)
            tw = bbox[2] - bbox[0]
            th = bbox[3] - bbox[1]
            tx = (x - tw) if align_right else x
            ty = int(sy - th / 2 - bbox[1])
            draw.text((tx, ty), label, fill=color, font=font_step)

    def _draw_ladder_pattern(
        self,
        draw: ImageDraw.ImageDraw,
        x0: int,
        y0: int,
        col_w: int,
        col_h: int,
        color: Any,
        diag_label_color: Any = None,
    ) -> None:
        num_steps = 10
        pitch = col_h / float(num_steps)
        rung_w = max(2, self._mm(0.55))
        diag_w = max(1, self._mm(0.22))
        step_len = max(8, self._mm(2.2))
        step_dx = max(6, self._mm(1.4))
        ladder_w = (num_steps - 1) * step_dx + step_len
        block_gap = max(6, self._mm(2.4))
        diag_pad = self._mm(3.6) if diag_label_color is not None else 0
        font_step = self._font(1.9, bold=True) if diag_label_color is not None else None
        lbl_gap = max(2, self._mm(0.8))

        curr_x = x0 + diag_pad
        block_idx = 0
        while curr_x + ladder_w <= x0 + col_w:
            for i in range(num_steps):
                sy = int(y0 + (i + 0.5) * pitch)
                sx = int(curr_x + i * step_dx)
                if block_idx == 0 and diag_label_color is not None and font_step is not None:
                    label = str(i + 1)
                    bbox = draw.textbbox((0, 0), label, font=font_step)
                    tw = bbox[2] - bbox[0]
                    th = bbox[3] - bbox[1]
                    tx = max(x0, sx - tw - lbl_gap)
                    ty = int(sy - th / 2 - bbox[1])
                    draw.text((tx, ty), label, fill=diag_label_color, font=font_step)
                draw.line([(sx, sy), (sx + step_len, sy)], fill=color, width=rung_w)
                if i < num_steps - 1:
                    next_sy = int(y0 + (i + 1.5) * pitch)
                    draw.line([(sx + step_len, sy), (curr_x + (i + 1) * step_dx, next_sy)], fill=color, width=diag_w)
            curr_x += ladder_w + block_gap
            block_idx += 1

        tick = max(3, self._mm(1.2))
        draw.line([(x0 - tick, y0), (x0, y0)], fill=color, width=diag_w)
        draw.line([(x0 - tick, y0 + col_h), (x0, y0 + col_h)], fill=color, width=diag_w)

    def _compute_ladder_y(self, num_stripes: int) -> int:
        bars_h = num_stripes * (self.stripe_height_px + self.stripe_spacing_px)
        return self.top_margin_px + self._mm(6.5) + bars_h + self._mm(5.5)

    def build_purge_image(self, mode: str) -> Image.Image:
        """Builds an in-memory Pillow Image ('L' for 'k', 'RGB' for 'cmy'/'cmyk')."""
        mode_l = mode.lower().strip()
        font_title = self._font(3.0, bold=True)
        font_badge = self._font(2.4, bold=True)
        col_h_px = self._mm(18.0)

        if mode_l == "k":
            img = Image.new("L", (self.width_px, self.height_px), 255)
            draw = ImageDraw.Draw(img)
            x0 = self.side_margin_px
            x1 = x0 + self.stripe_width_px

            y_cursor = self.top_margin_px
            draw.text((x0 + self._mm(5.0), y_cursor), "NOZZLE GUARDIAN — TEST NERO (K) [1-10]", fill=0, font=font_title)
            y_cursor += self._mm(6.5)

            badge_w = self._mm(6.0)
            draw.text((x0, y_cursor + self._mm(0.2)), "K", fill=0, font=font_badge)
            draw.rectangle([x0 + badge_w, y_cursor, x1, y_cursor + self.stripe_height_px], fill=0)

            ladder_y0 = self._compute_ladder_y(1)
            ruler_w = self._mm(7.0)
            fid_sz = max(8, self._mm(2.8))
            self._draw_fiducial(draw, x0, ladder_y0, 0)
            self._draw_fiducial(draw, x1 - fid_sz, ladder_y0, 0)
            self._draw_fiducial(draw, x0, ladder_y0 + col_h_px - fid_sz, 0)
            self._draw_fiducial(draw, x1 - fid_sz, ladder_y0 + col_h_px - fid_sz, 0)
            self._draw_ruler_labels(draw, x0 + self._mm(5.8), ladder_y0, col_h_px, color=0, align_right=True)
            self._draw_ruler_labels(draw, x1 - self._mm(5.8), ladder_y0, col_h_px, color=0, align_right=False)
            self._draw_ladder_pattern(
                draw,
                x0 + ruler_w,
                ladder_y0,
                self.stripe_width_px - (2 * ruler_w),
                col_h_px,
                color=0,
                diag_label_color=0,
            )
            return img

        if mode_l == "cmy":
            colors_to_draw: List[Tuple[str, str, Tuple[int, int, int]]] = [
                ("C", "CYAN", COLOR_CYAN),
                ("M", "MAGENTA", COLOR_MAGENTA),
                ("Y", "YELLOW", COLOR_YELLOW),
            ]
            include_black = False
        elif mode_l == "cmyk":
            colors_to_draw = [
                ("C", "CYAN", COLOR_CYAN),
                ("M", "MAGENTA", COLOR_MAGENTA),
                ("Y", "YELLOW", COLOR_YELLOW),
                ("K", "BLACK", COLOR_BLACK),
            ]
            include_black = True
        else:
            raise ValueError(f"Unknown purge mode: {mode}")

        img = Image.new("RGB", (self.width_px, self.height_px), COLOR_WHITE)
        draw = ImageDraw.Draw(img)
        x0 = self.side_margin_px
        x1 = x0 + self.stripe_width_px
        hdr_color = COLOR_TEXT_NEUTRAL if include_black else COLOR_MAGENTA
        fid_color = COLOR_BLACK if include_black else COLOR_MAGENTA

        y_cursor = self.top_margin_px
        mode_tag = "CMYK" if include_black else "CMY"
        draw.text(
            (x0 + self._mm(5.0), y_cursor),
            f"NOZZLE GUARDIAN — {mode_tag} MATRIX [1-10]",
            fill=hdr_color,
            font=font_title,
        )
        y_cursor += self._mm(6.5)

        badge_w = self._mm(6.0)
        for short_code, _name, color in colors_to_draw:
            y0 = y_cursor
            y1 = y0 + self.stripe_height_px
            badge_fill = COLOR_TEXT_NEUTRAL if (include_black and short_code == "Y") else color
            draw.text((x0, y0 + self._mm(0.2)), short_code, fill=badge_fill, font=font_badge)
            draw.rectangle([x0 + badge_w, y0, x1, y1], fill=color)
            y_cursor = y1 + self.stripe_spacing_px

        ladder_y0 = self._compute_ladder_y(len(colors_to_draw))
        fid_sz = max(8, self._mm(2.8))
        self._draw_fiducial(draw, x0, ladder_y0, fid_color)
        self._draw_fiducial(draw, x1 - fid_sz, ladder_y0, fid_color)
        self._draw_fiducial(draw, x0, ladder_y0 + col_h_px - fid_sz, fid_color)
        self._draw_fiducial(draw, x1 - fid_sz, ladder_y0 + col_h_px - fid_sz, fid_color)

        matrix_x0 = x0 + badge_w
        matrix_w = self.stripe_width_px - badge_w - fid_sz - self._mm(1.5)
        num_cols = len(colors_to_draw)
        gap_px = self._mm(4.0)
        col_w_px = int((matrix_w - (num_cols - 1) * gap_px) / num_cols)

        for i, (short_code, _name, color) in enumerate(colors_to_draw):
            col_x = matrix_x0 + i * (col_w_px + gap_px)
            if short_code == "Y":
                diag_color = COLOR_TEXT_NEUTRAL if include_black else (210, 110, 0)
            else:
                diag_color = color
            self._draw_ladder_pattern(
                draw,
                col_x,
                ladder_y0,
                col_w_px,
                col_h_px,
                color=color,
                diag_label_color=diag_color,
            )

        footer_y = ladder_y0 + col_h_px + self._mm(5.5)
        for i, (short_code, name, color) in enumerate(colors_to_draw):
            col_x = matrix_x0 + i * (col_w_px + gap_px)
            lbl_color = COLOR_TEXT_NEUTRAL if (include_black and short_code == "Y") else (COLOR_BLACK if include_black else color)
            draw.text((col_x, footer_y), f"[{short_code}] {name} 1-10", fill=lbl_color, font=font_badge)

        return img

    def build_surgical_image(
        self,
        channel: str,
        step: int,
        bursts: int = 5,
        head_technology: str = "piezo",
    ) -> Tuple[Image.Image, str, int]:
        """Builds a surgical single-nozzle pass enforcing thermal BubbleJet protection."""
        ch = channel.lower().strip()
        channel_map = {"c": "cyan", "cyan": "cyan", "m": "magenta", "magenta": "magenta", "y": "yellow", "yellow": "yellow", "k": "black", "black": "black"}
        if ch not in channel_map:
            raise ValueError(f"Invalid channel '{channel}'")
        canon = channel_map[ch]

        step = max(1, min(10, int(step)))
        raw_bursts = max(1, min(9, int(bursts)))
        is_thermal = head_technology == "thermal"
        effective_bursts = min(raw_bursts, 3) if is_thermal else raw_bursts
        line_pitch = max(2, self._mm(0.34 if is_thermal else 0.17))

        col_h_px = self._mm(18.0)
        pitch = col_h_px / 10.0
        ladder_y0 = self._compute_ladder_y(1 if canon == "black" else 4)
        target_y = int(ladder_y0 + (step - 0.5) * pitch)

        x0 = self.side_margin_px
        x1 = x0 + self.stripe_width_px
        font_hdr = self._font(2.5, bold=True)

        if canon == "black":
            img = Image.new("L", (self.width_px, self.height_px), 255)
            draw = ImageDraw.Draw(img)
            draw.text((x0, max(10, target_y - self._mm(5.5))), f"[NOZZLE GUARDIAN - SURGICAL BLACK #{step}]", fill=0, font=font_hdr)
            for b in range(-(effective_bursts // 2), (effective_bursts // 2) + 1):
                ly = target_y + b * line_pitch
                draw.line([(x0, ly), (x1, ly)], fill=0, width=max(2, self._mm(0.25)))
            return img, "Gray", effective_bursts

        color_lookup = {"cyan": COLOR_CYAN, "magenta": COLOR_MAGENTA, "yellow": COLOR_YELLOW}
        color = color_lookup[canon]

        img = Image.new("RGB", (self.width_px, self.height_px), COLOR_WHITE)
        draw = ImageDraw.Draw(img)
        draw.text((x0, max(10, target_y - self._mm(5.5))), f"[NOZZLE GUARDIAN - SURGICAL {canon.upper()} #{step}]", fill=color, font=font_hdr)
        for b in range(-(effective_bursts // 2), (effective_bursts // 2) + 1):
            ly = target_y + b * line_pitch
            draw.line([(x0, ly), (x1, ly)], fill=color, width=max(2, self._mm(0.25)))
        return img, "RGB", effective_bursts

    def compile_payload(self, mode: str, doc_format: str = "image/pwg-raster") -> bytes:
        img = self.build_purge_image(mode)
        return encode_image_for_format(img, mime_format=doc_format, dpi=self.dpi, media_name=self.media_name)

    def compile_surgical_payload(
        self,
        channel: str,
        step: int,
        bursts: int = 5,
        head_technology: str = "piezo",
        doc_format: str = "image/pwg-raster",
    ) -> Tuple[bytes, int]:
        img, _model, effective_bursts = self.build_surgical_image(
            channel=channel,
            step=step,
            bursts=bursts,
            head_technology=head_technology,
        )
        payload = encode_image_for_format(img, mime_format=doc_format, dpi=self.dpi, media_name=self.media_name)
        return payload, effective_bursts

    def generate_png(self, mode: str) -> Path:
        out_path = self.assets_dir / f"purge_{mode}.png"
        img = self.build_purge_image(mode)
        img.save(out_path, format="PNG")
        return out_path

    def generate_surgical_purge_png(
        self,
        channel: str,
        step: int,
        bursts: int = 5,
        head_technology: str = "piezo",
    ) -> Tuple[Path, str]:
        img, color_model, _ = self.build_surgical_image(channel, step, bursts, head_technology)
        canon = {"c": "cyan", "cyan": "cyan", "m": "magenta", "magenta": "magenta", "y": "yellow", "yellow": "yellow", "k": "black", "black": "black"}[channel.lower().strip()]
        out_path = self.assets_dir / f"purge_surgical_{canon}_s{int(step)}.png"
        img.save(out_path, format="PNG")
        return out_path, color_model

    def compile_pwg(self, mode: str) -> Path:
        """Pure-Python PWG-Raster file compilation (zero CUPS)."""
        pwg_path = self.assets_dir / f"purge_{mode}.pwg"
        payload = self.compile_payload(mode, doc_format="image/pwg-raster")
        pwg_path.write_bytes(payload)
        return pwg_path

    def compile_surgical_pwg(
        self,
        channel: str,
        step: int,
        bursts: int = 5,
        head_technology: str = "piezo",
    ) -> Path:
        """Pure-Python surgical PWG-Raster file compilation (zero CUPS)."""
        canon = {"c": "cyan", "cyan": "cyan", "m": "magenta", "magenta": "magenta", "y": "yellow", "yellow": "yellow", "k": "black", "black": "black"}[channel.lower().strip()]
        pwg_path = self.assets_dir / f"purge_surgical_{canon}_s{int(step)}.pwg"
        payload, _ = self.compile_surgical_payload(channel, step, bursts=bursts, head_technology=head_technology, doc_format="image/pwg-raster")
        pwg_path.write_bytes(payload)
        return pwg_path

    def ensure_all_patterns(self) -> None:
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


PurgePatternGenerator = PatternGenerator
