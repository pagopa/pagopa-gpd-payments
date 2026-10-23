package it.gov.pagopa.payments.scheduler;

import it.gov.pagopa.payments.service.SchedulerService;
import it.gov.pagopa.payments.logging.BackgroundExecutionLogging;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@EnableScheduling
@ConditionalOnProperty(name = "cron.job.schedule.retry.enabled", matchIfMissing = true)
public class Scheduler {

    private Thread threadOfExecution;

    @Autowired
    SchedulerService schedulerService;

    @Scheduled(cron = "${cron.job.schedule.expression.retry.trigger}")
    public void retryPaSendRT() {
        BackgroundExecutionLogging.runWithLogging("retryPaSendRT", execution -> {
            schedulerService.retryFailedPaSendRT();
            this.threadOfExecution = Thread.currentThread();
        });
    }

    public Thread getThreadOfExecution() {
        return this.threadOfExecution;
    }
}
