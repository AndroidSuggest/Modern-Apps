#!/usr/bin/env zsh
set -e

# Reduced CA set hardening — downloads specific roots from Mozilla bundle and direct sources,
# converts PEM -> DER for bundling in library:network assets/ca/.
#
# Why: first-party Cloudflare domains only need ISRG + GTS, reducing blast radius of rogue CA.
# Appstore STANDARD needs DigiCert + ISRG + GTS (F-Droid + GitHub + Play Store via GTS).

OUT_DIR="${0:A:h:h}/library/network/src/main/assets/ca"
BUNDLE_URL="https://curl.se/ca/cacert.pem"
TMP_DIR=$(mktemp -d)

if [[ "${1:-}" == "--clean" ]]; then
  rm -f "$OUT_DIR"/*.der
  echo "Cleaned $OUT_DIR"
fi

mkdir -p "$OUT_DIR"

echo "Downloading Mozilla CA bundle ($BUNDLE_URL)..."
curl -LfsS -o "$TMP_DIR/cacert.pem" "$BUNDLE_URL" || { echo "curl failed for bundle"; exit 1; }

# Split bundle into individual certs
echo "Splitting bundle..."
awk -v tmpdir="$TMP_DIR" '
  BEGIN { n=0; out="" }
  /BEGIN CERTIFICATE/ { out=tmpdir"/cert-"n".pem"; n++ }
  { if(out!="") print > out }
  /END CERTIFICATE/ { out="" }
' "$TMP_DIR/cacert.pem"

typeset -A ROOTS
# FIRST_PARTY: ISRG X1/X2 + GTS R1-R4
ROOTS[isrgrootx1]="ISRG Root X1"
ROOTS[isrgrootx2]="ISRG Root X2"
ROOTS[gts-root-r1]="GTS Root R1"
ROOTS[gts-root-r2]="GTS Root R2"
ROOTS[gts-root-r3]="GTS Root R3"
ROOTS[gts-root-r4]="GTS Root R4"
# STANDARD adds DigiCert, Baltimore, Amazon, Sectigo
ROOTS[digicert-global-g2]="DigiCert Global Root G2"
ROOTS[digicert-global-g3]="DigiCert Global Root G3"
ROOTS[baltimore-cybertrust]="Baltimore CyberTrust Root"
ROOTS[amazon-root-ca1]="Amazon Root CA 1"
ROOTS[amazon-root-ca2]="Amazon Root CA 2"
ROOTS[amazon-root-ca3]="Amazon Root CA 3"
ROOTS[amazon-root-ca4]="Amazon Root CA 4"
ROOTS[aaa-cert]="AAA Certificate Services"
ROOTS[usertrust-rsa]="USERTrust RSA Certification Authority"
# EXTENDED
ROOTS[microsoft-rsa-2017]="Microsoft RSA Root Certificate Authority 2017"
ROOTS[apple-root-g2]="Apple Root CA - G2"
ROOTS[apple-root-g3]="Apple Root CA - G3"
ROOTS[apple-ist-ca2-g1]="Apple IST CA 2 - G1"

typeset -A URLS
URLS[isrgrootx1]="https://letsencrypt.org/certs/isrgrootx1.pem"
URLS[isrgrootx2]="https://letsencrypt.org/certs/isrg-root-x2.pem"
URLS[gts-root-r1]="https://pki.goog/roots/gtsr1.pem"
URLS[gts-root-r2]="https://pki.goog/roots/gtsr2.pem"
URLS[gts-root-r3]="https://pki.goog/roots/gtsr3.pem"
URLS[gts-root-r4]="https://pki.goog/roots/gtsr4.pem"
URLS[digicert-global-g2]="https://cacerts.digicert.com/DigiCertGlobalRootG2.crt"
URLS[digicert-global-g3]="https://cacerts.digicert.com/DigiCertGlobalRootG3.crt"
URLS[baltimore-cybertrust]="https://cacerts.digicert.com/BaltimoreCyberTrustRoot.crt"
URLS[amazon-root-ca1]="https://www.amazontrust.com/repository/AmazonRootCA1.pem"
URLS[amazon-root-ca2]="https://www.amazontrust.com/repository/AmazonRootCA2.pem"
URLS[amazon-root-ca3]="https://www.amazontrust.com/repository/AmazonRootCA3.pem"
URLS[amazon-root-ca4]="https://www.amazontrust.com/repository/AmazonRootCA4.pem"

find_pem_in_bundle() {
  local label=$1 pattern=$2
  for cert in "$TMP_DIR"/cert-*.pem; do
    if openssl x509 -in "$cert" -noout -subject 2>/dev/null | grep -q "$pattern"; then
      cp "$cert" "$TMP_DIR/$label.pem"
      echo "  Found $label <- $pattern"
      return 0
    fi
  done
  # fallback text grep
  for cert in "$TMP_DIR"/cert-*.pem; do
    if openssl x509 -in "$cert" -noout -text 2>/dev/null | grep -q "$pattern"; then
      cp "$cert" "$TMP_DIR/$label.pem"
      echo "  Found $label <- $pattern (text)"
      return 0
    fi
  done
  return 1
}

success=0
fail=0
for label in ${(k)ROOTS}; do
  pattern="${ROOTS[$label]}"
  if find_pem_in_bundle "$label" "$pattern"; then
    success=$((success+1))
  else
    if [[ -n "${URLS[$label]:-}" ]]; then
      echo "  Trying direct URL for $label: ${URLS[$label]}"
      if curl -LfsS -o "$TMP_DIR/$label.pem" "${URLS[$label]}"; then
        echo "  Fetched $label via URL"
        success=$((success+1))
      else
        echo "  FAIL to fetch $label via URL" >&2
        fail=$((fail+1))
      fi
    else
      echo "  MISSING $label ($pattern) — will remain system-trust fallback"
      fail=$((fail+1))
    fi
  fi
done

echo "Converting PEM -> DER to $OUT_DIR..."
for pem in "$TMP_DIR"/*.pem; do
  base=$(basename "$pem")
  if [[ "$base" == cert-* ]] || [[ "$base" == cacert.pem ]]; then continue; fi
  label="${base%.pem}"
  der="$OUT_DIR/$label.der"
  if openssl x509 -in "$pem" -outform der -out "$der" 2>/dev/null; then
    echo "  $label.pem -> $label.der"
  else
    # maybe DER input
    if openssl x509 -in "$pem" -inform der -outform der -out "$der" 2>/dev/null; then
      echo "  $label (DER input) -> $label.der"
    else
      echo "  FAIL converting $pem" >&2
      rm -f "$der"
    fi
  fi
done

echo ""
echo "Result: $success found, $fail missing"
ls -lh "$OUT_DIR" 2>/dev/null
echo ""
echo "Verify: openssl x509 -in $OUT_DIR/isrgrootx1.der -inform der -noout -subject || true"

rm -rf "$TMP_DIR"
