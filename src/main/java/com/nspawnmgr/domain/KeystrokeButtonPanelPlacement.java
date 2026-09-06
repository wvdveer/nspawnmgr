package com.nspawnmgr.domain;

/**
 * Where (if at all) a container/host's session page shows its Keystroke Button Panel - the row of
 * admin-configured buttons (see {@link KeySequence}) that send a key sequence directly to the
 * live Guacamole client, for combinations a browser can never deliver as a real keypress (Ctrl+Alt+
 * Delete being the canonical example - the local OS/browser intercepts it before any web page ever
 * sees it). {@code NONE} is the default: this is new, opt-in UI real estate on the session page, not
 * something every existing container/host should suddenly grow without an admin choosing it.
 */
public enum KeystrokeButtonPanelPlacement {
    NONE, ABOVE, LEFT
}
