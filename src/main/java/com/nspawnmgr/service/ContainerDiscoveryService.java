package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.MinimalTemplateFlavor;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin-triggered "Discover machines" action: finds machinectl images on the host that aren't yet
 * tracked as a {@link Container} row and registers them as ordinary MANAGED containers, owned by
 * whoever ran the discovery. Deliberately does not touch SSH/RDP/Guacamole — see
 * {@link Container#discovered} and docs/administrator-guide.md's discovery section for why.
 *
 * <p>Also the one place that links the self-hosted "nspawnmgr" app machine and nspawnmgr's own
 * database machine (see {@code nspawnmgr-bootstrap-app-machine.sh}/{@code
 * nspawnmgr-bootstrap-db-machine.sh}) back to the {@code debian-minimal} template they're actually
 * cloned from — neither gets a {@link Container} row from anywhere else, so this is also the only
 * place that link could ever get made — and, in turn, the one place that provisions them real,
 * nspawnmgr-managed SSH access (see {@link #tryProvisionManagedSsh}), ensures both auto-start when
 * the host itself boots (see {@link #tryEnableAutoStart}), and sets "nspawnmgr" to require its own
 * database machine already started (see {@link #tryRequireDatabaseMachine}) - since neither machine
 * ever went through {@code ProvisioningService}'s normal create flow, this reconciliation pass is
 * the only place any of that could ever get set up.
 */
@Service
public class ContainerDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ContainerDiscoveryService.class);

    /** The self-hosted app machine's own opinionated, fixed name — see nspawnmgr-bootstrap-app-machine.sh. */
    private static final String APP_MACHINE_NAME = "nspawnmgr";
    /** Container list "Description" column contents for the two self-hosted machines — same fixed
     *  text {@code DatabaseSetupWizardServlet} uses in the root-wizard module for the same two
     *  machines when it registers them directly. */
    private static final String APP_MACHINE_DESCRIPTION = "Virtual machine management";
    private static final String DB_MACHINE_DESCRIPTION = "Database server";

    private final ContainerRepository containerRepository;
    private final ContainerCredentialRepository containerCredentialRepository;
    private final ContainerCliExecutor cliExecutor;
    private final ContainerAccessService accessService;
    private final TemplateService templateService;
    private final ProvisioningService provisioningService;
    private final SelfHostedBootstrapStatus bootstrapStatus;
    private final String datasourceUrl;

    public ContainerDiscoveryService(ContainerRepository containerRepository,
                                      ContainerCredentialRepository containerCredentialRepository,
                                      ContainerCliExecutor cliExecutor, ContainerAccessService accessService,
                                      TemplateService templateService, ProvisioningService provisioningService,
                                      SelfHostedBootstrapStatus bootstrapStatus,
                                      @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.containerRepository = containerRepository;
        this.containerCredentialRepository = containerCredentialRepository;
        this.cliExecutor = cliExecutor;
        this.accessService = accessService;
        this.templateService = templateService;
        this.provisioningService = provisioningService;
        this.bootstrapStatus = bootstrapStatus;
        this.datasourceUrl = datasourceUrl;
    }

    /**
     * Deliberately NOT one big {@code @Transactional} method: {@link #tryProvisionManagedSsh} runs
     * its own work in a {@code REQUIRES_NEW} sub-transaction (see that method's own javadoc for why),
     * which needs the container row it references to already be durably committed - confirmed live,
     * wrapping this whole loop in one shared transaction meant the newly-inserted {@code containers}
     * row wasn't visible to that separate connection/transaction yet, and its own
     * {@code container_credentials} insert failed outright on the foreign-key constraint. Each
     * container is registered (own short transaction, see {@link #registerContainer}), committed,
     * and only then handed to the SSH-provisioning/access-enabling steps (each already transactional
     * on its own) - no worse than before for atomicity (nothing here ever needed all-or-nothing
     * behavior across multiple containers) and strictly safer for this ordering requirement.
     */
    public List<Container> discover(User actingAdmin) {
        Set<String> known = containerRepository.findAll().stream()
                .map(Container::getName)
                .collect(Collectors.toSet());
        Template debianMinimal = templateService.registerExistingMinimal(MinimalTemplateFlavor.DEBIAN).orElse(null);
        String dbHost = resolveDbHost();

        List<Container> discovered = new ArrayList<>();
        for (String name : cliExecutor.listMachineImageNames()) {
            if (known.contains(name)) {
                continue;
            }
            MachineStatus status = cliExecutor.status(name);
            if (status == MachineStatus.NOT_FOUND) {
                // Listed a moment ago, gone now (e.g. someone removed it concurrently) - skip rather
                // than register a container for an image that no longer exists.
                continue;
            }
            Container container = registerContainer(name, status, actingAdmin, debianMinimal, dbHost);
            if (container.getTemplate() == null) {
                // Self-hosted infrastructure (template already linked by registerContainer above)
                // is handled uniformly by reconcileSelfHostedInfrastructure below instead - see its
                // own javadoc for why it must never go through this generic fallback.
                accessService.tryAutoEnable(container);
            }
            discovered.add(container);
        }
        reconcileSelfHostedInfrastructure(debianMinimal, dbHost);
        return discovered;
    }

    /**
     * Same self-hosted-infrastructure reconciliation {@link #discover} always ran anyway, exposed
     * as its own entry point and run on a recurring schedule - unlike {@link #discover}, this needs
     * no acting-admin {@link User}: the DB setup wizard now creates the first admin's {@code User}
     * row and both self-hosted {@code Container} rows directly (see {@code
     * DatabaseSetupWizardServlet} in the root-wizard module), so by the time nspawnmgr's own Spring
     * app is up there's already an owner - nothing here ever needs to register a brand new
     * container the way {@link #discover}'s own main loop does. Running this indefinitely, not just
     * once, is what actually closes the reliability gap a one-shot attempt had: confirmed live, the
     * very first attempt at this can fail on a transient SSH/host-readiness hiccup right at cold
     * boot, and previously nothing ever retried it unless an admin happened to notice and click
     * "Discover machines" by hand. Cheap once steady-state, same posture as {@link
     * ContainerDnsSyncService}'s own recurring sync.
     */
    @Scheduled(fixedDelay = 30000)
    public void reconcileSelfHostedInfrastructureNow() {
        Template debianMinimal = templateService.registerExistingMinimal(MinimalTemplateFlavor.DEBIAN).orElse(null);
        String dbHost = resolveDbHost();
        reconcileSelfHostedInfrastructure(debianMinimal, dbHost);
    }

    /**
     * Builds and commits one newly-discovered container's row - see {@link #discover}'s own javadoc
     * for why this can't share one big transaction with the rest of the loop. Deliberately no
     * {@code @Transactional} of its own here (would be a silent no-op anyway: calling one method on
     * {@code this} from another never goes through Spring's proxy, so a self-invoked
     * {@code @Transactional} never actually starts anything - same trap {@code ProvisioningService
     * .approve}'s own javadoc calls out for {@code @Async}). Not needed regardless:
     * {@code containerRepository.save} is itself transactional on the repository bean's own proxy
     * (a real, cross-bean call, not self-invocation) and commits immediately when nothing else is
     * already open on this thread - exactly the durable-before-return guarantee this needs.
     */
    private Container registerContainer(String name, MachineStatus status, User actingAdmin, Template debianMinimal, String dbHost) {
        Container container = Container.discovered(name, actingAdmin);
        String address = null;
        if (status == MachineStatus.RUNNING) {
            container.setState(ContainerState.RUNNING);
            address = cliExecutor.getInternalAddress(name);
            if (!address.isEmpty()) {
                container.setInternalAddress(address);
            }
        } else {
            container.setState(ContainerState.STOPPED);
        }
        if (isSelfHostedInfrastructure(name, address, dbHost)) {
            container.setDescription(selfHostedDescription(name));
            if (debianMinimal != null) {
                container.setTemplate(debianMinimal);
            }
        }
        return containerRepository.save(container);
    }

    /**
     * Ensures the self-hosted "nspawnmgr" app machine and database machine - whether freshly
     * registered by the loop just above, or already known from an earlier {@code discover()} call -
     * stay linked to {@code debian-minimal} and have real, nspawnmgr-managed SSH access. Runs over
     * every currently-known container on every single {@code discover()} call, not just the one
     * where each one happens to first get its template linked: confirmed live, gating the SSH
     * re-attempt on "template is still null" meant a managed-SSH attempt that failed just once (a
     * transient host hiccup, say) could never be retried again once the template link itself
     * succeeded, since that's a one-time, idempotent operation - {@code discover()}'s own main loop
     * only ever looks at machines not yet known at all, so nothing else would ever call back in for
     * these two specifically. {@link #tryProvisionManagedSsh}'s own credential check keeps repeat
     * calls here cheap once it actually succeeds.
     */
    private void reconcileSelfHostedInfrastructure(Template debianMinimal, String dbHost) {
        if (debianMinimal == null) {
            return;
        }
        // Stop appending to bootstrapStatus once it's already latched done - this pass still runs
        // (self-healing any later drift), it just has nothing new worth showing the wizard's own
        // one-time "is setup finished yet" poll, and would otherwise grow that in-memory log
        // forever since this method now runs on an indefinite @Scheduled cadence.
        boolean loggingEnabled = !bootstrapStatus.isDone();
        String dbMachineName = null;
        boolean foundAny = false;
        boolean allSettled = true;
        for (Container container : containerRepository.findAllWithTemplate()) {
            if (!isSelfHostedInfrastructure(container.getName(), container.getInternalAddress(), dbHost)) {
                continue;
            }
            foundAny = true;
            if (!APP_MACHINE_NAME.equals(container.getName())) {
                dbMachineName = container.getName();
            }
            if (container.getTemplate() == null) {
                linkTemplate(container, debianMinimal);
                if (loggingEnabled) {
                    bootstrapStatus.log("Linked '" + container.getName() + "' to the debian-minimal template.");
                }
            }
            if (container.getDescription() == null || container.getDescription().isBlank()) {
                backfillDescription(container);
            }
            allSettled &= tryProvisionManagedSsh(container, loggingEnabled);
            allSettled &= tryEnableAutoStart(container.getName(), loggingEnabled);
        }
        boolean requiresSettled = dbMachineName != null && tryRequireDatabaseMachine(dbMachineName, loggingEnabled);
        if (loggingEnabled && foundAny && allSettled && requiresSettled) {
            bootstrapStatus.log("Self-hosted machine setup complete.");
            bootstrapStatus.markDone();
        }
    }

    /** Commits the template link on its own - same reasoning as {@link #registerContainer}. */
    private void linkTemplate(Container container, Template debianMinimal) {
        container.setTemplate(debianMinimal);
        container.touch();
        containerRepository.save(container);
    }

    /** Backfills the container list "Description" column for an install predating this - e.g. an
     *  existing self-hosted install that already had these two containers registered before this
     *  feature existed. New installs get it set directly at registration time instead (here, or by
     *  {@code DatabaseSetupWizardServlet} in the root-wizard module), so this only ever fires once
     *  per machine in practice. */
    private void backfillDescription(Container container) {
        container.setDescription(selfHostedDescription(container.getName()));
        container.touch();
        containerRepository.save(container);
    }

    /** {@link #APP_MACHINE_DESCRIPTION} for the fixed-name app machine, {@link
     *  #DB_MACHINE_DESCRIPTION} for anything else this gets called for - only ever called once
     *  {@link #isSelfHostedInfrastructure} has already confirmed {@code name} is one of the two. */
    private String selfHostedDescription(String name) {
        return APP_MACHINE_NAME.equals(name) ? APP_MACHINE_DESCRIPTION : DB_MACHINE_DESCRIPTION;
    }

    /**
     * Provisions real, nspawnmgr-managed SSH access (own generated keypair + Guacamole connection)
     * for the self-hosted "nspawnmgr" app machine or database machine - both already have sshd
     * baked in and running (cloned from debian-minimal), but neither ever went through
     * ProvisioningService's normal create flow, so neither has an SSH_KEY credential of its own the
     * way an ordinary managed container would. Best-effort and idempotent, same posture as {@link
     * ContainerAccessService#tryAutoEnable}: skips a container that isn't RUNNING yet (nothing to do
     * until it boots - the next discover() call picks it up) or that already has a credential
     * (already provisioned, most calls after the first), and swallows any failure rather than
     * failing the whole discover() call over one machine's hiccup - logged at WARN, not DEBUG
     * (confirmed live: a DEBUG-level swallow here is invisible at this app's default logging level,
     * so a real, persistent failure here looked identical to "hasn't tried yet" from the outside).
     *
     * @return true once {@code container} has a managed SSH credential (already had one, or just
     * got one) - false if it's not RUNNING yet or provisioning failed and needs another pass.
     */
    private boolean tryProvisionManagedSsh(Container container, boolean loggingEnabled) {
        if (container.getState() != ContainerState.RUNNING) {
            return false;
        }
        if (containerCredentialRepository.findByContainerAndType(container, CredentialType.SSH_KEY).isPresent()) {
            return true;
        }
        try {
            provisioningService.provisionSshForExistingContainer(container, null);
            if (loggingEnabled) {
                bootstrapStatus.log("Provisioned managed SSH access for '" + container.getName() + "'.");
            }
            return true;
        } catch (Exception e) {
            log.warn("Managed SSH provisioning failed for self-hosted infrastructure machine '{}' - will retry on the next pass: {}",
                    container.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Ensures {@code machineName} auto-starts when the HOST itself boots - user-requested for both
     * the self-hosted "nspawnmgr" app machine and its database machine, since the app can't recover
     * from a host reboot on its own otherwise (nothing else would ever start either machine again).
     * Re-checks the host's own current setting first rather than blindly re-enabling on every call -
     * cheap, and avoids an unnecessary host round-trip once it's already correctly set. Best-effort,
     * same posture as {@link #tryProvisionManagedSsh}: swallows any failure (logged at WARN) rather
     * than failing the whole discover() call over one machine's hiccup.
     *
     * @return true once {@code machineName} auto-starts on host boot - false on failure.
     */
    private boolean tryEnableAutoStart(String machineName, boolean loggingEnabled) {
        try {
            if (cliExecutor.getBootSettings(machineName).autoStart()) {
                return true;
            }
            cliExecutor.setAutoStart(machineName, true);
            if (loggingEnabled) {
                bootstrapStatus.log("Enabled auto-start on host boot for '" + machineName + "'.");
            }
            return true;
        } catch (Exception e) {
            log.warn("Could not enable auto-start for self-hosted infrastructure machine '{}': {}", machineName, e.getMessage());
            return false;
        }
    }

    /**
     * User-requested: the self-hosted "nspawnmgr" app machine requires its own database machine
     * already started - nspawnmgr's own JVM needs a reachable database to do anything useful, so
     * starting before the database machine is up would just mean redeploying into a broken state on
     * every host reboot. Same re-check-before-writing and best-effort posture as
     * {@link #tryEnableAutoStart}.
     *
     * @return true once {@code APP_MACHINE_NAME} requires {@code dbMachineName} - false on failure.
     */
    private boolean tryRequireDatabaseMachine(String dbMachineName, boolean loggingEnabled) {
        try {
            if (dbMachineName.equals(cliExecutor.getBootSettings(APP_MACHINE_NAME).requiresMachineName())) {
                return true;
            }
            cliExecutor.setRequiresMachine(APP_MACHINE_NAME, dbMachineName);
            if (loggingEnabled) {
                bootstrapStatus.log("Set '" + APP_MACHINE_NAME + "' to require '" + dbMachineName + "' already started.");
            }
            return true;
        } catch (Exception e) {
            log.warn("Could not set '{}' to require database machine '{}': {}", APP_MACHINE_NAME, dbMachineName, e.getMessage());
            return false;
        }
    }

    /**
     * True for the self-hosted "nspawnmgr" app machine (always this fixed name) or nspawnmgr's own
     * database machine — identified by internal address rather than name, since its name is
     * admin-chosen at setup time (see DatabaseSetupWizardServlet's own default-name-per-engine
     * dropdown) and can't be predicted. {@code dbHost} is parsed from the JDBC URL nspawnmgr is
     * actually connected through right now, so this stays correct even if that admin picked a
     * non-default name. Doesn't account for the bundled postgresql-minimal image variant (see
     * nspawnmgr-bootstrap-db-machine.sh) — a database machine cloned straight from THAT tarball
     * isn't actually based on debian-minimal, but there's no signal available here to distinguish
     * the two cases from.
     */
    private boolean isSelfHostedInfrastructure(String name, String internalAddress, String dbHost) {
        if (APP_MACHINE_NAME.equals(name)) {
            return true;
        }
        return dbHost != null && dbHost.equals(internalAddress);
    }

    /** Best-effort: the configured JDBC URL's own host, or null if it has none (e.g. H2) or doesn't parse. */
    private String resolveDbHost() {
        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            return null;
        }
        String withoutJdbcPrefix = datasourceUrl.startsWith("jdbc:") ? datasourceUrl.substring("jdbc:".length()) : datasourceUrl;
        try {
            return URI.create(withoutJdbcPrefix).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
