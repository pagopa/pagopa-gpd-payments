package it.gov.pagopa.payments.scheduler;

import it.gov.pagopa.payments.service.SchedulerService;
import it.gov.pagopa.payments.logging.ContextSnapshot;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.OperationLogScope;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@EnableScheduling
@ConditionalOnProperty(name = "cron.job.schedule.retry.enabled", matchIfMissing = true)
public class Scheduler {

    private static final LogEventWriter LOG_EVENTS = new LogEventWriter(log);
    private Thread threadOfExecution;

    @Autowired
    SchedulerService schedulerService;

    @Scheduled(cron = "${cron.job.schedule.expression.retry.trigger}")
    public void retryPaSendRT() {
        try (OperationLogScope ignored = new ContextSnapshot(
                null, null, UUID.randomUUID().toString(), null, null, null)
                .openOperation("retryPaSendRT")) {
            SchedulerService.JobCounts counts = new SchedulerService.JobCounts();
            long started = System.nanoTime();
            boolean aborted = true;
            try {
                schedulerService.retryFailedPaSendRT(counts);
                this.threadOfExecution = Thread.currentThread();
                aborted = false;
            } finally {
                boolean failed = aborted || counts.summary().failedCount() > 0;
                MDC.put("status", failed ? "KO" : "OK");
                MDC.put("httpCode", failed ? "500" : "201");
                MDC.put("responseTime",
                        Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
                LOG_EVENTS.jobCompleted(counts.summary(), failed);
            }
        }

    }

    public Thread getThreadOfExecution() {
        return this.threadOfExecution;
    }
}
