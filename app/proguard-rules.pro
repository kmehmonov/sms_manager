# ProGuard qoidalari — kodni qisqartirish va optimizatsiya uchun
# Debug build'da ishlatilmaydi, faqat Release'da

# Standart Android qoidalari
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service

# ViewModel'larni saqlaymiz
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Data class'larni saqlaymiz
-keep class com.smsmanager.app.SmsContact { *; }
-keep class com.smsmanager.app.SmsLogEntry { *; }
