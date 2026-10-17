@echo off
chcp 65001 >nul
rem تركيب «ميزاني TV» على البوكس عن طريق ADB (من كمبيوتر ويندوز على نفس الواي فاي)
rem   adb-setup.bat 192.168.1.50
rem قبلها: في البوكس فعّل «خيارات المطوّرين» ثم «تصحيح الأخطاء عبر الشبكة/ADB».
if "%~1"=="" ( echo اكتب عنوان البوكس، مثل: adb-setup.bat 192.168.1.50 & exit /b 1 )
set IP=%~1:5555
set PKG=com.mizani.tv
echo 1) الاتصال بالبوكس... (لو طلع سؤال على شاشة التلفزيون وافق عليه)
adb connect %IP%
timeout /t 2 >nul
echo 2) تنزيل التطبيق وتثبيته...
curl -fsSL -o "%TEMP%\MizaniTV.apk" https://github.com/nadir-coderlab/Whoop/releases/download/tv-latest/MizaniTV.apk
adb -s %IP% install -r "%TEMP%\MizaniTV.apk"
echo 3) الأذونات...
for %%p in (BLUETOOTH_SCAN BLUETOOTH_CONNECT ACCESS_FINE_LOCATION POST_NOTIFICATIONS) do adb -s %IP% shell pm grant %PKG% android.permission.%%p 2>nul
echo 4) يشتغل دايم...
adb -s %IP% shell dumpsys deviceidle whitelist +%PKG%
adb -s %IP% shell settings put global hdmi_control_auto_device_off_enabled 0
echo 5) تشغيل التطبيق...
adb -s %IP% shell am start -n %PKG%/.MainActivity
echo خلاص - صوّر رمز QR اللي على شاشة التلفزيون من جوالك وكمّل الإعداد.
