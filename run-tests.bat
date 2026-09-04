@echo off
REM ------------------------------------------------------------------
REM PixelPlayer - run JVM unit tests on Windows.
REM
REM These are the local unit tests in app/src/test: they run on the
REM dev machine's JVM, so no emulator or connected device is required.
REM
REM Usage (double-click, or run from a terminal in the repo root):
REM
REM   run-tests.bat                  run every unit test
REM   run-tests.bat ImportedHistory  run only tests matching *ImportedHistory*
REM   run-tests.bat --summary-only   reprint the summary of the last run
REM
REM After the Gradle run it calls test_summary.py, which splits failures
REM into "known baseline (already red on master)" and "NEW" so a real
REM regression can never hide behind the pre-existing red tests.
REM ------------------------------------------------------------------
setlocal enabledelayedexpansion

cd /d "%~dp0"

set "RC=0"
set "ARG=%~1"

if /i "%ARG%"=="--summary-only" goto summary

echo ============================================================
echo  PixelPlayer unit tests  (JVM only, no device required)
echo ============================================================
echo.

if defined ARG (
    echo Filter: *%ARG%*
    echo.
    call gradlew.bat :app:testDebugUnitTest --tests "*%ARG%*"
) else (
    call gradlew.bat :app:testDebugUnitTest
)
set "RC=%ERRORLEVEL%"

:summary
echo.
echo -----------------------------------------------------------
echo  Test summary
echo -----------------------------------------------------------

set "PY="
where python >nul 2>&1 && set "PY=python"
if not defined PY where python3 >nul 2>&1 && set "PY=python3"
if not defined PY where py >nul 2>&1 && set "PY=py"

set "NEW_FAILED=0"
if defined PY (
    "%PY%" test_summary.py
    set "NEW_FAILED=!ERRORLEVEL!"
) else (
    echo python not found on PATH - skipping summary.
    echo HTML report:
    echo   app\build\reports\tests\testDebugUnitTest\index.html
)

echo.
if not "%RC%"=="0" (
    echo Gradle exited with code %RC%
    echo ^(non-zero is expected while the known baseline failures exist^)
)
if "%NEW_FAILED%"=="0" (
    echo RESULT: no new failures - any red tests above are the known baseline.
) else (
    echo RESULT: NEW FAILURES DETECTED - see the "NEW failures" list above.
)
echo Full report: app\build\reports\tests\testDebugUnitTest\index.html

REM Exit code reflects regressions only, so the script stays green while the
REM pre-existing baseline failures are still red.
endlocal & exit /b %NEW_FAILED%
