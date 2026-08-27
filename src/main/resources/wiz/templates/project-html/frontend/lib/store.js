const THEME_KEY = '__WIZ_ARTIFACT_ID__:theme';

function initialTheme() {
  const saved = localStorage.getItem(THEME_KEY);
  if (saved === 'light' || saved === 'dark') return saved;
  return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function createStore() {
  const listeners = new Set();
  const state = {
    session: { authenticated: false },
    theme: initialTheme(),
  };
  const emit = () => listeners.forEach(listener => listener(state));
  return {
    get session() { return state.session; },
    get theme() { return state.theme; },
    setSession(session) { state.session = session ?? { authenticated: false }; emit(); },
    setTheme(theme) {
      state.theme = theme === 'dark' ? 'dark' : 'light';
      localStorage.setItem(THEME_KEY, state.theme);
      emit();
    },
    toggleTheme() { this.setTheme(state.theme === 'dark' ? 'light' : 'dark'); },
    subscribe(listener) { listeners.add(listener); listener(state); return () => listeners.delete(listener); },
  };
}
