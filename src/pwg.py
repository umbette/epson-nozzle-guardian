"""
Pure-Python PWG-Raster (PWG 5102.4) & Multi-Format Raster Encoder
=================================================================
Generates driverless image/pwg-raster, application/pdf, image/png, and image/jpeg
streams directly from Pillow images without requiring CUPS, cupsfilter, or Ghostscript.
"""

import io
import struct
from typing import Optional, Tuple
from PIL import Image

PWG_MAGIC = b"RaS2"
PWG_HEADER_SIZE = 1796
CUPS_CSPACE_SGRAY = 18
CUPS_CSPACE_SRGB = 19

MEDIA_DIMENSIONS_MM = {
    "iso_a4_210x297mm": (210.0, 297.0, 595, 842),
    "na_letter_8.5x11in": (215.9, 279.4, 612, 792),
}

PWG_MEDIA_TYPE_MAP = {
    "auto": "Stationery",
    "stationery": "Stationery",
    "photographic": "Photographic",
    "photographic-glossy": "PhotographicGlossy",
    "photographic-matte": "PhotographicMatte",
    "photographic-semi-gloss": "PhotographicSemiGloss",
}


def _pwg_media_type_name(media_type: Optional[str]) -> str:
    if not media_type:
        return "Stationery"
    key = media_type.lower().strip()
    return PWG_MEDIA_TYPE_MAP.get(key, media_type.strip()[:63] or "Stationery")


def _pack_cstr(text: str, length: int = 64) -> bytes:
    raw = text.encode("ascii", errors="ignore")[: length - 1]
    return raw.ljust(length, b"\x00")


def _packbits_encode_row(row_bytes: bytes, bpp_bytes: int) -> bytes:
    """
    Encodes a single scanline using PWG 5102.4 PackBits pixel-run compression:
    - 0x00..0x7F (n): repeat the following single pixel (bpp_bytes) n + 1 times
    - 0x81..0xFF (257 - count): copy `count` literal pixels verbatim
    """
    n_pixels = len(row_bytes) // bpp_bytes
    if n_pixels == 0:
        return b""

    out = bytearray()
    i = 0
    while i < n_pixels:
        px = row_bytes[i * bpp_bytes : (i + 1) * bpp_bytes]
        run_len = 1
        while (
            run_len < 128
            and (i + run_len) < n_pixels
            and row_bytes[(i + run_len) * bpp_bytes : (i + run_len + 1) * bpp_bytes] == px
        ):
            run_len += 1

        if run_len >= 2 or i + 1 >= n_pixels:
            out.append(run_len - 1)
            out.extend(px)
            i += run_len
            continue

        lit_start = i
        lit_count = 1
        i += 1
        while lit_count < 128 and i < n_pixels:
            cur_px = row_bytes[i * bpp_bytes : (i + 1) * bpp_bytes]
            if (
                i + 1 < n_pixels
                and row_bytes[(i + 1) * bpp_bytes : (i + 2) * bpp_bytes] == cur_px
            ):
                break
            lit_count += 1
            i += 1

        if lit_count == 1:
            out.append(0)
            out.extend(px)
        else:
            out.append(257 - lit_count)
            out.extend(row_bytes[lit_start * bpp_bytes : (lit_start + lit_count) * bpp_bytes])

    return bytes(out)


def encode_pwg_raster(
    img: Image.Image,
    dpi: int = 300,
    media_name: str = "iso_a4_210x297mm",
    media_type: str = "Stationery",
) -> bytes:
    """
    Encodes a Pillow Image ('L' grayscale or 'RGB') into a spec-compliant
    big-endian PWG-Raster ('RaS2') byte stream.
    """
    if img.mode == "L":
        work = img
        bpp = 8
        num_colors = 1
        cspace = CUPS_CSPACE_SGRAY
    else:
        work = img.convert("RGB")
        bpp = 24
        num_colors = 3
        cspace = CUPS_CSPACE_SRGB

    width, height = work.size
    bpp_bytes = bpp // 8
    bytes_per_line = width * bpp_bytes
    _, _, pt_w, pt_h = MEDIA_DIMENSIONS_MM.get(media_name, MEDIA_DIMENSIONS_MM["iso_a4_210x297mm"])

    header = bytearray(PWG_HEADER_SIZE)
    header[0:64] = _pack_cstr("PwgRaster", 64)
    header[128:192] = _pack_cstr(_pwg_media_type_name(media_type), 64)

    struct.pack_into(">2I", header, 276, dpi, dpi)
    struct.pack_into(">I", header, 340, 1)
    struct.pack_into(">2I", header, 352, pt_w, pt_h)
    struct.pack_into(">2I", header, 372, width, height)
    struct.pack_into(">I", header, 384, 8)
    struct.pack_into(">I", header, 388, bpp)
    struct.pack_into(">I", header, 392, bytes_per_line)
    struct.pack_into(">I", header, 396, 0)
    struct.pack_into(">I", header, 400, cspace)
    struct.pack_into(">I", header, 420, num_colors)

    struct.pack_into(">I", header, 452, 1)
    struct.pack_into(">I", header, 456, 1)
    struct.pack_into(">I", header, 460, 1)
    struct.pack_into(">I", header, 480, 0x00FFFFFF)

    header[1732:1796] = _pack_cstr(media_name, 64)

    out = bytearray(PWG_MAGIC)
    out.extend(header)

    raw_pixels = work.tobytes()
    y = 0
    while y < height:
        row = raw_pixels[y * bytes_per_line : (y + 1) * bytes_per_line]
        repeat = 1
        while (
            repeat < 256
            and (y + repeat) < height
            and raw_pixels[(y + repeat) * bytes_per_line : (y + repeat + 1) * bytes_per_line] == row
        ):
            repeat += 1

        out.append(repeat - 1)
        out.extend(_packbits_encode_row(row, bpp_bytes))
        y += repeat

    return bytes(out)


def decode_pwg_raster(data: bytes) -> Tuple[dict, bytes]:
    """
    Decodes a single-page PWG-Raster stream back into header metadata and raw pixel bytes.
    """
    if not data.startswith(PWG_MAGIC) or len(data) < 4 + PWG_HEADER_SIZE:
        raise ValueError("Invalid PWG-Raster stream")

    hdr = data[4 : 4 + PWG_HEADER_SIZE]
    dpi_x, dpi_y = struct.unpack_from(">2I", hdr, 276)
    width, height = struct.unpack_from(">2I", hdr, 372)
    bpp = struct.unpack_from(">I", hdr, 388)[0]
    bytes_per_line = struct.unpack_from(">I", hdr, 392)[0]
    cspace = struct.unpack_from(">I", hdr, 400)[0]
    bpp_bytes = bpp // 8

    meta = {
        "media_class": hdr[0:64].split(b"\x00", 1)[0].decode("ascii", errors="ignore"),
        "media_type": hdr[128:192].split(b"\x00", 1)[0].decode("ascii", errors="ignore"),
        "media_size_name": hdr[1732:1796].split(b"\x00", 1)[0].decode("ascii", errors="ignore"),
        "dpi": (dpi_x, dpi_y),
        "width": width,
        "height": height,
        "bpp": bpp,
        "bytes_per_line": bytes_per_line,
        "colorspace": cspace,
    }

    pos = 4 + PWG_HEADER_SIZE
    rows_out = bytearray()
    decoded_rows = 0
    while decoded_rows < height and pos < len(data):
        repeat = data[pos] + 1
        pos += 1
        row_buf = bytearray()
        while len(row_buf) < bytes_per_line and pos < len(data):
            code = data[pos]
            pos += 1
            if code <= 127:
                count = code + 1
                px = data[pos : pos + bpp_bytes]
                pos += bpp_bytes
                row_buf.extend(px * count)
            elif code >= 129:
                count = 257 - code
                chunk = data[pos : pos + count * bpp_bytes]
                pos += count * bpp_bytes
                row_buf.extend(chunk)
        for _ in range(min(repeat, height - decoded_rows)):
            rows_out.extend(row_buf[:bytes_per_line])
            decoded_rows += 1

    return meta, bytes(rows_out)


def encode_image_for_format(
    img: Image.Image,
    mime_format: str,
    dpi: int = 300,
    media_name: str = "iso_a4_210x297mm",
    media_type: str = "Stationery",
) -> bytes:
    """Encodes a Pillow Image into the negotiated IPP document format."""
    fmt = mime_format.lower().strip()
    if fmt == "image/pwg-raster":
        return encode_pwg_raster(img, dpi=dpi, media_name=media_name, media_type=media_type)

    buf = io.BytesIO()
    if fmt == "application/pdf":
        pdf_img = img if img.mode in ("L", "RGB") else img.convert("RGB")
        pdf_img.save(buf, format="PDF", resolution=float(dpi))
        return buf.getvalue()
    if fmt == "image/jpeg":
        jpg_img = img.convert("RGB")
        jpg_img.save(buf, format="JPEG", quality=95, dpi=(dpi, dpi))
        return buf.getvalue()

    img.save(buf, format="PNG", dpi=(dpi, dpi))
    return buf.getvalue()
