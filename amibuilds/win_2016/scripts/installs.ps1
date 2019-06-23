Install-WindowsFeature -name Web-Server -IncludeManagementTools
#install chocolatey
Set-ExecutionPolicy Bypass -Scope Process -Force; Invoke-Expression ((New-Object System.Net.WebClient).DownloadString('https://chocolatey.org/install.ps1'))
choco install inspec -y
choco install ccleaner -y
