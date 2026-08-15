(function () {
    const STORAGE_KEY = 'nspawnmgr-theme';
    const lightBtn = document.getElementById('theme-toggle-light');
    const darkBtn = document.getElementById('theme-toggle-dark');

    function currentTheme() {
        const stored = localStorage.getItem(STORAGE_KEY);
        if (stored === 'light' || stored === 'dark') {
            return stored;
        }
        return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }

    function applyActiveState() {
        const theme = currentTheme();
        lightBtn?.classList.toggle('active', theme === 'light');
        darkBtn?.classList.toggle('active', theme === 'dark');
    }

    function setTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(STORAGE_KEY, theme);
        applyActiveState();
    }

    lightBtn?.addEventListener('click', () => setTheme('light'));
    darkBtn?.addEventListener('click', () => setTheme('dark'));
    applyActiveState();
})();
