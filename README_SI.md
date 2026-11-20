# NEXUS AI Android — v1.5.0

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

## Device Link සහ Controller / Target APK

මෙම release එකේ Android APK දෙකක් වෙන වෙනම build කරයි.

- **Controller APK:** Chat, Notes, Saved, Memory, Project Progress, Settings, Pairing Code Generate කිරීම, Paired Devices Dashboard සහ Target Wake Signal යැවීම.
- **Target APK:** Controller වෙතින් ලැබෙන Pairing Code එක Claim කිරීම, Target setup සහ Settings. Controller Dashboard, Pairing Code Generate කිරීම සහ Controller Voice Engine පෙන්වන්නේ නැත.
- APK දෙකම `com.nexusai.app` package ID එක සහ දැනට භාවිත කරන එකම release signing key එක රඳවා ගනී. ඒවා වෙන වෙනම භෞතික දුරකථන දෙකකට ස්ථාපනය කළ යුතුය; එකම දුරකථනයේ දෙකම එකවර ස්ථාපනය කළ නොහැක.
- Pairing සහ device role තීරණය backend එකෙන් තහවුරු කරයි. APK එකේ UI role marker එක පමණක් authorization ලෙස භාවිත නොකරයි.
- Firebase Android configuration එක GitHub Actions Secret එකෙන් build වෙලාවේ පමණක් ලබාගනී; repository එකට commit නොකරයි.
- Pair වූ උපාංග දෙකම FCM token register කරයි. Controller එකෙන් Target එකට Wake Signal යැවූ විට Target එක authenticated heartbeat එකක් යවා last-seen තත්ත්වය යාවත්කාලීන කරයි. Notification permission ලබාදී ඇත්නම් notification එකක් ද පෙන්වයි.
- මෙම FCM wake පහසුකම Remote Screen Capture, Remote Touch Control, Camera Streaming හෝ Microphone Streaming නොවේ. එම පහසුකම් මෙම release එකෙන් සපයන්නේ නැත.

## Update / Signing

- Application ID: `com.nexusai.app`
- Version: `1.5.0`
- Version Code: `14`
- සෑම installable update එකකදීම versionCode වැඩි කළ යුතුය.
- දැනට ඇති app එකට update install කිරීමට එකම package ID සහ එකම signing key භාවිත කරයි.
- Signing secrets GitHub Actions secrets තුළ පමණක් තබාගන්න; keystore/password values source code හෝ chat තුළ ඇතුළත් නොකරන්න.

## Build තත්ත්වය

GitHub Actions run #131 සාර්ථකව අවසන් වී ඇත. Controller සහ Target signed Release APK දෙකම නිර්මාණය කර, signature, package ID, versionCode 14, versionName සහ role-specific launcher label CI තුළ සාර්ථකව පරීක්ෂා කර artifacts ලෙස upload කර ඇත. ඉතිරිව ඇත්තේ APK දෙක වෙන වෙනම දුරකථනවල install කර Pairing සහ FCM delivery සැබෑ උපාංගවල පරීක්ෂා කිරීමයි.

## ආරක්ෂාව

- API keys සහ service-role secrets Android APK තුළ hardcode නොකරන්න.
- Chat backend එකේ user authentication සහ authorization server-side තහවුරු කළ යුතුය.
- දෙවන device එකකට commands යැවීමේදී device ownership, explicit pairing, revocation සහ audit log අනිවාර්ය කරන්න.

