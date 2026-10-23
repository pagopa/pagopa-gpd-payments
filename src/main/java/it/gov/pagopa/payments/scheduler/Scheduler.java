package it.gov.pagopa.payments.scheduler;

import it.gov.pagopa.payments.service.SchedulerService;
import it.gov.pagopa.payments.config.LogContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

import static it.gov.pagopa.payments.utils.SchedulerUtils.*;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "cron.job.schedule.retry.enabled", matchIfMissing = true)
public class Scheduler {
    private static final String RETRY_OPERATION = "retryPaSendRT";

    private Thread threadOfExecution;

    @Autowired
    SchedulerService schedulerService;

    @Scheduled(cron = "${cron.job.schedule.expression.retry.trigger}")
    public void retryPaSendRT() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        long started = System.nanoTime();
        try {
            MDC.clear();
            MDC.put(LogContext.CORRELATION_ID, UUID.randomUUID().toString());
            MDC.put(LogContext.METHOD, RETRY_OPERATION);
            schedulerService.retryFailedPaSendRT();
            this.threadOfExecution = Thread.currentThread();
            logCompletion(started, null);
        }
        catch (Exception e){
            logCompletion(started, e);
            throw e;
        }
        finally {
            LogContext.restore(previous);
        }

    }

    public Thread getThreadOfExecution() {
        return this.threadOfExecution;
    }
}
