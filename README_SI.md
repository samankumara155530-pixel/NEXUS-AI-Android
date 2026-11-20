# NEXUS AI Android — v1.4.2

මෙය Kotlin + Jetpack Compose භාවිතයෙන් සකස් කළ NEXUS AI Android යෙදුමයි. Android APK එක GitHub Actions මඟින් build කර පරීක්ෂා කර ඇත.

## දැනට ඇතුළත් පහසුකම්

- Sinhala-first UI සහ Sinhala / English භාෂා මාරු කිරීම
- Dark / Light theme තේරීම
- NEXUS AI chat — සම්බන්ධිත backend endpoint වෙත HTTPS නොවන/HTTPS URL සැකසුම අනුව HTTP request යවයි; chat සාර්ථකත්වය backend සහ login token මත රඳා පවතී
- Chat request එකට පෙර chat history සහ app memory සීමිත ප්‍රමාණයක් යැවීම
- Notes සහ local app memory පහසුකම්
- Android speech recognition intent මඟින් voice input
- Android camera intent මඟින් camera විවෘත කිරීම
- Calendar insert intent මඟින් පෙර පිරවූ event form එකක් විවෘත කිරීම
- Settings සහ Update Center UI

## Calendar පිළිබඳ සටහන

Calendar බොත්තම Android Calendar event form එකක් විවෘත කරයි. දැනට එය “NEXUS AI Event” යන පෙරනිමි මාතෘකාව සහ ඉදිරි පැයක ආරම්භක වේලාවක් යොදයි. පරිශීලකයා Calendar යෙදුමේදී විස්තර පරීක්ෂා කර Save කළ යුතුය. Chat විධානයකින් ස්වයංක්‍රීයව දිනය/වේලාව විග්‍රහ කර event එකක් සුරැකීම තවම සම්පූර්ණ කර නැත.

## Device Link පිළිබඳ තත්ත්වය

දෙවන Android දුරකථනය Pair කිරීම, දුරස්ථ app launch, සහ අවසර කළමනාකරණය තවම ක්‍රියාත්මක කර නැත. ඒ සඳහා authenticated backend pairing flow එකක්, device registration, සහ අවසර මත පදනම් වූ Android-side actions අවශ්‍ය වේ. Android platform restrictions අනුව Camera, Microphone, Location වැනි සංවේදී පහසුකම් සඳහා අවශ්‍ය පරිශීලක අවසර අනිවාර්ය වේ.

## Update / Signing

- Application ID: `com.nexusai.app`
- Version: `1.4.2`
- Version Code: `8`
- සෑම installable update එකකදීම versionCode වැඩි කළ යුතුය.
- දැනට ඇති app එකට update install කිරීමට එකම package ID සහ එකම signing key භාවිත කළ යුතුය.
- Signing secrets GitHub Actions secrets තුළ පමණක් තබාගන්න; keystore/password values source code හෝ chat තුළ ඇතුළත් නොකරන්න.

## Build තත්ත්වය

GitHub Actions run #85 සාර්ථකව අවසන් වී ඇති අතර release APK artifact එක නිර්මාණය වී ඇත. එය සැබෑ දුරකථනයක install කර Calendar, chat, camera, permissions සහ update-installation පරීක්ෂා කිරීම තවම වෙනම කළ යුතුය.

## ආරක්ෂාව

- API keys සහ service-role secrets Android APK තුළ hardcode නොකරන්න.
- Chat backend එකේ user authentication සහ authorization server-side තහවුරු කළ යුතුය.
- දෙවන device එකකට commands යැවීමේදී device ownership, explicit pairing, revocation සහ audit log අනිවාර්ය කරන්න.


## Controller සහ Target APK වෙන් කිරීම

- `feature/controller-target-apk-split` branch එකේ Controller සහ Target Build Variants දෙක සකස් කරමින් පවතී.
- Controller APK එකෙන් Pairing කේත සාදා Paired Devices ලැයිස්තුව පාලනය කළ හැක.
- Target APK එකෙන් Controller දුරකථනයෙන් ලැබෙන Pairing කේතය පමණක් ඇතුළත් කළ හැක.
- APK දෙකම දැනට තිබෙන `com.nexusai.app` Package ID එක සහ එකම Release Signing Key එක භාවිත කරයි. ඒවා වෙන වෙනම දුරකථන දෙකකට ස්ථාපනය කිරීමට සැලසුම් කර ඇත.
- Firebase Android Config එක GitHub Actions Secret එකෙන් build වෙලාවේ පමණක් ලබාගනී; repository එකට commit නොකරයි.
- FCM wake message එක ලැබුණු විට Target දුරකථනය authenticated heartbeat එකක් යවා last-seen තත්ත්වය යාවත්කාලීන කරයි. Notification permission ලබාදී ඇත්නම් දැනුම්දීමක් ද පෙන්වයි.
- FCM wake එක Remote Screen හෝ Touch Control ක්‍රියාත්මක බව අදහස් නොකරයි. එම පහසුකම් සඳහා වෙනම Android consent සහ permission ක්‍රියාත්මක කිරීම අවශ්‍යය.

සවිස්තර සැලැස්ම: `docs/CONTROLLER_TARGET_APK_SPLIT.md`
