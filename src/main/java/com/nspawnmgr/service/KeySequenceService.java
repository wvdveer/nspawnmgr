package com.nspawnmgr.service;

import com.nspawnmgr.domain.KeySequence;
import com.nspawnmgr.repository.KeySequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD for the Keystroke Button Panel's global, admin-curated list of key sequences - see
 * {@link KeySequence}'s own javadoc for why this is global rather than per-container.
 */
@Service
public class KeySequenceService {

    /** One option in the admin form's "main key" dropdown - named X11 keysyms (see
     *  <a href="https://www.cl.cam.ac.uk/~mgk25/ucs/keysymdef.h">keysymdef.h</a>) rather than
     *  free-form keysym entry, so an admin picking "Delete" never needs to know it's really
     *  0xFFFF. Covers every non-printable key a remote-desktop shortcut plausibly needs, plus
     *  every printable letter/digit/punctuation key (added 2026-09-06) - the full direct-ASCII
     *  keysym range needed for shortcuts like Ctrl+Alt+F2 (a digit, not an F-key) or app-specific
     *  bindings on a letter/symbol key. */
    public record MainKeyOption(String name, int keysym) {
    }

    /** Letters/digits/punctuation in the printable ASCII range (0x0021-0x007E) use their ASCII
     *  code directly as the X11 keysym - confirmed by keysymdef.h's own "for the direct mapping"
     *  comment (already relied on above for Space, 0x0020). Letters are keyed by their lowercase
     *  (unshifted) keysym - matching a real keyboard, where the bare "A" key sends lowercase 'a'
     *  and Shift produces the uppercase form - but labeled with the uppercase glyph to match how
     *  the physical keycap itself is printed; the existing Shift checkbox already covers a
     *  deliberately-uppercase combination (e.g. Ctrl+Shift+A) without needing a second "shifted
     *  letter" entry. Digits and punctuation are exact ASCII round-trips, both label and keysym. */
    private static MainKeyOption ascii(String label, char asciiChar) {
        return new MainKeyOption(label, asciiChar);
    }

    public static final List<MainKeyOption> MAIN_KEY_OPTIONS = List.of(
            new MainKeyOption("Delete", 0xFFFF),
            new MainKeyOption("Escape", 0xFF1B),
            new MainKeyOption("Tab", 0xFF09),
            new MainKeyOption("Backspace", 0xFF08),
            new MainKeyOption("Enter", 0xFF0D),
            new MainKeyOption("Space", 0x0020),
            new MainKeyOption("Insert", 0xFF63),
            new MainKeyOption("Home", 0xFF50),
            new MainKeyOption("End", 0xFF57),
            new MainKeyOption("Page Up", 0xFF55),
            new MainKeyOption("Page Down", 0xFF56),
            new MainKeyOption("Up", 0xFF52),
            new MainKeyOption("Down", 0xFF54),
            new MainKeyOption("Left", 0xFF51),
            new MainKeyOption("Right", 0xFF53),
            new MainKeyOption("Print Screen", 0xFF61),
            new MainKeyOption("F1", 0xFFBE), new MainKeyOption("F2", 0xFFBF),
            new MainKeyOption("F3", 0xFFC0), new MainKeyOption("F4", 0xFFC1),
            new MainKeyOption("F5", 0xFFC2), new MainKeyOption("F6", 0xFFC3),
            new MainKeyOption("F7", 0xFFC4), new MainKeyOption("F8", 0xFFC5),
            new MainKeyOption("F9", 0xFFC6), new MainKeyOption("F10", 0xFFC7),
            new MainKeyOption("F11", 0xFFC8), new MainKeyOption("F12", 0xFFC9),
            // Digits
            ascii("0", '0'), ascii("1", '1'), ascii("2", '2'), ascii("3", '3'), ascii("4", '4'),
            ascii("5", '5'), ascii("6", '6'), ascii("7", '7'), ascii("8", '8'), ascii("9", '9'),
            // Letters - see this field's own comment above for why the keysym is lowercase but
            // the label is uppercase.
            ascii("A", 'a'), ascii("B", 'b'), ascii("C", 'c'), ascii("D", 'd'), ascii("E", 'e'),
            ascii("F", 'f'), ascii("G", 'g'), ascii("H", 'h'), ascii("I", 'i'), ascii("J", 'j'),
            ascii("K", 'k'), ascii("L", 'l'), ascii("M", 'm'), ascii("N", 'n'), ascii("O", 'o'),
            ascii("P", 'p'), ascii("Q", 'q'), ascii("R", 'r'), ascii("S", 's'), ascii("T", 't'),
            ascii("U", 'u'), ascii("V", 'v'), ascii("W", 'w'), ascii("X", 'x'), ascii("Y", 'y'),
            ascii("Z", 'z'),
            // Punctuation - label shows the literal character plus a name, since a bare symbol
            // (especially , . ' `) is easy to misread/misclick in a dropdown list.
            ascii("! (Exclamation)", '!'), ascii("\" (Quote)", '"'), ascii("# (Hash)", '#'),
            ascii("$ (Dollar)", '$'), ascii("% (Percent)", '%'), ascii("& (Ampersand)", '&'),
            ascii("' (Apostrophe)", '\''), ascii("( (Left Paren)", '('), ascii(") (Right Paren)", ')'),
            ascii("* (Asterisk)", '*'), ascii("+ (Plus)", '+'), ascii(", (Comma)", ','),
            ascii("- (Minus)", '-'), ascii(". (Period)", '.'), ascii("/ (Slash)", '/'),
            ascii(": (Colon)", ':'), ascii("; (Semicolon)", ';'), ascii("< (Less Than)", '<'),
            ascii("= (Equals)", '='), ascii("> (Greater Than)", '>'), ascii("? (Question)", '?'),
            ascii("@ (At)", '@'), ascii("[ (Left Bracket)", '['), ascii("\\ (Backslash)", '\\'),
            ascii("] (Right Bracket)", ']'), ascii("^ (Caret)", '^'), ascii("_ (Underscore)", '_'),
            ascii("` (Backtick)", '`'), ascii("{ (Left Brace)", '{'), ascii("| (Pipe)", '|'),
            ascii("} (Right Brace)", '}'), ascii("~ (Tilde)", '~')
    );

    private final KeySequenceRepository repository;
    private final UserMessages messages;

    public KeySequenceService(KeySequenceRepository repository, UserMessages messages) {
        this.repository = repository;
        this.messages = messages;
    }

    public List<KeySequence> list() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    @Transactional
    public KeySequence create(String label, boolean ctrl, boolean alt, boolean shift, boolean superKey, int mainKeysym) {
        int nextSortOrder = repository.findAllByOrderBySortOrderAsc().stream()
                .mapToInt(KeySequence::getSortOrder).max().orElse(-1) + 1;
        return repository.save(new KeySequence(label, ctrl, alt, shift, superKey, mainKeysym, nextSortOrder));
    }

    @Transactional
    public KeySequence update(Long id, String label, boolean ctrl, boolean alt, boolean shift, boolean superKey, int mainKeysym) {
        KeySequence sequence = getById(id);
        sequence.setLabel(label);
        sequence.setCtrl(ctrl);
        sequence.setAlt(alt);
        sequence.setShift(shift);
        sequence.setSuperKey(superKey);
        sequence.setMainKeysym(mainKeysym);
        return sequence;
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(getById(id));
    }

    /** Re-numbers every sequence's own sortOrder to match its position in {@code orderedIds} - the
     *  admin UI's drag-to-reorder posts the full new id order every time, simpler than a relative
     *  move-up/move-down API. */
    @Transactional
    public void reorder(List<Long> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            getById(orderedIds.get(i)).setSortOrder(i);
        }
    }

    private KeySequence getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(messages.get("error.keySequences.noSuchSequence", id)));
    }
}
