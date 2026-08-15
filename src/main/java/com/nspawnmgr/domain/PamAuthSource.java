package com.nspawnmgr.domain;

/**
 * What a container's {@code pam_nspawnmgr} replacement login check verifies a submitted
 * password against, bypassing the container's own local {@code /etc/shadow} entirely for
 * whichever PAM services are configured (see {@link ContainerPamService}). {@code
 * RDP_PASSWORD}/{@code VNC_PASSWORD} check the matching stored, encrypted {@link
 * ContainerCredential} on this same container; {@code NSPAWNMGR_AUTH_BACKEND} delegates to
 * whatever backend nspawnmgr's own web login uses (SMB/AD today) — any user who authenticates
 * successfully against it may log in, not just this container's owner or shared users.
 */
public enum PamAuthSource {
    RDP_PASSWORD, VNC_PASSWORD, NSPAWNMGR_AUTH_BACKEND
}
