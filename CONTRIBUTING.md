# Contributing to Epson Nozzle Guardian

We welcome contributions from the community!

## How to Contribute

1. **Test Other Printer Models:** If you use an EcoTank model not listed in `COMPATIBILITY.md`, open an issue or submit a PR adding your model and test notes.
2. **Bug Fixes & Improvements:** Fork the repo, create a feature branch, and submit a Pull Request.
3. **Manual Review:** All PRs are reviewed and tested manually to maintain zero-trust security.

## Development & Testing

```bash
git clone https://github.com/umbette/epson-nozzle-guardian.git
cd epson-nozzle-guardian
pip install -r requirements.txt -r requirements-dev.txt
pytest tests -v
ruff check src tests
```

