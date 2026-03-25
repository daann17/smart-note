$envFile = Join-Path $PSScriptRoot '.env'

if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
      $name = $matches[1].Trim()
      $value = $matches[2]
      Set-Item -Path "Env:$name" -Value $value
    }
  }
}

mvn.cmd spring-boot:run
