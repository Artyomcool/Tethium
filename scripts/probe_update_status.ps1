$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$definitions=@()
foreach($name in @('reload_desktop.ps1','request_desktop_update.ps1')) {
    $tokens=$null;$parseErrors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $root ('scripts/'+$name)),[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw ($parseErrors|Out-String)}
    $function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'SaveStatusFile'},$true)
    $definitions+=$function.Extent.Text.Replace("`r`n","`n").Trim()
}
if($definitions[0] -ne $definitions[1]){throw 'Status writers differ'}
Invoke-Expression $definitions[0]
$folder=Join-Path $root ('.local/update-status-probe/'+[Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$file=Join-Path $folder 'status.json'
SaveStatusFile $file '{"sequence":0}'
$ready=[Threading.ManualResetEventSlim]::new($false)
$worker=[PowerShell]::Create()
[void]$worker.AddScript({param($path,$ready)
    $stream=[IO.File]::Open($path,'Open','Read','Read')
    try{$ready.Set();[Threading.Thread]::Sleep(250)}finally{$stream.Dispose()}
}).AddArgument($file).AddArgument($ready)
$pending=$worker.BeginInvoke();if(-not $ready.Wait(5000)){throw 'Reader did not start'}
SaveStatusFile $file '{"sequence":1}'
$null=$worker.EndInvoke($pending);$worker.Dispose();$ready.Dispose()
if((Get-Content -LiteralPath $file -Raw|ConvertFrom-Json).sequence -ne 1){throw 'Retry lost the update'}
$stop=[Threading.ManualResetEventSlim]::new($false)
$readDefinition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'ReadStatusFile'},$true).Extent.Text
$reader=[PowerShell]::Create()
[void]$reader.AddScript({param($path,$stop,$definition)
    $ErrorActionPreference='Stop';Invoke-Expression $definition
    $reads=0
    while(-not $stop.IsSet){
        $value=ReadStatusFile $path;if($null -eq $value.sequence){throw 'Torn JSON'};$reads++
    }
    $reads
}).AddArgument($file).AddArgument($stop).AddArgument($readDefinition)
$pending=$reader.BeginInvoke()
try{for($number=2;$number -le 101;$number++){SaveStatusFile $file (@{sequence=$number;phase='test';padding=('x'*1000)}|ConvertTo-Json)}}finally{$stop.Set()}
$reads=$reader.EndInvoke($pending)
if($reader.HadErrors){throw ($reader.Streams.Error|Out-String)}
$reader.Dispose();$stop.Dispose()
if((Get-Content -LiteralPath $file -Raw|ConvertFrom-Json).sequence -ne 101){throw 'Latest state lost'}
"Both scripts parse; locked-reader retry and 100 atomic writes passed ($reads concurrent reads)."
