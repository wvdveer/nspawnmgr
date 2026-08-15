package com.nspawnmgr.service;

import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Writes a structured audit line to nspawnmgr's own log — no separate database table or admin page;
 * these lines flow into catalina.out exactly like every other log statement in this app, and are
 * meant to be read via the Tomcat log viewer, not a bespoke UI. Logged under a dedicated "AUDIT"
 * logger name (rather than this class's own) so an admin can grep/filter for exactly these lines.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    public void log(User actor, AuditAction action, AuditTargetType targetType, Long targetId, String targetName, String details) {
        log.info("actor={} action={} targetType={} targetId={} targetName={} details={}",
                actor.getUsername(), action, targetType, targetId, targetName, details);
    }

    /**
     * Logs an UPDATED/SETTINGS line describing which fields of {@code before}/{@code after}
     * (records of the same type) actually changed — any field whose name contains "password" is
     * reported as changed without its value, everything else gets "field: old -&gt; new". No-op if
     * nothing changed (e.g. the admin submitted the form without editing anything).
     */
    public void logSettingsChange(User actor, Object before, Object after, Set<String> ignoredFields) {
        String details = AuditDiff.describeChanges(before, after, ignoredFields);
        if (details == null) {
            return;
        }
        log(actor, AuditAction.UPDATED, AuditTargetType.SETTINGS, null, null, details);
    }
}
