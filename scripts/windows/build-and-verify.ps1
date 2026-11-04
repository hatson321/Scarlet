param([switch]$Offline, [string]$TestDirectory)

$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$mavenRepository = if ($env:SCARLET_MAVEN_REPOSITORY) { $env:SCARLET_MAVEN_REPOSITORY } else { Join-Path $sourceRoot '.build-cache\maven' }
if ($env:SCARLET_MAVEN_OFFLINE -eq '1') { $Offline = $true }

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

Push-Location -LiteralPath $sourceRoot
try {
    $javaInfo = (& java -XshowSettings:properties -version 2>&1 | Out-String)
    if ($javaInfo -notmatch 'java.specification.version = 21\s') { throw 'Use JDK 21 on PATH.' }
    $compilerInfo = (& javac -version 2>&1 | Out-String)
    if ($compilerInfo -notmatch 'javac 21[.\s]') { throw 'Use the JDK 21 compiler on PATH.' }
    $mavenInfo = (& mvn --version 2>&1 | Out-String)
    if ($mavenInfo -notmatch 'Apache Maven (\d+)\.(\d+)\.(\d+)') { throw 'Apache Maven 3.9 or newer is required.' }
    if ([version]($Matches[1] + '.' + $Matches[2] + '.' + $Matches[3]) -lt [version]'3.9.0') { throw 'Apache Maven 3.9 or newer is required.' }
    $mavenArguments = @('-B', '-ntp', "-Dmaven.repo.local=$mavenRepository")
    $bootstrapArguments = @('scripts/BootstrapVrchatSdk.java')
    if ($Offline) { $mavenArguments += '--offline'; $bootstrapArguments += '--offline' }
    Invoke-Checked 'java' $bootstrapArguments
    Invoke-Checked 'mvn' ($mavenArguments + @('-f', '.build-cache/vrchat-sdk/source/vrchatapi-java-1.23.0/pom.xml', '-Dmaven.test.skip=true', '-Dmaven.javadoc.skip=true', 'clean', 'install'))
    Invoke-Checked 'mvn' ($mavenArguments + @('-f', 'libdave-maven/pom.xml', 'clean', 'install'))
    $testArguments = @()
    if ($TestDirectory) {
        $testRoot = [IO.Path]::GetFullPath($TestDirectory)
        if (-not (Test-Path -LiteralPath $testRoot -PathType Container)) { throw 'Create the dedicated test directory before using -TestDirectory.' }
        $testArguments += "-Dscarlet.test.root=$testRoot"
    }
    Invoke-Checked 'mvn' ($mavenArguments + $testArguments + @('clean', 'verify'))
    $version = (Get-Content -LiteralPath 'scarlet.version' -Raw).Trim()
    if ($version -notmatch '^\d+[.]\d+[.]\d+(?:-a\d+)?$') { throw 'Unexpected release version.' }
    Invoke-Checked 'java' @('scripts/linux/VerifyLinuxPackage.java', "target/scarlet-$version.jar", "target/scarlet-$version-debug.jar", "target/scarlet-$version-ppc.jar", 'target/SHA256SUMS-packages.txt')
    Write-Host 'Source build and static package inspection finished. This does not verify live Windows/Linux voice, GUI, or VRChat/Discord integration.'
} finally { Pop-Location }
