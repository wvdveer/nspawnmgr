// Drives auth.war's plain servlet-rendered login form (auth/src/main/java/com/nspawnmgr/osauth/
// LoginServlet.java) to get a real session cookie set for this origin, then confirms nspawnmgr
// itself recognizes it. Every other suite in this harness assumes this one has already run in the
// same browser session (the cookie is origin-scoped, not tab-scoped, so it doesn't matter which
// tab is open afterward).
//
// Needs a real OS account (PAM, or an SMB account with the required share grant - see
// tools/scripts/start-dev-stack.sh's AUTH_BACKEND handling and CLAUDE.md/memory for this
// machine's own setup). Deliberately NOT hardcoded here like a disposable DB fixture would be -
// this is a real account on the box running the dev stack, so the harness page asks for it
// instead (see index.html's "OS account" fields).
async function runAuthTests(username, password) {
    const h = window.harness;
    h.section('login: auth.war form -> nspawnmgr recognizes the session');

    if (!username || !password) {
        h.fail('OS username/password required', 'fill them in above before running this suite');
        return;
    }

    const nspawnmgrRoot = `${location.origin}/nspawnmgr/`;
    const win = h.openApp('nspawnmgr', `${location.origin}/auth/login?returnTo=${encodeURIComponent(nspawnmgrRoot)}`);

    await h.step('login form renders', async () => {
        await h.waitForReload(win);
        await h.waitForSelector(win, 'input[name=username]', { timeout: 8000 });
        h.assertContains(h.bodyText(win), 'Log in', 'login form heading visible');
    });

    await h.step('submitting valid credentials redirects to nspawnmgr', async () => {
        h.fill(win, 'input[name=username]', username);
        h.fill(win, 'input[name=password]', password);
        h.clickByText(win, 'button', 'Log in');
        await h.waitFor(() => win.location.pathname.startsWith('/nspawnmgr'), {
            timeout: 8000,
            label: 'redirect to /nspawnmgr/',
        });
        await h.waitForText(win, 'Containers', { timeout: 8000 });
        h.assertNotContains(h.bodyText(win), 'Login required', 'nspawnmgr accepted the session cookie');
        h.pass('signed in and landed on the containers list');
    });
}
