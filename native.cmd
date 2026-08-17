@REM ---------------------------------------------------------------------------
@REM Builds target\orkx.exe.
@REM
@REM Only here because the native-maven-plugin finds native-image through
@REM GRAALVM_HOME, and a Maven toolchain cannot stand in for it on Windows: Maven
@REM looks for bin\native-image.exe, while a GraalVM ships bin\native-image.cmd.
@REM So this finds the GraalVM and sets the variable for one build rather than
@REM asking you to keep it in your environment.
@REM
@REM Arguments are passed on to Maven: `native.cmd -DskipTests`.
@REM ---------------------------------------------------------------------------
@echo off
setlocal

if defined GRAALVM_HOME (
    if exist "%GRAALVM_HOME%\bin\native-image.cmd" goto :build
    echo GRAALVM_HOME is set to "%GRAALVM_HOME%", which has no bin\native-image.cmd.
    exit /b 1
)

@REM Newest first, so an upgraded GraalVM is picked up without editing anything.
for /f "delims=" %%d in ('dir /b /ad /o-n "%USERPROFILE%\.jdks\graalvm*" 2^>nul') do (
    if exist "%USERPROFILE%\.jdks\%%d\bin\native-image.cmd" (
        set "GRAALVM_HOME=%USERPROFILE%\.jdks\%%d"
        goto :found
    )
)

echo No GraalVM found in "%USERPROFILE%\.jdks".
echo.
echo Install one - a GraalVM JDK 25, from
echo https://github.com/graalvm/graalvm-ce-builds/releases - and unpack it there,
echo or set GRAALVM_HOME to where yours already is.
echo.
echo Building the native image on Windows also needs the MSVC C++ tools and a
echo Windows SDK; native-image finds those itself once they are installed.
exit /b 1

:found
echo Using GraalVM at %GRAALVM_HOME%

:build
call "%~dp0mvnw.cmd" -Pnative package %*
