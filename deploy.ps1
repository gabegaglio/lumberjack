# Stop immediately if anything fails
$ErrorActionPreference = "Stop"

# ---- CONFIG ----
$SERVER = "root@192.168.0.192"
$PLUGIN_DIR = "/opt/minecraft/plugins"
$SERVICE_NAME = "minecraft"
# ----------------

# Resolve Maven command (PATH first, IDE fallback)
$MAVEN_CMD = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $MAVEN_CMD) {
    $ideaMvn = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.2\plugins\maven\lib\maven3\bin\mvn.cmd"
    if (Test-Path $ideaMvn) {
        $MAVEN_CMD = $ideaMvn
    }
}
if (-not $MAVEN_CMD) {
    Write-Error "Maven (mvn) not found in PATH and no fallback found."
    exit 1
}

Write-Host "==============================="
Write-Host " Building Lumberjack plugin"
Write-Host "==============================="

# 1. Build with Maven
& $MAVEN_CMD clean package

# 2. Find the newest JAR in target/
$jar = Get-ChildItem "target\*.jar" |
       Sort-Object LastWriteTime -Descending |
       Select-Object -First 1

if (-not $jar) {
    Write-Error "No JAR found in target folder."
    exit 1
}

Write-Host "Found JAR: $($jar.Name)"

# 3. Upload JAR to Proxmox server
Write-Host "Uploading plugin to server..."
scp $jar.FullName "$($SERVER):$PLUGIN_DIR/"

# 4. Restart Minecraft service
Write-Host "Restarting Minecraft service..."
ssh $SERVER "systemctl restart $SERVICE_NAME"

Write-Host "==============================="
Write-Host " Deploy complete!"
Write-Host "==============================="

