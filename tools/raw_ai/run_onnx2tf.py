"""Stable file-based entry point for the pinned onnx2tf environment.

Invoke this file with normal onnx2tf arguments. A real file avoids Windows
PowerShell quoting and Unicode-current-directory issues.
"""

from onnx2tf import main


if __name__ == "__main__":
    main()
