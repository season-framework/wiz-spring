import { apiClient, pageUrl } from '../api.js';
import { errorText, formValues } from '../ui.js';

const themeKey = '__WIZ_ARTIFACT_ID__:jsp-theme';
document.documentElement.dataset.theme = localStorage.getItem(themeKey)
    || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
const form = document.querySelector('#login-form');
const errorBox = document.querySelector('#login-error');
document.querySelector('#demo-account').addEventListener('click', () => {
    form.elements.email.value = 'admin@example.com';
    form.elements.password.value = 'admin1234';
    form.elements.email.focus();
});
form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true; errorBox.hidden = true;
    try {
        const api = await apiClient();
        await api.post('/auth/login', formValues(form));
        location.assign(pageUrl('/dashboard'));
    } catch (error) {
        errorBox.textContent = errorText(error); errorBox.hidden = false; button.disabled = false;
    }
});
