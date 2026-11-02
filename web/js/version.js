// Uppdatera APP_VERSION (och CACHE i sw.js) när version.txt och changelog.json höjs.
export const APP_VERSION = '1.0';
export const DEVELOPER = 'Claes Sundman';
export const COMPANY = 'S.N.C AB';
export const COMPANY_FULL = 'Scandinavian Nexum Consulting AB';
export const REPO = 'menola8803/sign';
// Var uppdateringslistan hämtas. Android-appen går direkt till GitHub, webbappen först till sin egen server.
export const UPDATE_SOURCES = {
  remote: [
    `https://raw.githubusercontent.com/${REPO}/main/changelog.json`,
  ],
  local: 'changelog.json', // kopieras in av Pages-bygget
};
export const APK_URL = `https://github.com/${REPO}/releases/download/senaste/Sign.apk`;
