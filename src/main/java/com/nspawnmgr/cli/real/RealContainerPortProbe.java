package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.ContainerPortProbe;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Real TCP connect from the host JVM directly to a container's internal veth address - works
 * because the host already routes directly to container veths (same reason guacd/readiness checks
 * reach containers this way). See {@link com.nspawnmgr.cli.ContainerPortProbe} for why this is a
 * bare connect rather than an authenticated check.
 */
@Component
@Profile("!dev")
public class RealContainerPortProbe implements ContainerPortProbe {

    private static final int TIMEOUT_MILLIS = 3000;

    @Override
    public boolean isOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
