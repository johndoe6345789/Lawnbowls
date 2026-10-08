#!/usr/bin/env bash
# Builds, aligns and signs the Lawn Bowls APK against Android 17 (API 37).
set -euo pipefail
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS

BT=/home/claude/sdk/android-37.0           # build-tools 37 + platform 37 (android.jar)
AJ=$BT/android.jar
MIN_SDK=30; TARGET_SDK=37
OUT=build

rm -rf $OUT && mkdir -p $OUT/classes

# 1. resources + manifest (aapt2)
$BT/aapt2 compile --dir res -o $OUT/res.zip
$BT/aapt2 link -o $OUT/base.apk -I $AJ --manifest AndroidManifest.xml \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --version-code 4 --version-name 4.0 --auto-add-overlay $OUT/res.zip

# 2. compile Java and convert to dex (release: no debug info)
javac --release 11 -Xlint:all -Xlint:-options -cp $AJ -d $OUT/classes src/com/example/lawnbowls/*.java
$BT/d8 --release --min-api $MIN_SDK --lib $AJ --output $OUT $(find $OUT/classes -name '*.class')

# 3. add classes.dex to the apk
python3 - <<'EOF'
import zipfile
with zipfile.ZipFile('build/base.apk', 'a') as z:
    z.write('build/classes.dex', 'classes.dex', zipfile.ZIP_DEFLATED)
EOF

# 4. align (4-byte, 16 KB page aligned) then sign with v2+v3 only (v1 not needed for minSdk 30)
$BT/zipalign -P 16 -f 4 $OUT/base.apk $OUT/aligned.apk
if [ ! -f signing.p12 ]; then
    python3 -c "import secrets;print(secrets.token_urlsafe(24))" > signing.pass
    keytool -genkeypair -storetype PKCS12 -keystore signing.p12 -storepass:file signing.pass \
        -keypass:file signing.pass -alias lawnbowls -keyalg RSA -keysize 4096 -sigalg SHA384withRSA \
        -validity 9125 -dname "CN=Lawn Bowls, O=Lawn Bowls" >/dev/null 2>&1
    chmod 600 signing.p12 signing.pass
fi
$BT/apksigner sign --ks signing.p12 --ks-pass file:signing.pass \
    --min-sdk-version $MIN_SDK --v1-signing-enabled false --v2-signing-enabled true \
    --v3-signing-enabled true --out $OUT/lawn_bowls.apk $OUT/aligned.apk
rm -f $OUT/lawn_bowls.apk.idsig
echo built $OUT/lawn_bowls.apk
