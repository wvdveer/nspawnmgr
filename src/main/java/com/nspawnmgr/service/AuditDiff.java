package com.nspawnmgr.service;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a human-readable "field: old -> new" summary of what changed between two instances of the
 * same record type, for audit-log {@code details}. Any component whose name contains "password"
 * (case-insensitive) is reported as changed without revealing either value — this is generic
 * specifically so it keeps working if a password-shaped field is renamed or a new one is added,
 * rather than relying on a hand-maintained exclusion list that could silently miss one.
 */
final class AuditDiff {

    private AuditDiff() {
    }

    /** @return null if nothing changed (excluding {@code ignoredFields}), otherwise a "; "-joined summary. */
    static String describeChanges(Object before, Object after, Set<String> ignoredFields) {
        if (!before.getClass().equals(after.getClass())) {
            throw new IllegalArgumentException("before/after must be the same type");
        }
        RecordComponent[] components = before.getClass().getRecordComponents();
        if (components == null) {
            throw new IllegalArgumentException(before.getClass() + " is not a record");
        }
        List<String> changes = new ArrayList<>();
        for (RecordComponent component : components) {
            String name = component.getName();
            if (ignoredFields.contains(name)) {
                continue;
            }
            Object oldValue;
            Object newValue;
            try {
                oldValue = component.getAccessor().invoke(before);
                newValue = component.getAccessor().invoke(after);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to read record component " + name, e);
            }
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            if (name.toLowerCase(Locale.ROOT).contains("password")) {
                changes.add(name + " changed");
            } else {
                changes.add(name + ": " + oldValue + " -> " + newValue);
            }
        }
        return changes.isEmpty() ? null : String.join("; ", changes);
    }
}
