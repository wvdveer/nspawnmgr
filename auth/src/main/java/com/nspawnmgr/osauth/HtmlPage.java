package com.nspawnmgr.osauth;

/** Shared page shell + embedded CSS for auth's plain servlet-rendered pages. */
final class HtmlPage {

    /** Neutral/informational tone (login form, logout confirmation). */
    static final String TONE_NEUTRAL = "neutral";
    /** Something went wrong with the credentials themselves. */
    static final String TONE_ERROR = "error";
    /** Credentials were fine, but the account isn't permitted. */
    static final String TONE_WARN = "warn";
    /** Login succeeded. */
    static final String TONE_SUCCESS = "success";

    private static final String STYLE = """
            <style>
              :root { color-scheme: light dark; }
              * { box-sizing: border-box; }
              body {
                margin: 0;
                min-height: 100vh;
                display: flex;
                align-items: center;
                justify-content: center;
                background: #f4f5f7;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                color: #1f2328;
              }
              .card {
                background: #fff;
                border-radius: 12px;
                box-shadow: 0 1px 3px rgba(0,0,0,.1), 0 8px 24px rgba(0,0,0,.08);
                padding: 2.5rem;
                max-width: 420px;
                width: 100%;
                margin: 1rem;
              }
              h1 { margin: 0 0 .5rem; font-size: 1.4rem; }
              p { color: #57606a; line-height: 1.5; }
              label { display: block; margin: 1rem 0 .35rem; font-size: .875rem; font-weight: 600; }
              input[type=text], input[type=password] {
                width: 100%;
                padding: .55rem .7rem;
                border: 1px solid #d0d7de;
                border-radius: 6px;
                font-size: 1rem;
                font-family: inherit;
              }
              input:focus { outline: 2px solid #0969da; outline-offset: 1px; }
              /* Password show/hide toggle - wired up automatically by the script below, no
                 per-field markup needed. Mirrors root-wizard's own SetupWizardHtml.java. */
              .password-field { position: relative; }
              .password-field input { padding-right: 2.4rem; }
              .password-toggle {
                position: absolute; right: .35rem; top: 50%; transform: translateY(-50%);
                width: auto; margin: 0; padding: .2rem .3rem; background: none; border: none;
                display: inline-flex; align-items: center; justify-content: center;
                color: currentColor; cursor: pointer; opacity: .55;
              }
              .password-toggle:hover { opacity: 1; background: none; }
              button {
                margin-top: 1.5rem;
                width: 100%;
                padding: .6rem;
                background: #0969da;
                color: #fff;
                border: none;
                border-radius: 6px;
                font-size: 1rem;
                font-weight: 600;
                cursor: pointer;
              }
              button:hover { background: #0757ba; }
              a { color: #0969da; text-decoration: none; }
              a:hover { text-decoration: underline; }
              .card.error h1 { color: #cf222e; }
              .card.warn h1 { color: #9a6700; }
              .card.success h1 { color: #1a7f37; }
              @media (prefers-color-scheme: dark) {
                body { background: #0d1117; color: #e6edf3; }
                .card { background: #161b22; box-shadow: 0 1px 3px rgba(0,0,0,.4), 0 8px 24px rgba(0,0,0,.3); }
                p { color: #8b949e; }
                input[type=text], input[type=password] { background: #0d1117; border-color: #30363d; color: #e6edf3; }
                .card.error h1 { color: #ff7b72; }
                .card.warn h1 { color: #d29922; }
                .card.success h1 { color: #3fb950; }
              }
            </style>
            <script>
              // Wraps every password field on the page with a show/hide toggle - runs on every
              // page this shell renders, so no individual form needs to opt in. Uses inline SVG
              // (stroke="currentColor"), not an emoji character: confirmed live that a bare eye
              // emoji renders as a fixed-color glyph on some browser/OS combos regardless of CSS
              // color, making it nearly invisible against this card's background - currentColor
              // guarantees it always matches the button's actual (themed, opacity-adjusted) color.
              // Mirrors root-wizard's own SetupWizardHtml.java.
              var EYE_OPEN = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7Z"/><circle cx="12" cy="12" r="3"/></svg>';
              var EYE_CLOSED = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.94 10.94 0 0 1 12 19c-7 0-11-7-11-7a21.7 21.7 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 7 11 7a21.75 21.75 0 0 1-2.16 3.19M14.12 14.12a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';
              document.addEventListener('DOMContentLoaded', function () {
                document.querySelectorAll('input[type=password]').forEach(function (input) {
                  var wrapper = document.createElement('div');
                  wrapper.className = 'password-field';
                  input.parentNode.insertBefore(wrapper, input);
                  wrapper.appendChild(input);
                  var toggle = document.createElement('button');
                  toggle.type = 'button';
                  toggle.className = 'password-toggle';
                  toggle.innerHTML = EYE_OPEN;
                  toggle.setAttribute('aria-label', 'Show password');
                  toggle.addEventListener('click', function () {
                    var nowShowing = input.type === 'password';
                    input.type = nowShowing ? 'text' : 'password';
                    toggle.innerHTML = nowShowing ? EYE_CLOSED : EYE_OPEN;
                    toggle.setAttribute('aria-label', nowShowing ? 'Hide password' : 'Show password');
                  });
                  wrapper.appendChild(toggle);
                });
              });
            </script>
            """;

    private HtmlPage() {
    }

    static String render(String title, String tone, String bodyHtml) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8"/>
                <title>%s</title>
                %s
                </head>
                <body>
                <div class="card %s">
                %s
                </div>
                </body>
                </html>
                """.formatted(title, STYLE, tone, bodyHtml);
    }
}
