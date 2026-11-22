from src.config import Settings

def test_default_config():
    settings = Settings()
    assert settings.printer.port == 631
    assert settings.printer.inactivity_threshold_days == 5
    assert settings.printer.auto_purge_enabled is False
    assert settings.pattern.dpi == 300
    assert settings.api_key is None

def test_env_override(monkeypatch):
    monkeypatch.setenv("PRINTER_HOST", "10.0.0.42")
    monkeypatch.setenv("PRINTER_PORT", "8631")
    monkeypatch.setenv("INACTIVITY_DAYS", "7")
    monkeypatch.setenv("AUTO_PURGE_ENABLED", "true")
    monkeypatch.setenv("API_KEY", "secret-key-123")
    
    loaded = Settings.load(config_path="nonexistent_config.yaml")
    assert loaded.printer.host == "10.0.0.42"
    assert loaded.printer.port == 8631
    assert loaded.printer.inactivity_threshold_days == 7
    assert loaded.printer.auto_purge_enabled is True
    assert loaded.api_key == "secret-key-123"
