package com.nspawnmgr.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An admin-defined key combination shown as a button in the Keystroke Button Panel (see
 * {@link KeystrokeButtonPanelPlacement}) on any container/host session page that has the panel
 * enabled - global, not per-container, since the whole point is a small shared set of well-known
 * combinations (Ctrl+Alt+Delete, Ctrl+Shift+Escape, ...) an admin curates once for everyone.
 *
 * <p>The four modifiers are plain booleans, not a generic modifier list, since only these four are
 * ever meaningful for a remote-desktop keystroke and this keeps both the admin form and the
 * session-page button handler simple - no ordering ambiguity to store or resolve, the button
 * handler always presses them in a fixed order (ctrl, alt, shift, super) and releases in the
 * reverse order. {@code mainKeysym} is the one genuinely free choice, stored as a raw X11 keysym
 * (see {@code KeySequenceService.MAIN_KEY_OPTIONS} for the curated dropdown of values an admin can
 * actually pick - deliberately not free-form keysym entry, so an admin never needs to know what an
 * X11 keysym even is).
 */
@Entity
@Table(name = "key_sequences")
@Getter
@Setter
@NoArgsConstructor
public class KeySequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private boolean ctrl;

    @Column(nullable = false)
    private boolean alt;

    @Column(nullable = false)
    private boolean shift;

    @Column(name = "super_key", nullable = false)
    private boolean superKey;

    @Column(name = "main_keysym", nullable = false)
    private int mainKeysym;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public KeySequence(String label, boolean ctrl, boolean alt, boolean shift, boolean superKey, int mainKeysym, int sortOrder) {
        this.label = label;
        this.ctrl = ctrl;
        this.alt = alt;
        this.shift = shift;
        this.superKey = superKey;
        this.mainKeysym = mainKeysym;
        this.sortOrder = sortOrder;
    }
}
