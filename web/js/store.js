// Lokal lagring av inställningar, foto och stämplar (allt stannar på enheten).
const KEY = 'snc-sign-settings-v1';
const defaults = {
  lang: 'en', // engelska är förvalt, som i Trace; svenska väljs i Inställningar
  name: '',
  showName: true,
  showDate: true,
  includePhoto: true,
  photo: null,      // dataURL (JPEG) från frontkameran
  stamps: [],       // [{id, name, data}] egna stämplar
};

let state = load();
function load() {
  try { return { ...defaults, ...JSON.parse(localStorage.getItem(KEY) || '{}') }; }
  catch { return { ...defaults }; }
}
export function get() { return state; }
export function set(patch) {
  state = { ...state, ...patch };
  try { localStorage.setItem(KEY, JSON.stringify(state)); }
  catch (e) { console.warn('Kunde inte spara inställningar', e); throw e; }
  return state;
}
