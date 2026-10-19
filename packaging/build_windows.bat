@echo off
rem Builds Bloom for Windows: dist\Bloom\Bloom.exe and (with Inno Setup) dist\Bloom-Setup.exe
rem Needs: Python 3.12 or 3.13 from python.org (tick "Add python.exe to PATH").
rem Optional: Inno Setup 6 from jrsoftware.org, for the installer.
setlocal
cd /d "%~dp0\.."

where py >nul 2>nul
if errorlevel 1 (
  echo Python was not found. Install Python 3.12 or 3.13 from https://www.python.org/downloads/ and run this again.
  exit /b 1
)

echo [1/3] Creating a build environment...
py -3 -m venv .venv-win || exit /b 1
call .venv-win\Scripts\activate.bat
python -m pip install --upgrade pip >nul
pip install -r requirements.txt pyinstaller || exit /b 1

echo [2/3] Building Bloom.exe...
pyinstaller --noconfirm --distpath dist --workpath build packaging\bloom.spec || exit /b 1

echo [3/3] Building the installer...
set ISCC="%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not exist %ISCC% set ISCC="%LocalAppData%\Programs\Inno Setup 6\ISCC.exe"
if exist %ISCC% (
  %ISCC% packaging\installer.iss || exit /b 1
  echo.
  echo Done: dist\Bloom-Setup.exe
) else (
  echo Inno Setup not found, so no installer was made.
  echo You can still run dist\Bloom\Bloom.exe directly.
)
endlocal
