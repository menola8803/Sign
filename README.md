# Sign

<img src="docs/icon.png" width="128" alt="Sign-ikonen">

Utvecklare: Claes Sundman · Scandinavian Nexum Consulting AB

Android-app som signerar Word-, ODT- och PDF-dokument direkt i telefonen, i samma stil och struktur som Trace.

- **Hemskärm** med S.N.C-loggan, *Fota dokument* och *Öppna dokument*, senaste sparade dokument, Kontakter och Uppdateringar. Kugghjulet uppe till höger.
- **Fota dokument**: skanner som hittar kanterna och klarar flera sidor. Blir en PDF.
- **Öppna dokument** känner själv igen PDF, Word (.docx och .doc) och OpenDocument (.odt), även via *Öppna med* / *Dela*. Lösenordsskyddade PDF:er frågar efter lösenordet.
- **Visa var**: tryck på *Signatur*, *Foto* eller *Stämpel* och sedan där på sidan det ska stå. Dra för att flytta, hörnet ändrar storlek, krysset tar bort. Stämplar kan vridas med guldhandtaget eller reglaget.
- **Signatur 1, 2 och 3** har samma upplägg: förhandsvisning med tid, namn och datum på/av, foto bredvid på/av, kamera (främre som standard), vem som signerar och val från Kontakter. Signatur 1 ritas och är alltid på. Signatur 2 är ett tillval som fotas på papper eller hämtas från Download (bakgrunden blir genomskinlig). Signatur 3 är dold.
- Senaste signatur och foto sparas. Innan de används frågar appen om de ska användas, eller om du vill ta nya. Datumet på dokumentet är alltid dagens.
- **Spara som** PDF, Word (.docx), ODT eller en bild per sida (PNG/JPG), ett eller flera format på en gång. PDF till Word/ODT lägger in sidorna som bilder; gamla .doc kan bli en riktig Word-fil med redigerbar text.
- **Manual** under Inställningar med kapitel och sökning.
- **Sparade dokument**: grönt *Sign 1 2* = signaturfält 1 och 2 är signerade, rött *Unsigned/Osignerad* = inget fält är signerat än. Med miniatyr, status (Signerad, 1 av 2 signerade, Inte signerad), filter och stor förhandsvisning. Ofärdiga dokument sparas också.
- **Dela** till WhatsApp, Gmail, Drive med flera, skicka till en kontakt eller spara i Download.
- **Stämplar**: S.N.C-loggan i vänstra hörnet (valbar), sigillet för Scandinavian Nexum Consulting AB, Other Seals och egna loggor där bakgrunden tas bort.
- **Kontakter** A–Ö med sök, snabbknappar, svep för att ta bort med Ångra, flerval och synk åt båda hållen med telefonens kontakter.
- **Uppdateringar** (Inställningar): utvecklare, version, bygge, sök efter uppdatering och vad som är nytt (`changelog.json`).
- Engelska är förvalt, svenska väljs i Inställningar. Allt sker i telefonen – inget laddas upp.

## Installera

Varje push till `main` bygger en ny APK med GitHub Actions. Öppna
[Releases → senaste](https://github.com/menola8803/sign/releases/tag/senaste) på telefonen,
ladda ner `Sign.apk` och installera. Appen kräver Android 10 eller nyare.

Varje bygge ger två filer: `Sign.apk` (fast namn, samma länk varje gång) och `Sign-<version>.apk`.
Alla tidigare versioner sparas som egna utgåvor under [Releases](https://github.com/menola8803/Sign/releases)
(t.ex. `v1.3.12`) med en git-tagg på exakt den koden. Vid felsökning kan en äldre APK laddas ner, och en gren
kan skapas från en version med `git switch -c fix v1.3.12`.

Appen söker själv efter nya versioner (Inställningar → Uppdateringar, eller sidan Utveckling).

### Signeringsnyckel

Utan nyckel byggs en testversion som måste avinstalleras före varje uppdatering. Kör
`./skapa-signeringsnyckel.sh` (macOS/Linux) eller `skapa-signeringsnyckel.ps1` (Windows) och lägg in
`KEYSTORE_B64` och `KEYSTORE_PASSWORD` under *Settings → Secrets and variables → Actions*. Då kan varje ny
APK installeras ovanpå den förra.

## Ny version

1. Höj `version.txt` (t.ex. `1.4`).
1. Uppdatera manualen i appen (strängarna `manual_*` på svenska och engelska) med det som är nytt.
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
