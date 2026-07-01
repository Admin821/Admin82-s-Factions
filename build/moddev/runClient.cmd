@echo off
setlocal
for /f "tokens=2 delims=:." %%x in ('chcp') do set _codepage=%%x
chcp 65001>nul
cd C:\Users\delta\Documents\github\Admin82-s-Factions\run
"C:\Program Files\Java\jdk-21.0.11\bin\java.exe" @C:\Users\delta\Documents\github\Admin82-s-Factions\build\moddev\clientRunClasspath.txt @C:\Users\delta\Documents\github\Admin82-s-Factions\build\moddev\clientRunVmArgs.txt -Dfml.modFolders=adminsfactions%%%%C:\Users\delta\Documents\github\Admin82-s-Factions\build\classes\java\main;adminsfactions%%%%C:\Users\delta\Documents\github\Admin82-s-Factions\build\resources\main net.neoforged.devlaunch.Main @C:\Users\delta\Documents\github\Admin82-s-Factions\build\moddev\clientRunProgramArgs.txt
if not ERRORLEVEL 0 (  echo Minecraft failed with exit code %ERRORLEVEL%  pause)
chcp %_codepage%>nul
endlocal