package it.gov.pagopa.payments.scheduler;

import static org.mockito.Mockito.verify;

import it.gov.pagopa.payments.service.SchedulerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SchedulerTest {

    @Mock
    private SchedulerService schedulerService;

    @InjectMocks
    private Scheduler scheduler;

    @Test
    void retryPaSendRTShouldDelegateToSchedulerService() {

        scheduler.retryPaSendRT();

        verify(schedulerService).retryFailedPaSendRT();
    }
}