(function () {
    var stored = localStorage.getItem('nspawnmgr-theme');
    if (stored === 'light' || stored === 'dark') {
        document.documentElement.setAttribute('data-theme', stored);
    }
})();
