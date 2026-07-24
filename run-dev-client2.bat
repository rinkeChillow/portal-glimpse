@echo off
setlocal
title Portal Glimpse - Dev Client 2
cd /d "D:\Mod Source Files\portal-glimps-1.21"

echo(
echo   Portal Glimpse - launching the SECOND dev client (Dev2)...
echo   Use this alongside Dev1 for multiplayer: LAN-host one, Direct Connect the other to localhost.
echo   Keep this window open while you play. Closing it stops the game.
echo(

call "gradlew.bat" --console=plain runClient2
set "EXITCODE=%ERRORLEVEL%"
if "%EXITCODE%"=="0" goto :done

echo(
echo   [!] The dev client exited with an error (code %EXITCODE%).
echo       The full log is above, and also in:
echo         run2\logs\latest.log
echo         run2\crash-reports\
echo(
powershell -NoProfile -Command "Add-Type -AssemblyName PresentationFramework; [void][System.Windows.MessageBox]::Show('The Portal Glimpse dev client 2 crashed or failed to start (exit code %EXITCODE%).`n`nThe console window has the full log. Details are also saved in:`n    run2\logs\latest.log`n    run2\crash-reports\','Portal Glimpse - Crash / Error',[System.Windows.MessageBoxButton]::OK,[System.Windows.MessageBoxImage]::Error)"
echo   Press any key to close this window...
pause >nul

:done
endlocal
