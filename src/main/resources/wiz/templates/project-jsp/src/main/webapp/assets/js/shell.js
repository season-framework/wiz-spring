import { apiClient, pageUrl } from './api.js';
import { errorText, toast } from './ui.js';

const themeKey = '__WIZ_ARTIFACT_ID__:jsp-theme';
const savedTheme = localStorage.getItem(themeKey);
document.documentElement.dataset.theme = savedTheme === 'dark' || savedTheme === 'light'
    ? savedTheme
    : (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');

function toggleTheme() {
    const theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = theme;
    localStorage.setItem(themeKey, theme);
}

document.querySelector('#theme-toggle')?.addEventListener('click', toggleTheme);
document.querySelector('#mobile-theme-toggle')?.addEventListener('click', toggleTheme);
const sidebar = document.querySelector('#sidebar');
const backdrop = document.querySelector('#sidebar-backdrop');
document.querySelector('#sidebar-toggle')?.addEventListener('click', () => {
    sidebar?.classList.add('is-open');
    if (backdrop) backdrop.hidden = false;
});
backdrop?.addEventListener('click', () => {
    sidebar?.classList.remove('is-open');
    backdrop.hidden = true;
});
document.querySelector('#logout-button')?.addEventListener('click', async event => {
    event.currentTarget.disabled = true;
    try {
        const api = await apiClient();
        await api.post('/auth/logout');
        location.assign(pageUrl('/access'));
    } catch (error) {
        toast(errorText(error), 'error');
        event.currentTarget.disabled = false;
    }
});
