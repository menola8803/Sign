# Sign

<img src="docs/icon.png" width="128" alt="Sign-ikonen">

Utvecklare: Claes Sundman S.N.C AB · Scandinavian Nexum Consulting AB

Android-app som signerar Word- och PDF-dokument direkt i telefonen, i samma stil och struktur som Trace.

- **Öppna** en PDF eller ett Word-dokument (.docx), från appen eller via *Öppna med* / *Dela* i andra appar.
- **Visa var**: tryck på *Signatur*, *Foto* eller *Stämpel* och sedan där på sidan det ska stå. Dra för att flytta, blå punkt ändrar storlek, rött kryss tar bort.
- **Signera** i liggande läge – skärmen vänds automatiskt. Namn och datum skrivs alltid under signaturen.
- **Foto** med frontkameran (Inställningar). Läggs automatiskt bredvid signaturen och skalas så att det får plats.
- **S.N.C-loggan** placeras i vänstra hörnet på första sidan (kan stängas av). Sigillet för Scandinavian Nexum Consulting AB finns inbyggt, och fler stämplar kan läggas till som bilder.
- **Spara** som Word (.docx) eller PDF. PDF-filer behåller text och kvalitet. Spara, dela eller skicka till en kontakt.
- **Kontakter** med namn, e-post och telefon. Välj vem som signerar; namnet skrivs under signaturen.
- **Utveckling**: hämtar uppdateringar från GitHub och visar vad varje version innebär och vad tidigare versioner innehöll (`changelog.json`).
- Engelska är förvalt, svenska väljs i Inställningar. Allt sker i telefonen – inget laddas upp.

## Installera

Varje push till `main` bygger en ny APK med GitHub Actions. Öppna
[Releases → senaste](https://github.com/menola8803/sign/releases/tag/senaste) på telefonen,
ladda ner `Sign.apk` och installera. Appen kräver Android 10 eller nyare.

Appen söker själv efter nya versioner (Inställningar → Uppdateringar, eller sidan Utveckling).

### Signeringsnyckel

Utan nyckel byggs en testversion som måste avinstalleras före varje uppdatering. Kör
`./skapa-signeringsnyckel.sh` (macOS/Linux) eller `skapa-signeringsnyckel.ps1` (Windows) och lägg in
`KEYSTORE_B64` och `KEYSTORE_PASSWORD` under *Settings → Secrets and variables → Actions*. Då kan varje ny
APK installeras ovanpå den förra.

## Ny version

1. Höj `version.txt` (t.ex. `1.1`).
2. Lägg en ny post överst i `changelog.json` med vad versionen innebär och innehåller, på svenska och engelska.
3. Pusha till `main`. Appen visar uppdateringen och texterna på sidan Utveckling.

## Struktur

| Mapp | Innehåll |
|---|---|
| `app/` | Android-appen (Kotlin), samma upplägg som Trace |
| `web/` | Webbversion för iPhone och dator (PWA). Enklare: signatur, stämplar och foto, sparar som PDF |
| `scripts/make-icons.py` | Ritar ikonerna i Trace-stil |
| `changelog.json` | Versionshistoriken som appen läser |

Bygga själv: öppna mappen i Android Studio och tryck **Run**. Ikonerna ritas om med `python3 scripts/make-icons.py`.
