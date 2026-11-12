param([string]$OutputPath='')
$ErrorActionPreference='Stop'
$entries=@()
foreach($hive in @('HKCU:','HKLM:')){
    foreach($branch in @('Software/Microsoft/Windows/CurrentVersion','Software/WOW6432Node/Microsoft/Windows/CurrentVersion','Software/Microsoft/Windows/CurrentVersion/Policies/Explorer')){
        foreach($kind in @('Run','RunOnce')){
            $path=Join-Path $hive ($branch+'/'+$kind)
            if(-not(Test-Path -LiteralPath $path)){continue}
            $key=Get-Item -LiteralPath $path
            foreach($name in $key.GetValueNames()){
                $approval=Join-Path $hive ('Software/Microsoft/Windows/CurrentVersion/Explorer/StartupApproved/'+$(if($branch -like '*WOW6432Node*'){'Run32'}else{'Run'}))
                $status=$null;if(Test-Path -LiteralPath $approval){$status=(Get-Item -LiteralPath $approval).GetValue($name)}
                $entries+=[pscustomobject]@{name=$name;source=$path;command=$key.GetValue($name);approvalByte=$(if($status -is [byte[]] -and $status.Length){[int]$status[0]}else{$null})}
            }
        }
    }
}
$shell=New-Object -ComObject WScript.Shell
$folders=@(foreach($folder in @([Environment]::GetFolderPath('Startup'),[Environment]::GetFolderPath('CommonStartup'))){
    Get-ChildItem -LiteralPath $folder -Force|Where-Object {$_.Name -ne 'desktop.ini'}|ForEach-Object {
        $target=$_.FullName;$arguments='';if($_.Extension -eq '.lnk'){$link=$shell.CreateShortcut($_.FullName);$target=$link.TargetPath;$arguments=$link.Arguments}
        [pscustomobject]@{name=$_.Name;source=$folder;target=$target;arguments=$arguments;targetExists=(Test-Path -LiteralPath $target)}
    }
})
$installed=@{};Get-AppxPackage|ForEach-Object {$installed[$_.PackageFamilyName]=$_.Name}
$packaged=@();$base='HKCU:/Software/Classes/Local Settings/Software/Microsoft/Windows/CurrentVersion/AppModel/SystemAppData'
if(Test-Path -LiteralPath $base){$packaged=@(Get-ChildItem -LiteralPath $base -Recurse -ErrorAction SilentlyContinue|ForEach-Object {
    $value=Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue
    if($null -ne $value.State){$parts=$_.Name.Split('\');$family=$parts[-2];[pscustomobject]@{package=$family;task=$_.PSChildName;state=[int]$value.State;installed=$installed.ContainsKey($family);name=$installed[$family]}}
})}
$tasks=@(Get-ScheduledTask|Where-Object {$_.TaskPath -notlike '\Microsoft\*'}|ForEach-Object {
    [pscustomobject]@{name=$_.TaskName;path=$_.TaskPath;state=$_.State.ToString();author=$_.Author;triggers=@($_.Triggers|ForEach-Object {$_.CimClass.CimClassName});actions=@($_.Actions|ForEach-Object {[pscustomobject]@{execute=$_.Execute;arguments=$_.Arguments}})}
})
$services=@(Get-CimInstance Win32_Service -Filter "StartMode='Auto'"|Where-Object {$_.PathName -notmatch '(?i)\\Windows\\|\\Microsoft\\|\\Microsoft.NET\\'}|ForEach-Object {
    [pscustomobject]@{name=$_.Name;displayName=$_.DisplayName;state=$_.State;delayed=$_.DelayedAutoStart;command=$_.PathName}
})
$processes=@(Get-Process -ErrorAction SilentlyContinue|Group-Object ProcessName|ForEach-Object {
    [pscustomobject]@{name=$_.Name;count=$_.Count;workingSetMB=[math]::Round(($_.Group|Measure-Object WorkingSet64 -Sum).Sum/1MB)}
}|Sort-Object workingSetMB -Descending)
$report=[ordered]@{capturedAt=(Get-Date -Format o);run=$entries;startupFolders=$folders;packagedTasks=$packaged;scheduledTasks=$tasks;automaticServices=$services;processes=$processes}
$json=$report|ConvertTo-Json -Depth 9
if($OutputPath){$file=[IO.Path]::GetFullPath($OutputPath);[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($file))|Out-Null;[IO.File]::WriteAllText($file,$json,[Text.UTF8Encoding]::new($false));Write-Output $file}else{$json}
