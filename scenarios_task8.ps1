# Task8 Scenario harness - use curl.exe directly, avoid PS function/alias conflicts
$ErrorActionPreference = "Continue"
$WORKDIR = "C:\Users\core101\Desktop\autocode\linked_store\backend\target\scn8"
New-Item -ItemType Directory -Force -Path $WORKDIR | Out-Null
Remove-Item -Path (Join-Path $WORKDIR "*") -Force -Recurse -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $WORKDIR | Out-Null

$BASE = "http://localhost:8080"
$BROOKLYN = "d7e59214-5adf-4788-beb0-ffccf4ab18f9"
$DOWNTOWN = "3c4e5dad-7cf2-4f5b-a617-ff0adaa04d44"
$UPTOWN   = "597f121f-cc36-4799-8e96-98b2ccfa53c7"
$Results = [ordered]@{}
$CURL = (Get-Command curl.exe -ErrorAction Stop).Source

function Write-JsonFile($Name, $Obj) {
    $path = Join-Path $WORKDIR "$Name.json"
    [System.IO.File]::WriteAllText($path, ($Obj | ConvertTo-Json -Depth 10 -Compress), [System.Text.UTF8Encoding]::new($false))
    return $path
}

function CallCurl($Label, $Method, $Url, [string]$BodyFile = $null, [string]$Bearer = $null, [string]$Accept = "application/json") {
    $outBody = Join-Path $WORKDIR "${Label}.body"
    $outHead = Join-Path $WORKDIR "${Label}.head"
    $argList = New-Object System.Collections.Generic.List[string]
    [void]$argList.Add("-s"); [void]$argList.Add("-S")
    [void]$argList.Add("-X"); [void]$argList.Add($Method)
    [void]$argList.Add("-o"); [void]$argList.Add($outBody)
    [void]$argList.Add("-D"); [void]$argList.Add($outHead)
    [void]$argList.Add("-w"); [void]$argList.Add("%{http_code}")
    [void]$argList.Add("-H"); [void]$argList.Add("Accept: ${Accept}")
    if ($BodyFile) {
        [void]$argList.Add("-H"); [void]$argList.Add("Content-Type: application/json")
        [void]$argList.Add("--data-binary"); [void]$argList.Add("@" + $BodyFile)
    }
    if ($Bearer) {
        [void]$argList.Add("-H"); [void]$argList.Add("Authorization: Bearer ${Bearer}")
    }
    [void]$argList.Add($Url)
    $arr = $argList.ToArray()
    $code = & $CURL @arr 2>&1
    $body = if (Test-Path $outBody) { [System.IO.File]::ReadAllText($outBody) } else { "" }
    return [pscustomobject]@{ Label=$Label; Code=[string]$code; Body=$body }
}

function Record($r, $expected, $note = "") {
    $preview = if ($r.Body.Length -gt 0) { $r.Body.Substring(0, [Math]::Min(220, $r.Body.Length)) } else { "" }
    $Results[$r.Label] = [pscustomobject]@{ Code=$r.Code; Expected=$expected; Ok=($r.Code -eq $expected); Note=$note; BodyPreview=$preview }
    $flag = if ($r.Code -eq $expected) {"PASS"} else {"FAIL"}
    Write-Host ("[{0}] {1} HTTP {2} (expect {3}) {4}" -f $flag, $r.Label.PadRight(20), $r.Code.ToString().PadLeft(3), $expected, $note)
    if ($r.Code -ne $expected -and $r.Body.Length -gt 0) {
        $snippet = if ($r.Body.Length -gt 500) { $r.Body.Substring(0, 500) } else { $r.Body }
        Write-Host ("    body: {0}" -f $snippet)
    }
}

Write-Host ""
Write-Host "=== [0] connect health ===" -Foreground Cyan
$r = CallCurl "00_HEALTH" GET "${BASE}/api/connect/health"
Record $r "200" "connect platform OK"

Write-Host "=== [1] login global admin ===" -Foreground Cyan
Write-JsonFile "LOGIN_ADMIN" @{ email="admin@linked.store"; password="Admin123!" } | Out-Null
$r = CallCurl "01_LOGIN_ADMIN" POST "${BASE}/api/auth/login" -BodyFile (Join-Path $WORKDIR "LOGIN_ADMIN.json")
Record $r "200"
$adminTok = if ($r.Code -eq "200" -and $r.Body) { ($r.Body | ConvertFrom-Json).accessToken } else { $null }
Write-Host ("    admin token length: {0}" -f $adminTok.Length) -Foreground DarkGray

Write-Host "=== [2b] SQL seed fresh Brooklyn+Downtown PENDING invites ===" -Foreground Yellow
$invBrook = "inv_S8b_brook_" + (-join ((65..90) + (97..122) + (48..57) | Get-Random -Count 24 | ForEach-Object { [char]$_ }))
$invDown  = "inv_S8b_down_"  + (-join ((65..90) + (97..122) + (48..57) | Get-Random -Count 24 | ForEach-Object { [char]$_ }))
$sql = @"
INSERT INTO store_invites (id, store_id, invite_token, target_role, prefill_email, created_by, created_at, expires_at, status)
VALUES
  (gen_random_uuid(), '${BROOKLYN}', '${invBrook}', 'STORE_REPRESENTATIVE', 'brooklyn.rep2@scn8.test', 'f072b220-63b9-4151-b356-219348d427df', NOW(), NOW() + interval '7 days', 'PENDING'),
  (gen_random_uuid(), '${DOWNTOWN}', '${invDown}',  'STORE_REPRESENTATIVE', 'downtown.rep2@scn8.test', 'f072b220-63b9-4151-b356-219348d427df', NOW(), NOW() + interval '7 days', 'PENDING');
SELECT invite_token, store_id::text, target_role, prefill_email, status FROM store_invites WHERE invite_token IN ('${invBrook}','${invDown}');
"@
$env:PGPASSWORD = 'postgres'
Set-Content -Path (Join-Path $WORKDIR "seed_invites.sql") -Value $sql -Encoding UTF8
& 'C:\Program Files\PostgreSQL\16\bin\psql.exe' -h localhost -U postgres -d linked_store -f (Join-Path $WORKDIR "seed_invites.sql") 2>&1 | Out-Host
Write-Host ("    brooklyn invite = ${invBrook}") -Foreground DarkGray
Write-Host ("    downtown invite = ${invDown}")  -Foreground DarkGray

Write-Host "=== [A] Preview Brooklyn invite (public unauth) ===" -Foreground Cyan
$r = CallCurl "A_PREVIEW_BROOK" GET "${BASE}/api/auth/invites/${invBrook}/preview"
Record $r "200" "valid=true targetRole=STORE_REPRESENTATIVE"

Write-Host "=== [B] Register Brooklyn REP via invite ===" -Foreground Cyan
Write-JsonFile "REG_BROOK" @{
    email="brooklyn.rep2@scn8.test"; password="BrooklynRep1!"; fullName="Brooklyn Scenario Rep";
    phone=""; isStoreAdmin=$false; inviteToken=$invBrook;
    businessName=$null; latitude=$null; longitude=$null; countryCode=$null; currencyCode=$null
} | Out-Null
$r = CallCurl "B_REGISTER_BROOK" POST "${BASE}/api/auth/register" -BodyFile (Join-Path $WORKDIR "REG_BROOK.json")
Record $r "201" "role STORE_REPRESENTATIVE storeId Brooklyn"
$brookTok = if ($r.Code -eq "201" -and $r.Body) { ($r.Body | ConvertFrom-Json).accessToken } else { $null }

Write-Host "=== [C] Re-redeem same Brooklyn invite -> 400 ===" -Foreground Cyan
Write-JsonFile "REG_BROOK2" @{
    email="brooklyn.rep3@scn8.test"; password="BrooklynRep1!"; fullName="Other";
    isStoreAdmin=$false; inviteToken=$invBrook;
} | Out-Null
$r = CallCurl "C_REDEEM_AGAIN" POST "${BASE}/api/auth/register" -BodyFile (Join-Path $WORKDIR "REG_BROOK2.json")
Record $r "400" "Invite has already been redeemed"

Write-Host "=== [D] Login Brooklyn REP ===" -Foreground Cyan
Write-JsonFile "LOGIN_BROOK" @{ email="brooklyn.rep2@scn8.test"; password="BrooklynRep1!" } | Out-Null
$r = CallCurl "D_LOGIN_BROOK" POST "${BASE}/api/auth/login" -BodyFile (Join-Path $WORKDIR "LOGIN_BROOK.json")
Record $r "200" "role STORE_REPRESENTATIVE"
if ($r.Code -eq "200" -and $r.Body) { $brookTok = ($r.Body | ConvertFrom-Json).accessToken }
Write-Host ("    brook rep token len: {0}" -f $brookTok.Length) -Foreground DarkGray

Write-Host "=== Find Downtown and Brooklyn variant IDs for scenarios F-J ===" -Foreground Cyan
$r = CallCurl "GETDOWNINV" GET "${BASE}/api/admin/stores/${DOWNTOWN}/inventory" -Bearer $adminTok
Record $r "200" "admin list downtown inventory"
$downVariants = if ($r.Code -eq "200" -and $r.Body) { @($r.Body | ConvertFrom-Json) } else { @() }
$r = CallCurl "GETBROOKINV" GET "${BASE}/api/admin/stores/${BROOKLYN}/inventory" -Bearer $adminTok
Record $r "200" "admin list brooklyn inventory"
$brookVariants = if ($r.Code -eq "200" -and $r.Body) { @($r.Body | ConvertFrom-Json) } else { @() }
$downVid  = if ($downVariants.Count -gt 0)  { $downVariants[0].variantId  } else { $null }
$brookVid = if ($brookVariants.Count -gt 0) { $brookVariants[0].variantId } else { $null }
Write-Host ("    downtown variant id: {0} (count={1})" -f $downVid, $downVariants.Count) -Foreground DarkGray
Write-Host ("    brooklyn variant id: {0} (count={1})" -f $brookVid, $brookVariants.Count) -Foreground DarkGray

if (-not $brookTok) { Write-Host "No brooklyn token - aborting E-K"; exit 1 }

Write-Host "=== [E] Brooklyn REP GET Downtown store ===" -Foreground Cyan
$r = CallCurl "E_GETDOWNTOWN" GET "${BASE}/api/admin/stores/${DOWNTOWN}" -Bearer $brookTok
Record $r "200" "cross-store view REP ok"

Write-Host "=== [F] Brooklyn REP GET Downtown inventory ===" -Foreground Cyan
$r = CallCurl "F_DOWNTOWN_INV" GET "${BASE}/api/admin/stores/${DOWNTOWN}/inventory" -Bearer $brookTok
Record $r "200" "cross-store inventory read ok count>=1"
$countF = if ($r.Code -eq "200" -and $r.Body) { (@($r.Body | ConvertFrom-Json)).Count } else { 0 }
Write-Host ("    items returned: {0}" -f $countF) -Foreground DarkGray

Write-Host "=== [G] Brooklyn REP GET Brooklyn inventory ===" -Foreground Cyan
$r = CallCurl "G_BROOKLYN_INV" GET "${BASE}/api/admin/stores/${BROOKLYN}/inventory" -Bearer $brookTok
Record $r "200" "own-store inventory ok"
$countG = if ($r.Code -eq "200" -and $r.Body) { (@($r.Body | ConvertFrom-Json)).Count } else { 0 }
Write-Host ("    items returned: {0}" -f $countG) -Foreground DarkGray

if ($downVid -and $brookVid) {
    Write-Host "=== [H] Brooklyn REP PUT Downtown variant retailPrice=9999 -> 403 ===" -Foreground Cyan
    $orig = $downVariants[0]
    Write-JsonFile "PUT_DOWN" @{ sku=$orig.sku; title=$orig.productTitle; description=$orig.productDescription; retailPriceCents=9999; wholesalePriceCents=$orig.wholesalePriceCents; stockQuantity=$orig.stockQuantity; variantImageUrl=$orig.variantImageUrl; productId=$orig.productId } | Out-Null
    $r = CallCurl "H_PUT_DOWN_VAR" PUT "${BASE}/api/admin/stores/${DOWNTOWN}/inventory/${downVid}" -Bearer $brookTok -BodyFile (Join-Path $WORKDIR "PUT_DOWN.json")
    Record $r "403" "PERMISSION_ILLEGAL* cross write"

    Write-Host "=== [I] Brooklyn REP PUT Brooklyn variant retailPrice=1234 -> 200 ===" -Foreground Cyan
    $origB = $brookVariants[0]
    Write-JsonFile "PUT_BROOK" @{ sku=$origB.sku; title=$origB.productTitle; description=$origB.productDescription; retailPriceCents=1234; wholesalePriceCents=$origB.wholesalePriceCents; stockQuantity=$origB.stockQuantity; variantImageUrl=$origB.variantImageUrl; productId=$origB.productId } | Out-Null
    $r = CallCurl "I_PUT_BROOK_VAR" PUT "${BASE}/api/admin/stores/${BROOKLYN}/inventory/${brookVid}" -Bearer $brookTok -BodyFile (Join-Path $WORKDIR "PUT_BROOK.json")
    Record $r "200" "retailPriceCents == 1234 expected"
    if ($r.Code -eq "200" -and $r.Body) { $rb = $r.Body | ConvertFrom-Json; Write-Host ("    returned retailPriceCents = {0}" -f $rb.retailPriceCents) -Foreground DarkGray }

    Write-Host "=== [J] Brooklyn REP POST Downtown inventory/{variantId}/share-code -> 200 ===" -Foreground Cyan
    $r = CallCurl "J_QR_DOWNTOWN" POST "${BASE}/api/admin/stores/${DOWNTOWN}/inventory/${downVid}/share-code" -Bearer $brookTok
    Record $r "200" "cross-store QR generation ok shareCode non-null"
    if ($r.Code -eq "200" -and $r.Body) { $jb = $r.Body | ConvertFrom-Json; Write-Host ("    shareCode = {0}" -f $jb.shareCode) -Foreground DarkGray }
} else {
    Write-Host ("    SKIP H/I/J: variant IDs missing (downVid={0} brookVid={1})" -f $downVid, $brookVid) -Foreground Yellow
    $Results["H_PUT_DOWN_VAR"] = [pscustomobject]@{ Code="SKIP"; Expected="403"; Ok=$false; Note="variant missing"; BodyPreview="" }
    $Results["I_PUT_BROOK_VAR"]= [pscustomobject]@{ Code="SKIP"; Expected="200"; Ok=$false; Note="variant missing"; BodyPreview="" }
    $Results["J_QR_DOWNTOWN"] = [pscustomobject]@{ Code="SKIP"; Expected="200"; Ok=$false; Note="variant missing"; BodyPreview="" }
}

Write-Host "=== [K] Brooklyn REP POST Downtown connect/onboarding-link -> 403 ===" -Foreground Cyan
$r = CallCurl "K_PAYOUTS_DENY" POST "${BASE}/api/admin/stores/${DOWNTOWN}/connect/onboarding-link" -Bearer $brookTok
Record $r "403" "REP excluded from payout/connect routes"

Write-Host "=== [L] POST register clerk no-invite no-store-fields -> 400 ===" -Foreground Cyan
Write-JsonFile "REG_CLERK_NOINV" @{
    email="clerk_noinv2@scn8.test"; password="Clerk123!"; fullName="NoInv Clerk";
    isStoreAdmin=$false; inviteToken="";
} | Out-Null
$r = CallCurl "L_NO_INVITE_CLERK" POST "${BASE}/api/auth/register" -BodyFile (Join-Path $WORKDIR "REG_CLERK_NOINV.json")
Record $r "400" "Either provide a valid invite token..."

Write-Host ""
Write-Host "=== SUMMARY ===" -Foreground Green
$Results.GetEnumerator() | ForEach-Object {
    $color = if ($_.Value.Ok) { "Green" } elseif ($_.Value.Code -eq "SKIP") { "Yellow" } else { "Red" }
    Write-Host ("  {0,-22} {1,3} (expect {2,3})  {3}  {4}" -f $_.Key, $_.Value.Code, $_.Value.Expected, $(if ($_.Value.Ok) {"OK"} elseif ($_.Value.Code -eq "SKIP") {"SKIP"} else {"FAIL"}), $_.Value.Note) -Foreground $color
}
