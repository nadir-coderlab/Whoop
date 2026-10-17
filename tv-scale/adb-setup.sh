#!/usr/bin/env bash
# تركيب «ميزاني TV» على البوكس عن طريق ADB (من ماك أو لينكس على نفس الواي فاي)
#   ./adb-setup.sh 192.168.1.50
# قبلها: في البوكس فعّل «خيارات المطوّرين» ثم «تصحيح الأخطاء عبر الشبكة/ADB».
set -u
IP="${1:?اكتب عنوان البوكس، مثل: ./adb-setup.sh 192.168.1.50}"
APK_URL="https://github.com/nadir-coderlab/Whoop/releases/download/tv-latest/MizaniTV.apk"
PKG=com.mizani.tv

echo "١) الاتصال بالبوكس… (لو طلع سؤال على شاشة التلفزيون وافق عليه)"
adb connect "$IP:5555" && sleep 2 && adb -s "$IP:5555" get-state || { echo "ما قدرت أتصل. تأكد إن ADB مفعّل وإن العنوان صح"; exit 1; }
A="adb -s $IP:5555"

echo "٢) تنزيل التطبيق وتثبيته…"
curl -fsSL -o /tmp/MizaniTV.apk "$APK_URL" || { echo "ما قدرت أنزّل التطبيق من $APK_URL"; exit 1; }
$A install -r /tmp/MizaniTV.apk

echo "٣) الأذونات (اللي ما تخص نسخة أندرويد حقتك تطلع عليها رسالة خطأ عادي)…"
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT ACCESS_FINE_LOCATION POST_NOTIFICATIONS; do
  $A shell pm grant $PKG android.permission.$p 2>/dev/null || true
done

echo "٤) يشتغل دايم: بدون توفير طاقة، والتلفزيون لو انطفى ما يطفّي البوكس…"
$A shell dumpsys deviceidle whitelist +$PKG
$A shell settings put global hdmi_control_auto_device_off_enabled 0

echo "٥) تشغيل التطبيق…"
$A shell am start -n $PKG/.MainActivity
echo "خلاص ✓ — صوّر رمز QR اللي على شاشة التلفزيون من جوالك وكمّل الإعداد."
