export function escapeHtml(value) {
    return String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#039;');
}

export function formatDate(value, dateOnly = false) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return new Intl.DateTimeFormat('ko-KR', {
        year: 'numeric', month: 'short', day: 'numeric',
        hour: dateOnly ? undefined : '2-digit', minute: dateOnly ? undefined : '2-digit',
    }).format(date);
}

export function statusBadge(status) {
    const value = String(status || 'unknown').toLowerCase();
    const labels = { published: '공개', draft: '초안', archived: '보관', admin: '관리자', editor: '편집자', user: '사용자', viewer: '조회자' };
    return `<span class="status status-${escapeHtml(value)}">${escapeHtml(labels[value] || value)}</span>`;
}

export function formValues(form) { return Object.fromEntries(new FormData(form).entries()); }
export function errorText(error) {
    const fields = Object.values(error?.fieldErrors ?? {});
    return fields.length ? fields.join(' ') : error?.message || '요청을 처리하지 못했습니다.';
}
export function emptyPanel(title, description) {
    return `<div class="empty-panel"><span class="empty-icon">◇</span><h3>${escapeHtml(title)}</h3><p>${escapeHtml(description)}</p></div>`;
}
export function toast(message, type = 'success') {
    const region = document.querySelector('#toast-region');
    if (!region) return;
    const element = document.createElement('div');
    element.className = `toast toast-${type}`;
    element.textContent = message;
    region.append(element);
    setTimeout(() => element.remove(), 3600);
}
export function showFatal(error, target = document.querySelector('.page')) {
    if (target) target.innerHTML = emptyPanel('화면을 불러오지 못했습니다.', errorText(error));
    toast(errorText(error), 'error');
}
