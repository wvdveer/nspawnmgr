package com.nspawnmgr.guacamole;

import java.util.List;

/** A labeled subsection of related GuacamolePropertyFields (matches the grouping used in the
 *  Apache Guacamole manual itself — Connection, SSL/TLS, Password Policy, etc.). */
public record GuacamolePropertyGroup(String title, List<GuacamolePropertyField> fields) {
}
