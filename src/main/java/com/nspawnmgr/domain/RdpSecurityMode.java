package com.nspawnmgr.domain;

import java.util.Locale;

/** Guacamole's RDP {@code security} parameter values - ANY matches pre-existing default behavior. */
public enum RdpSecurityMode {
    ANY, RDP, TLS, NLA, VMCONNECT;

    public String guacamoleValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
