# Begood Studio - separa a música em faixas (voz, guitarra, baixo, bateria, piano e outros)
# usando o Demucs (inteligência artificial gratuita, roda no próprio PC, sem internet depois de instalado).
#
# Uso: arraste uma ou mais músicas para "Separar instrumentos.bat" (ou dê dois cliques nele e escolha).
# As faixas ficam em Documentos\Begood Studio\Faixas separadas\<nome da música>.
# Copie a pasta da música para o celular e abra no Studio: Player > Biblioteca > "Abrir faixas separadas".

[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arquivos,
    # 6 faixas (com guitarra e piano) ou 4 faixas (voz, baixo, bateria e outros; um pouco mais limpo)
    [ValidateSet('6', '4')][string]$Faixas = '6',
    [string]$Saida = ''
)

$ErrorActionPreference = 'Stop'
$env:PYTHONIOENCODING = 'utf-8'
$base = Join-Path $env:LOCALAPPDATA 'BegoodStudio\separador'
$venv = Join-Path $base 'venv'
$py = Join-Path $venv 'Scripts\python.exe'
if (-not $Saida) { $Saida = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Begood Studio\Faixas separadas' }

function Escrever($texto, $cor = 'Yellow') { Write-Host $texto -ForegroundColor $cor }

function Instalar {
    Escrever "Primeira vez: instalando o separador (uns 1 GB, pode levar alguns minutos)..."
    $lancador = $null
    if (Get-Command py -ErrorAction SilentlyContinue) { $lancador = 'py' }
    elseif (Get-Command python -ErrorAction SilentlyContinue) { $lancador = 'python' }
    if (-not $lancador) {
        Escrever "Não achei o Python neste PC. Instale pelo site python.org (marque 'Add to PATH') e rode de novo." 'Red'
        exit 1
    }
    New-Item -ItemType Directory -Force $base | Out-Null
    # O "uv" baixa um Python 3.12 só para o separador (o Demucs ainda não funciona nos Pythons mais novos).
    & $lancador -m pip install --quiet --disable-pip-version-check --user uv
    if ($LASTEXITCODE -ne 0) { throw "Não deu para instalar o uv." }
    & $lancador -m uv venv --python 3.12 $venv
    if ($LASTEXITCODE -ne 0) { throw "Não deu para preparar o Python 3.12." }
    & $lancador -m uv pip install --python $py torch==2.5.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/cpu
    if ($LASTEXITCODE -ne 0) { throw "Não deu para instalar o PyTorch." }
    & $lancador -m uv pip install --python $py demucs==4.0.1 soundfile "numpy<2"
    if ($LASTEXITCODE -ne 0) { throw "Não deu para instalar o Demucs." }
    Escrever "Separador instalado." 'Green'
}

# Nomes em português (o app do Studio reconhece estes e também os nomes em inglês)
$nomes = @{ vocals = 'Voz'; guitar = 'Guitarra'; bass = 'Baixo'; drums = 'Bateria'; piano = 'Piano'; other = 'Outros' }

if (-not (Test-Path $py)) { Instalar }

if (-not $Arquivos -or $Arquivos.Count -eq 0) {
    Add-Type -AssemblyName System.Windows.Forms
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Title = 'Escolha as músicas para separar'
    $dlg.Filter = 'Músicas|*.mp3;*.flac;*.wav;*.m4a;*.ogg;*.opus;*.aac;*.wma|Todos os arquivos|*.*'
    $dlg.Multiselect = $true
    if ($dlg.ShowDialog() -ne 'OK') { exit 0 }
    $Arquivos = $dlg.FileNames
}

$modelo = if ($Faixas -eq '4') { 'htdemucs' } else { 'htdemucs_6s' }
$temp = Join-Path $env:TEMP ("begood-separar-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $temp | Out-Null
$feitas = @()

try {
    foreach ($arq in $Arquivos) {
        if (-not (Test-Path -LiteralPath $arq)) { Escrever "Não achei: $arq" 'Red'; continue }
        $item = Get-Item -LiteralPath $arq
        $nome = $item.BaseName
        Escrever ""
        Escrever "Separando: $nome  (no PC sem placa de vídeo leva uns minutos por música)"
        # Trabalha numa cópia com nome simples (acentos e símbolos no nome não atrapalham)
        $copia = Join-Path $temp ("musica" + $item.Extension.ToLower())
        Copy-Item -LiteralPath $item.FullName $copia -Force
        $tmpSaida = Join-Path $temp 'saida'
        & $py -m demucs -n $modelo --flac -o $tmpSaida $copia
        if ($LASTEXITCODE -ne 0) { Escrever "Deu erro ao separar $nome." 'Red'; continue }

        $origem = Join-Path $tmpSaida "$modelo\musica"
        $limpo = ($nome -replace '[<>:"/\\|?*]', '_').Trim()
        $destino = Join-Path $Saida $limpo
        New-Item -ItemType Directory -Force $destino | Out-Null
        foreach ($f in Get-ChildItem $origem -Filter *.flac) {
            $pt = $nomes[$f.BaseName]
            if (-not $pt) { $pt = $f.BaseName }
            Move-Item $f.FullName (Join-Path $destino "$pt.flac") -Force
        }
        Remove-Item -Recurse -Force $tmpSaida -ErrorAction SilentlyContinue
        Remove-Item -Force $copia -ErrorAction SilentlyContinue
        Escrever "Pronto: $destino" 'Green'
        $feitas += $destino
    }
} finally {
    Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
}

if ($feitas.Count -gt 0) {
    Escrever ""
    Escrever "Copie a pasta de cada música para o celular e abra no Studio:" 'Green'
    Escrever "Player > Biblioteca > Abrir faixas separadas." 'Green'
    if (-not $env:BEGOOD_SEM_JANELA) { Start-Process explorer.exe $Saida }
}
