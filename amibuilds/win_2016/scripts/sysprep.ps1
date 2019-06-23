If ([environment]::OSVersion.Version.Major -ge 10) {
Write-Host .....Sysprepping Server 2016 or newer.....

cd C:\ProgramData\Amazon\EC2-Windows\Launch\Scripts
./InitializeInstance.ps1 -Schedule
./SysprepInstance.ps1 -NoShutdown
}
Else {

Write-Host .....Sysprepping Server 2012 R2 or older.....

#cd "C:\Program Files\Amazon\Ec2ConfigService\Scripts"
#.\InstallUpdates.ps1 -Patch 0

## Sysprep EC2 config
$EC2SettingsFile="C:\\Program Files\\Amazon\\Ec2ConfigService\\Settings\\Config.xml"
$xml = [xml](get-content $EC2SettingsFile)
$xmlElement = $xml.get_DocumentElement()
$xmlElementToModify = $xmlElement.Plugins

foreach ($element in $xmlElementToModify.Plugin)
{
    if ($element.name -eq "Ec2SetPassword")
    {
        $element.State="Enabled"
    }
    elseif ($element.name -eq "Ec2SetComputerName")
    {
        $element.State="Enabled"
    }
    elseif ($element.name -eq "Ec2HandleUserData")
    {
        $element.State="Enabled"
    }
}
$xml.Save($EC2SettingsFile)


## Sysprep BundleConfig
$EC2SettingsFile="C:\\Program Files\\Amazon\\Ec2ConfigService\\Settings\\BundleConfig.xml"
$xml = [xml](get-content $EC2SettingsFile)
$xmlElement = $xml.get_DocumentElement()

foreach ($element in $xmlElement.Property)
{
    if ($element.Name -eq "AutoSysprep")
    {
        $element.Value="Yes"
    }
}
$xml.Save($EC2SettingsFile)
}

