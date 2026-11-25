from unittest.mock import AsyncMock, patch
from PIL import Image

from src.printer import PrinterStatus, PurgeJobResult, PurgeJobStatus
from src.cli import main

def test_cli_systemd_output(capsys):
    with patch("sys.argv", ["nozzle-guardian", "systemd"]):
        main()
    captured = capsys.readouterr().out
    assert "nozzle-guardian.service" in captured
    assert "nozzle-guardian.timer" in captured
    assert "OnCalendar=" in captured

def test_cli_scan_command(tmp_path, capsys):
    # 1. Blank image must be safely rejected (not misdiagnosed as 100% clogged)
    blank_img = Image.new("RGB", (200, 200), color=(255, 255, 255))
    blank_file = tmp_path / "blank_sheet.png"
    blank_img.save(blank_file)

    with patch("sys.argv", ["nozzle-guardian", "scan", str(blank_file)]):
        main()
    captured = capsys.readouterr().out
    assert "NOZZLE HEALTH REPORT" in captured
    assert "Overall Printhead Health: 0.0%" in captured

    with patch("sys.argv", ["nozzle-guardian", "scan", str(blank_file), "--json"]):
        main()
    captured_blank_json = capsys.readouterr().out
    assert '"success": false' in captured_blank_json.lower()

    # 2. Valid Nozzle Guardian sheet must succeed with high health score
    from src.config import Settings
    from src.generator import PatternGenerator
    sheet_path = PatternGenerator(Settings(), assets_dir=tmp_path).generate_cmyk_pattern_png()
    with patch("sys.argv", ["nozzle-guardian", "scan", str(sheet_path), "--json"]):
        main()
    captured_json = capsys.readouterr().out
    assert '"success": true' in captured_json.lower()

def test_cli_check_command(capsys):
    status_mock = PrinterStatus(
        online=True,
        monochrome_pages=150,
        color_pages=45,
        total_pages=195,
        state="idle"
    )
    with patch("src.cli.EpsonPrinterClient.get_status", new_callable=AsyncMock) as mock_get, \
         patch("sys.argv", ["nozzle-guardian", "check"]):
        mock_get.return_value = status_mock
        main()
        
    captured = capsys.readouterr().out
    assert "Printer ONLINE: IDLE" in captured
    assert "Monochrome Pages Printed: 150" in captured
    assert "Color Pages Printed:      45" in captured

def test_cli_purge_command(capsys, tmp_path):
    job_ok = PurgeJobResult(status=PurgeJobStatus.ACCEPTED, message="Job queued")
    dummy_pwg = tmp_path / "purge_cmy.pwg"
    dummy_pwg.write_bytes(b"dummy")

    with patch("src.cli.EpsonPrinterClient.execute_purge_job", new_callable=AsyncMock) as mock_exec, \
         patch("src.cli.PatternGenerator.get_pwg_path", return_value=dummy_pwg), \
         patch("sys.argv", ["nozzle-guardian", "purge", "cmy"]):
        mock_exec.return_value = job_ok
        main()

    captured = capsys.readouterr().out
    assert "Purge job 'CMY' successfully dispatched" in captured

def test_cli_surgical_command(capsys, tmp_path):
    job_ok = PurgeJobResult(status=PurgeJobStatus.ACCEPTED, message="Surgical pass accepted")
    dummy_pwg = tmp_path / "purge_surg.pwg"
    dummy_pwg.write_bytes(b"dummy")

    with patch("src.cli.EpsonPrinterClient.execute_purge_job", new_callable=AsyncMock) as mock_exec, \
         patch("src.cli.PatternGenerator.compile_surgical_pwg", return_value=dummy_pwg), \
         patch("sys.argv", ["nozzle-guardian", "surgical", "--channel", "magenta", "--step", "5", "--bursts", "3"]):
        mock_exec.return_value = job_ok
        main()

    captured = capsys.readouterr().out
    assert "Surgical acoustic resuscitation pass dispatched for MAGENTA #5" in captured
