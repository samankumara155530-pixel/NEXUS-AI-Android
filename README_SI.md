# NEXUS AI Android — v1 project scaffold

මෙය Android Studio / GitHub Actions මඟින් APK එක build කිරීමට සකස් කළ Kotlin + Jetpack Compose ව්‍යාපෘති මූලිකයයි.

## දැනට ඇතුළත්
- Sinhala-first Android UI
- Dark / Light mode toggle
- Sinhala / English UI toggle (මූලික labels)
- Chat screen UI (AI backend එක තවම සම්බන්ධ කර නැත)
- Notes screen: සටහන් මේ session එකේ app memory තුළ තාවකාලිකයි; ස්ථිර database එකක් තවම සම්බන්ධ කර නැත
- Voice input button: Android speech recognizer intent එක අමතයි
- Camera button: Android camera intent එක අමතයි
- Calendar button: Calendar insert intent එක අමතයි
- Settings / Update Center placeholder

## වැදගත් සීමා
- මෙය සම්පූර්ණ AI assistant එකක් නොවේ. සැබෑ online AI සඳහා ආරක්ෂිත server/backend සහ API key සැකසුම අවශ්‍යයි.
- Offline AI model එකක් ඇතුළත් කර නැත.
- Update Center එක UI placeholder එකකි; server/download/update installation ක්‍රියාකාරී නොවේ.
- ZIP එක source project එකකි; APK එකක් ලෙස build කර පරීක්ෂා කර ඇති බවට මෙහි ප්‍රකාශයක් නැත.
- `gradlew` wrapper script/jar මෙහි ඇතුළත් කර නැත. GitHub Actions workflow එක Gradle/Android SDK සකස් කර build කිරීමට උත්සාහ කරයි.

## Build
Android Studio හි project root එක open කර Sync/Build කරන්න, නැතහොත් GitHub repository එකට upload කර Actions workflow එක run කරන්න.
