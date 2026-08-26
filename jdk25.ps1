# =================================================================================================
# Deja el JDK 25 activo SÓLO en esta sesión de PowerShell.
#
#   . .\jdk25.ps1        <- ojo al punto y el espacio del principio
#
# Hay que ejecutarlo con "dot sourcing" (ese punto inicial). Sin él, PowerShell lanza el script
# en un proceso hijo, las variables se fijan ahí dentro y mueren al terminar: el script diría que
# todo fue bien y `./mvnw` seguiría viendo el JDK de siempre.
#
# POR QUÉ ESTO Y NO UN JAVA_HOME PERMANENTE
#
# Cambiar JAVA_HOME a nivel de usuario afecta a TODOS los proyectos de la máquina. Java 25 es
# compatible hacia atrás y la mayoría seguirían funcionando, pero herramientas más antiguas
# —Gradle viejo, Spring Boot 2.x— se rompen con JDK nuevos. Este script acota el cambio a la
# terminal donde se ejecuta y no toca nada del sistema.
#
# Centinela exige JDK 25: `maven-enforcer-plugin` corta el build antes de compilar si encuentra
# otra cosa. No es 26 ni superior por decisión explícita: no existe imagen de GraalVM community
# para 26 y Spring Boot 4.1 certifica la compilación nativa sobre 25 (ADR-0001 y ADR-0015).
# =================================================================================================

$ErrorActionPreference = 'Stop'

# Se busca en las rutas habituales en vez de fijar una: el número de build cambia con cada
# actualización, y un JAVA_HOME clavado a mano deja de funcionar en silencio el día que winget
# instala la siguiente.
$raices = @(
    "$env:ProgramFiles\Eclipse Adoptium",
    "$env:ProgramFiles\Java",
    "$env:ProgramFiles\Microsoft",
    "$env:ProgramFiles\Amazon Corretto",
    "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
)

$candidatos = foreach ($raiz in $raices) {
    if (Test-Path $raiz) {
        Get-ChildItem $raiz -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(^|[-_])(jdk-?)?25([.\-]|$)' } |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') }   # JDK, no JRE
    }
}

if (-not $candidatos) {
    Write-Host "No se encontró ningún JDK 25 instalado." -ForegroundColor Red
    Write-Host ""
    Write-Host "  winget install --id EclipseAdoptium.Temurin.25.JDK"
    Write-Host ""
    Write-Host "Alternativa sin instalar nada, con el JDK 25 de la imagen de GraalVM:"
    Write-Host '  docker run --rm -v "${PWD}:/proyecto" -v "${HOME}/.m2:/root/.m2" -w /proyecto --entrypoint /bin/bash ghcr.io/graalvm/native-image-community:25 -c "./mvnw -B -ntp test"'
    return
}

# Si hay varios (pasa: cada actualización deja el anterior), gana el de VERSIÓN más alta.
#
# Ordenar por nombre no vale, aunque lo parezca: `Sort-Object Name` compara como texto, y ahí
# "jdk-25.0.4.7" sale por encima de "jdk-25.0.4.101" porque el carácter '7' es mayor que '1'.
# El resultado es elegir un build viejo creyendo que es el nuevo. Se extrae el número y se
# compara como [version], que sí ordena por componentes.
function Obtener-Version($directorio) {
    if ($directorio.Name -match '(\d+(?:\.\d+){1,3})') {
        try { return [version]$Matches[1] } catch { }
    }
    return [version]'0.0'
}

$elegido = $candidatos |
    Sort-Object -Property @{ Expression = { Obtener-Version $_ } } -Descending |
    Select-Object -First 1

$env:JAVA_HOME = $elegido.FullName
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

$version = (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-Object -First 1)

Write-Host "JAVA_HOME -> $env:JAVA_HOME" -ForegroundColor Green
Write-Host "            $version"

if ($candidatos.Count -gt 1) {
    Write-Host ""
    Write-Host "Hay $($candidatos.Count) instalaciones de JDK 25; se eligió la más reciente:" -ForegroundColor DarkGray
    $candidatos | Sort-Object -Property @{ Expression = { Obtener-Version $_ } } -Descending | ForEach-Object {
        $marca = if ($_.FullName -eq $elegido.FullName) { '->' } else { '  ' }
        Write-Host "  $marca $($_.Name)" -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "Ya podés ejecutar:  ./mvnw test"
