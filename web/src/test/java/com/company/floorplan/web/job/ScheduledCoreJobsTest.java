package com.company.floorplan.web.job;

import com.company.floorplan.application.service.BatchApplicationService;
import com.company.floorplan.domain.service.DistributedLock;
import com.company.floorplan.domain.service.LockHandle;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 调度器核心逻辑：抢到锁才执行、抢不到直接跳过、异常不阻塞后续调度。
 */
class ScheduledCoreJobsTest {

    @Test
    void runsJobWhenLockAcquired() {
        BatchApplicationService batch = mock(BatchApplicationService.class);
        DistributedLock lock = mock(DistributedLock.class);
        LockHandle handle = mock(LockHandle.class);
        when(lock.tryLock(anyString(), any())).thenReturn(Optional.of(handle));
        when(batch.runDailyInterest(any())).thenReturn(Map.of("status", "SUCCESS"));

        new ScheduledCoreJobs(batch, lock).dailyInterest();

        verify(batch).runDailyInterest(any());
        verify(handle).close();
    }

    @Test
    void skipsJobWhenLockHeldByAnotherInstance() {
        BatchApplicationService batch = mock(BatchApplicationService.class);
        DistributedLock lock = mock(DistributedLock.class);
        when(lock.tryLock(anyString(), any())).thenReturn(Optional.empty());

        new ScheduledCoreJobs(batch, lock).dailyInterest();

        verifyNoInteractions(batch);
    }

    @Test
    void skipsJobWhenLockProviderUnavailable_failClosed() {
        BatchApplicationService batch = mock(BatchApplicationService.class);
        DistributedLock lock = mock(DistributedLock.class);
        when(lock.tryLock(anyString(), any())).thenThrow(new IllegalStateException("redis down"));

        new ScheduledCoreJobs(batch, lock).dailyInterest();

        verifyNoInteractions(batch);
    }
}
