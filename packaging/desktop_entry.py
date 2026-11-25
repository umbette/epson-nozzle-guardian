"""PyInstaller entrypoint wrapper so `src` resolves as a proper package."""

from src.cli import main
from src.desktop import _configure_frozen_stdio

if __name__ == "__main__":
    _configure_frozen_stdio()
    main()
