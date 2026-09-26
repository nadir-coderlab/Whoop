# لوحة WHOOP التلقائية

كل يوم الساعة 8:17 الصبح بتوقيت الرياض يشتغل GitHub Actions بنفسه:

1. يفك بياناتك المحفوظة من الأيام اللي قبل (مشفرة)
2. يسحب الجديد من WHOOP (Recovery وHRV والنوم ومراحله والتمارين والنبض كل دقيقة)
3. يحسب التحليلات ويشفر النتيجة
4. ينشر الصفحة على `https://nadir-coderlab.github.io/Whoop/`

## وين تروح بياناتك

| الشي | مكانه | مين يشوفه |
|---|---|---|
| الكود | هذا المستودع | عام (ما فيه أي بيانات) |
| بياناتك الخام | GitHub Actions cache كملف واحد مشفر AES-256 | محد |
| ملف الصفحة | GitHub Pages مشفر AES-256 | ما ينفتح إلا بكلمة المرور |
| إيميل WHOOP وباسورده | GitHub Secrets | محد (حتى أنت ما تقدر تقراها بعد الحفظ) |

المستودع ما ينحفظ فيه أي رقم صحي. ولو انمسح الـ cache في يوم التشغيل الجاي يسحب كل تاريخك من جديد.

## الإعداد (مرة وحدة)

### 1. الأسرار
**Settings ← Secrets and variables ← Actions ← New repository secret**

| الاسم | القيمة |
|---|---|
| `WHOOP_USERNAME` | إيميل WHOOP |
| `WHOOP_PASSWORD` | باسورد WHOOP |
| `DASHBOARD_PASSWORD` | كلمة مرور الصفحة (طويلة: 4 كلمات أو أكثر) |

لو تدخل WHOOP بحساب Apple أو Google سوّ "نسيت كلمة المرور" من موقع WHOOP وحط باسورد.

### 2. تفعيل الصفحة
**Settings ← Pages ← Build and deployment ← Source: GitHub Actions**

### 3. أول تشغيل
**Actions ← WHOOP daily sync ← Run workflow**

أول مرة ياخذ من 15 لـ 40 دقيقة لأنه يسحب كل تاريخك. بعدها كل يوم دقيقة أو دقيقتين.

## إعدادات اختيارية
**Settings ← Secrets and variables ← Actions ← Variables**

| الاسم | الفايدة |
|---|---|
| `WHOOP_START` | أول تاريخ تبيه مثل `2023-05-01` (بدونه يكتشف أول يوم تلقائي) |

## لو صار خطأ
- GitHub يرسل لك إيميل لو فشل التشغيل
- افتح **Actions** ← آخر تشغيل ← الخطوة الحمراء
- أشهر سبب: WHOOP غيروا نظامهم الداخلي أو رفضوا الدخول من سيرفرات GitHub
- لو غيرت `DASHBOARD_PASSWORD` التشغيل الجاي يسحب كل شي من جديد (لأن البيانات القديمة مشفرة بالكلمة القديمة)

## تشغيله على الماك

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python3 whoop_export.py --out data
DASHBOARD_PASSWORD="كلمة المرور" python3 analyze.py --csv data/csv --out site
cd site && python3 -m http.server 8000
```

وافتح `http://localhost:8000`

## الملفات

| الملف | وش يسوي |
|---|---|
| `whoop_export.py` | يسحب البيانات من WHOOP ويحولها CSV |
| `analyze.py` | يحسب التحليلات ويشفرها في `site/data.enc.json` |
| `vault.py` | يشفر البيانات الخام ويفكها بين التشغيلات |
| `site/index.html` | الصفحة (تفك التشفير في المتصفح) |
| `.github/workflows/whoop.yml` | التشغيل اليومي |
