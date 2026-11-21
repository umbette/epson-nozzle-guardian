# Contributing to Epson Nozzle Guardian

We welcome community contributions, hardware reports, and feature proposals!

## How You Can Contribute

1. **Hardware Reports:** Test the daemon against your specific EcoTank model and submit a report.
2. **Feature Requests & Bug Reports:** Open an issue if you encounter protocol quirks or have ideas for new purges.
3. **Automated Fixes:** Comment `/auto-fix` or `/implement` on approved issues to trigger our GitHub Actions bot, which generates automated Pull Requests with the proposed patches.

## Development Setup

1. Clone repository:
   ```bash
   git clone https://github.com/your-username/epson-nozzle-guardian.git
   cd epson-nozzle-guardian
   ```
2. Install dependencies:
   ```bash
   pip install -r requirements.txt -r requirements-dev.txt
   ```
3. Run tests:
   ```bash
   pytest tests -v
   ruff check src tests
   ```

## Guidelines

- All PRs must pass the automated CI pipeline.
- Keep the daemon lightweight and driverless (avoid introducing CUPS dependencies).
