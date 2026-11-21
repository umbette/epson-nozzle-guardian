from pathlib import Path
from src.generator import PurgePatternGenerator
from src.config import Settings

def test_pattern_generator_paths(tmp_path):
    settings = Settings()
    gen = PurgePatternGenerator(settings, assets_dir=tmp_path)
    
    assert gen.get_pwg_path("k") == tmp_path / "purge_k.pwg"
    assert gen.get_pwg_path("cmy") == tmp_path / "purge_cmy.pwg"
    assert gen.get_pwg_path("cmyk") == tmp_path / "purge_cmyk.pwg"

def test_pattern_png_generation(tmp_path):
    settings = Settings()
    gen = PurgePatternGenerator(settings, assets_dir=tmp_path)
    
    # Generate PNG files
    k_png = gen.generate_k_pattern_png()
    cmy_png = gen.generate_cmy_pattern_png()
    cmyk_png = gen.generate_cmyk_pattern_png()
    
    assert k_png.exists()
    assert cmy_png.exists()
    assert cmyk_png.exists()
    assert k_png.stat().st_size > 0
