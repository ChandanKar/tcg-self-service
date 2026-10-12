<#
.SYNOPSIS
    Regenerates src/main/resources/pricing/instance-pricing.json from the AWS Price List API (E30-T02).

.DESCRIPTION
    Developer-run, read-only. Needs the AWS CLI v2 and credentials allowed pricing:GetProducts and
    ec2:DescribeInstanceTypes. For every family x size x region it reads the on-demand Linux, shared
    tenancy hourly rate, and from describe-instance-types the vCPU, memory and architecture of every
    type. storageGbMonthRate, downsizeMap and generationUpgradeMap are kept from the current file
    (the downsize and upgrade maps are extended to any new family/size so every priced type has them).
    With -Rds (E33-T05) it also reads AmazonRDS on-demand rates per engine, DB class, region and
    deployment (Single-AZ / Multi-AZ) into rdsPricing; rdsStorageGbMonthRate is kept from the file.
    The app reads the file once at startup and never calls the Price List API itself.

.EXAMPLE
    ./scripts/generate-instance-pricing.ps1
    ./scripts/generate-instance-pricing.ps1 -Regions us-east-1,eu-central-1 -Families m7i,m7g
    ./scripts/generate-instance-pricing.ps1 -Rds
#>
param(
    [string[]]$Regions = @('ap-south-1', 'us-east-1', 'us-west-2', 'eu-west-1', 'eu-central-1', 'ap-southeast-1'),
    [string[]]$Families = @('t3', 't3a', 't4g', 'm5', 'm6i', 'm6a', 'm6g', 'm7i', 'm7g',
                            'c5', 'c6i', 'c6g', 'c7i', 'c7g', 'r5', 'r6i', 'r6g', 'r7i', 'r7g'),
    [string[]]$BurstableSizes = @('nano', 'micro', 'small', 'medium', 'large', 'xlarge', '2xlarge'),
    [string[]]$Sizes = @('large', 'xlarge', '2xlarge', '4xlarge'),
    [string]$OutFile = (Join-Path $PSScriptRoot '..\src\main\resources\pricing\instance-pricing.json'),
    [switch]$Rds,
    [string[]]$RdsClasses = @('db.t3.micro', 'db.t3.small', 'db.t3.medium', 'db.t3.large',
                              'db.t4g.micro', 'db.t4g.small', 'db.t4g.medium', 'db.t4g.large',
                              'db.m5.large', 'db.m5.xlarge', 'db.m5.2xlarge', 'db.m6g.large', 'db.m6g.xlarge', 'db.m6g.2xlarge',
                              'db.m6i.large', 'db.m6i.xlarge', 'db.m6i.2xlarge', 'db.r5.large', 'db.r5.xlarge', 'db.r5.2xlarge',
                              'db.r6g.large', 'db.r6g.xlarge', 'db.r6g.2xlarge', 'db.r6i.large', 'db.r6i.xlarge', 'db.r6i.2xlarge'),
    # Price List databaseEngine values and the RDS engine names the app stores
    [hashtable]$RdsEngines = @{ 'PostgreSQL' = 'postgres'; 'MySQL' = 'mysql'; 'Aurora MySQL' = 'aurora-mysql'; 'Aurora PostgreSQL' = 'aurora-postgresql' }
)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) { throw 'The AWS CLI (v2) is required.' }

$current = Get-Content $OutFile -Raw | ConvertFrom-Json
$types = foreach ($f in $Families) {
    $list = if ($f -like 't*') { $BurstableSizes } else { $Sizes }
    foreach ($s in $list) { "$f.$s" }
}

function Get-OnDemandRate([string]$Type, [string]$Region) {
    # The Price List API is served from us-east-1 whatever region is priced.
    $filters = @(
        "Type=TERM_MATCH,Field=instanceType,Value=$Type",
        "Type=TERM_MATCH,Field=regionCode,Value=$Region",
        'Type=TERM_MATCH,Field=operatingSystem,Value=Linux',
        'Type=TERM_MATCH,Field=tenancy,Value=Shared',
        'Type=TERM_MATCH,Field=preInstalledSw,Value=NA',
        'Type=TERM_MATCH,Field=capacitystatus,Value=Used',
        'Type=TERM_MATCH,Field=licenseModel,Value=No License required'
    )
    $raw = aws pricing get-products --service-code AmazonEC2 --region us-east-1 --filters $filters --output json | ConvertFrom-Json
    foreach ($item in $raw.PriceList) {
        $product = $item | ConvertFrom-Json
        foreach ($term in $product.terms.OnDemand.PSObject.Properties.Value) {
            foreach ($dim in $term.priceDimensions.PSObject.Properties.Value) {
                $usd = [decimal]$dim.pricePerUnit.USD
                if ($dim.unit -eq 'Hrs' -and $usd -gt 0) { return $usd }
            }
        }
    }
    return $null
}

function Get-RdsRate([string]$Class, [string]$Engine, [string]$Region, [string]$Deployment) {
    $filters = @(
        "Type=TERM_MATCH,Field=instanceType,Value=$Class",
        "Type=TERM_MATCH,Field=regionCode,Value=$Region",
        "Type=TERM_MATCH,Field=databaseEngine,Value=$Engine",
        "Type=TERM_MATCH,Field=deploymentOption,Value=$Deployment"
    )
    $raw = aws pricing get-products --service-code AmazonRDS --region us-east-1 --filters $filters --output json | ConvertFrom-Json
    foreach ($item in $raw.PriceList) {
        $product = $item | ConvertFrom-Json
        # Aurora I/O-Optimized and license-included variants are separate products; take the standard one.
        if ($product.product.attributes.storage -eq 'Aurora IO Optimization Mode') { continue }
        foreach ($term in $product.terms.OnDemand.PSObject.Properties.Value) {
            foreach ($dim in $term.priceDimensions.PSObject.Properties.Value) {
                $usd = [decimal]$dim.pricePerUnit.USD
                if ($dim.unit -eq 'Hrs' -and $usd -gt 0) { return $usd }
            }
        }
    }
    return $null
}

$pricing = New-Object System.Collections.Generic.List[object]
foreach ($region in $Regions) {
    foreach ($t in $types) {
        $rate = Get-OnDemandRate $t $region
        if ($null -eq $rate) { Write-Warning "No on-demand Linux rate for $t in $region"; continue }
        $pricing.Add([ordered]@{ provider = 'AWS'; instanceType = $t; region = $region; hourlyRate = $rate })
    }
    Write-Output "$region done ($($pricing.Count) rates so far)"
}

# vCPU, memory and architecture; describe-instance-types answers for any region that offers the type.
$specs = New-Object System.Collections.Generic.List[object]
for ($i = 0; $i -lt $types.Count; $i += 50) {
    $batch = $types[$i..([Math]::Min($i + 49, $types.Count - 1))]
    $res = aws ec2 describe-instance-types --region us-east-1 --instance-types $batch --output json | ConvertFrom-Json
    foreach ($it in $res.InstanceTypes) {
        $family = $it.InstanceType.Split('.')[0]
        $specs.Add([ordered]@{
            instanceType = $it.InstanceType
            vcpu = [int]$it.VCpuInfo.DefaultVCpus
            memoryMib = [int]$it.MemoryInfo.SizeInMiB
            architecture = if ($it.ProcessorInfo.SupportedArchitectures -contains 'arm64') { 'arm64' } else { 'x86_64' }
            generation = [int]($family -replace '\D', '')
            family = $family
        })
    }
}
$specs = $specs | Sort-Object { $types.IndexOf($_.instanceType) }

# Downsize map: next smaller size within the family (never below micro); existing rules win.
$downsize = [ordered]@{}
foreach ($p in $current.downsizeMap.PSObject.Properties) { $downsize[$p.Name] = $p.Value }
foreach ($f in $Families) {
    $list = if ($f -like 't*') { $BurstableSizes } else { $Sizes }
    for ($i = $list.Count - 1; $i -gt 0; $i--) {
        if ($list[$i - 1] -eq 'nano') { continue }
        $key = "AWS:$f.$($list[$i])"
        if (-not $downsize.Contains($key)) { $downsize[$key] = "$f.$($list[$i - 1])" }
    }
}

$rdsPricing = $current.rdsPricing
if ($Rds) {
    $rdsPricing = New-Object System.Collections.Generic.List[object]
    foreach ($region in $Regions) {
        foreach ($engine in $RdsEngines.Keys) {
            foreach ($class in $RdsClasses) {
                # Aurora has no Multi-AZ deployment option: a cluster's replicas are separate instances.
                $deployments = if ($engine -like 'Aurora*') { @('Single-AZ') } else { @('Single-AZ', 'Multi-AZ') }
                foreach ($deployment in $deployments) {
                    $rate = Get-RdsRate $class $engine $region $deployment
                    if ($null -eq $rate) { continue }
                    $rdsPricing.Add([ordered]@{ engine = $RdsEngines[$engine]; instanceClass = $class; region = $region
                        deployment = if ($deployment -eq 'Multi-AZ') { 'MULTI_AZ' } else { 'SINGLE_AZ' }; hourlyRate = $rate })
                }
            }
        }
        Write-Output "RDS $region done ($($rdsPricing.Count) rates so far)"
    }
}

$doc = [ordered]@{
    _comment = $current._comment
    storageGbMonthRate = $current.storageGbMonthRate
    instancePricing = $pricing
    downsizeMap = $downsize
    instanceTypes = $specs
    generationUpgradeMap = $current.generationUpgradeMap
    rdsStorageGbMonthRate = $current.rdsStorageGbMonthRate
    rdsPricing = $rdsPricing
}
$doc | ConvertTo-Json -Depth 6 | Set-Content -Path $OutFile -Encoding utf8
Write-Output "Wrote $($pricing.Count) rates in $($Regions.Count) regions and $($specs.Count) specs to $OutFile"
Write-Output 'Check the diff before committing; regional rates that disappeared mean the API had no match.'
