package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerPortMapping;
import com.nspawnmgr.domain.PortMappingProtocol;
import com.nspawnmgr.repository.ContainerPortMappingRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Owner-managed custom inbound TCP/UDP port forwards — the only host-level port forwarding
 * nspawnmgr does; SSH/RDP go directly to a container's own internal veth address instead (see
 * ProvisioningService). Every mutation rewrites the container's .nspawn file immediately, but a
 * change only takes effect the next time the container is (re)started.
 */
@Service
public class ContainerPortMappingService {

    private final ContainerRepository containerRepository;
    private final ContainerPortMappingRepository portMappingRepository;
    private final ContainerFilesystemProvisioner filesystemProvisioner;

    public ContainerPortMappingService(ContainerRepository containerRepository,
                                        ContainerPortMappingRepository portMappingRepository,
                                        ContainerFilesystemProvisioner filesystemProvisioner) {
        this.containerRepository = containerRepository;
        this.portMappingRepository = portMappingRepository;
        this.filesystemProvisioner = filesystemProvisioner;
    }

    @Transactional
    public ContainerPortMapping addMapping(Container container, int hostPort, int containerPort, PortMappingProtocol protocol) {
        requireManaged(container);
        if (isHostPortInUse(hostPort, protocol)) {
            throw new IllegalStateException("Host port " + hostPort + " is already in use");
        }
        ContainerPortMapping mapping =
                portMappingRepository.save(new ContainerPortMapping(container, hostPort, containerPort, protocol));
        rewriteSettings(container);
        return mapping;
    }

    @Transactional
    public void removeMapping(Container container, Long mappingId) {
        requireManaged(container);
        ContainerPortMapping mapping = portMappingRepository.findById(mappingId)
                .filter(m -> m.getContainer().getId().equals(container.getId()))
                .orElseThrow(() -> new IllegalArgumentException("No such port mapping: " + mappingId));
        portMappingRepository.delete(mapping);
        rewriteSettings(container);
    }

    public List<ContainerPortMapping> listMappings(Container container) {
        return portMappingRepository.findByContainer(container);
    }

    private void rewriteSettings(Container container) {
        // container.getTemplate() may be a lazy proxy tied to whatever session originally loaded it
        // (open-in-view is off) - NspawnSettingsRenderer reads it now (PrivateUsers mode), so a plain
        // lazy proxy from outside this method's own transaction throws LazyInitializationException.
        // Same fix ProvisioningService.provision/TemplateService.createFromMachine already needed for
        // the identical reason.
        Container withTemplate = containerRepository.findByIdWithTemplate(container.getId())
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + container.getId()));
        filesystemProvisioner.writeNspawnSettings(withTemplate, portMappingRepository.findByContainer(container));
    }

    private boolean isHostPortInUse(int hostPort, PortMappingProtocol protocol) {
        return portMappingRepository.findUsedHostPorts(protocol).contains(hostPort);
    }

    private void requireManaged(Container container) {
        if (container.getKind() != ContainerKind.MANAGED) {
            throw new IllegalStateException(
                    "'" + container.getName() + "' is an admin-configured external host; port mappings aren't supported");
        }
    }
}
