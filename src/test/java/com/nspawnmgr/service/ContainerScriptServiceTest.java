package com.nspawnmgr.service;

import com.nspawnmgr.cli.AbortableScriptRun;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.OutputLine;
import com.nspawnmgr.cli.OutputSource;
import com.nspawnmgr.cli.ScriptRunResult;
import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerScriptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerScriptServiceTest {

    private ContainerScriptRepository scriptRepository;
    private ContainerCliExecutor cliExecutor;
    private TaskExecutor taskExecutor;
    private AuditLogService auditLogService;
    private ContainerScriptService service;

    @BeforeEach
    void setUp() {
        scriptRepository = mock(ContainerScriptRepository.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        taskExecutor = mock(TaskExecutor.class);
        auditLogService = mock(AuditLogService.class);
        service = new ContainerScriptService(scriptRepository, cliExecutor, taskExecutor, auditLogService);
    }

    private Container container(Long id) {
        Container container = new Container();
        container.setId(id);
        container.setName("my-container");
        return container;
    }

    private User user() {
        User user = new User("ext-id");
        user.setId(1L);
        return user;
    }

    /** Runs the submitted background task synchronously, as soon as it's submitted. */
    private void executeSynchronously() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(taskExecutor).execute(any());
    }

    @Test
    void startRunTransitionsToCompletedOnNormalFinish() {
        executeSynchronously();
        AbortableScriptRun handle = mock(AbortableScriptRun.class);
        when(handle.await()).thenReturn(new ScriptRunResult(0, List.of()));
        when(cliExecutor.startScript(eq("my-container"), any(), eq("echo hi"), any(Duration.class))).thenReturn(handle);
        Container container = container(9L);
        User user = user();

        String runId = service.startRun(container, "echo hi", user);

        ContainerScriptService.ActiveRun status = service.getStatus(container, runId);
        assertThat(status.state()).isEqualTo(ScriptRunState.COMPLETED);
        assertThat(status.result().exitCode()).isEqualTo(0);
        verify(auditLogService).log(eq(user), eq(AuditAction.RAN), any(), eq(9L), eq("my-container"), anyString());
    }

    @Test
    void abortTransitionsToAborted() {
        var runnableCaptor = forClass(Runnable.class);
        doAnswer(invocation -> null).when(taskExecutor).execute(runnableCaptor.capture());
        AbortableScriptRun handle = mock(AbortableScriptRun.class);
        when(handle.await()).thenReturn(new ScriptRunResult(-1, List.of(
                new OutputLine(Instant.now(), OutputSource.STDERR, "aborted"))));
        when(cliExecutor.startScript(eq("my-container"), any(), eq("sleep 60"), any(Duration.class))).thenReturn(handle);
        Container container = container(9L);
        User user = user();

        String runId = service.startRun(container, "sleep 60", user);
        assertThat(service.getStatus(container, runId).state()).isEqualTo(ScriptRunState.RUNNING);

        service.abort(container, runId);
        verify(handle).abort();

        // Simulate the background task noticing the abort once handle.await() unblocks.
        runnableCaptor.getValue().run();

        ContainerScriptService.ActiveRun status = service.getStatus(container, runId);
        assertThat(status.state()).isEqualTo(ScriptRunState.ABORTED);
        verify(auditLogService).log(eq(user), eq(AuditAction.ABORTED), any(), eq(9L), eq("my-container"), anyString());
    }

    @Test
    void rejectedExecutionAbortsHandleAndThrows() {
        doThrow(new RejectedExecutionException("pool exhausted")).when(taskExecutor).execute(any());
        AbortableScriptRun handle = mock(AbortableScriptRun.class);
        when(cliExecutor.startScript(eq("my-container"), any(), eq("echo hi"), any(Duration.class))).thenReturn(handle);
        Container container = container(9L);

        assertThatThrownBy(() -> service.startRun(container, "echo hi", user()))
                .isInstanceOf(ContainerCliException.class);

        verify(handle, times(1)).abort();
    }

    @Test
    void getStatusThrowsForUnknownRunId() {
        assertThatThrownBy(() -> service.getStatus(container(9L), "nonexistent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getStatusThrowsForRunBelongingToDifferentContainer() {
        executeSynchronously();
        AbortableScriptRun handle = mock(AbortableScriptRun.class);
        when(handle.await()).thenReturn(new ScriptRunResult(0, List.of()));
        when(cliExecutor.startScript(eq("my-container"), any(), eq("echo hi"), any(Duration.class))).thenReturn(handle);
        Container owningContainer = container(9L);
        String runId = service.startRun(owningContainer, "echo hi", user());

        Container otherContainer = container(10L);
        assertThatThrownBy(() -> service.getStatus(otherContainer, runId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.abort(otherContainer, runId))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
