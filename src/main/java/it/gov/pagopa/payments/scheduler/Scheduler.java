package it.gov.pagopa.payments.scheduler;

import it.gov.pagopa.payments.service.SchedulerService;
import it.gov.pagopa.payments.utils.SchedulerUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static it.gov.pagopa.payments.utils.SchedulerUtils.*;

@Component
@Slf4j
@EnableScheduling
@ConditionalOnProperty(name = "cron.job.schedule.retry.enabled", matchIfMissing = true)
public class Scheduler {

    private Thread threadOfExecution;

    @Autowired
    SchedulerService schedulerService;

    @Scheduled(cron = "${cron.job.schedule.expression.retry.trigger}")
    @Async
    public void retryPaSendRT() {
        try {
            updateMDCForStartExecution("retryPaSendRT");
            schedulerService.retryFailedPaSendRT();
            this.threadOfExecution = Thread.currentThread();
            updateMDCForEndExecution();
        }
        catch (Exception e){
            updateMDCError(e);
            throw e;
        }
        finally {
            MDC.clear();
        }

    }

    public Thread getThreadOfExecution() {
        return this.threadOfExecution;
    }
}

