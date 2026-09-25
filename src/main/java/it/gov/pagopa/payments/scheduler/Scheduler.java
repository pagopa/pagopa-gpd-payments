package it.gov.pagopa.payments.scheduler;

import it.gov.pagopa.payments.service.SchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;

import static it.gov.pagopa.payments.utils.SchedulerUtils.*;

@Component
@Slf4j
@EnableScheduling
@ConditionalOnProperty(name = "cron.job.schedule.retry.enabled", matchIfMissing = true)
public class Scheduler {

    private static final String LOG_BASE_HEADER_INFO = "[OperationType: %s] - [ClassMethod: %s] - [MethodParamsToLog: %s]";
    private static final String CRON_JOB = "CRON JOB";
    private static final String RETRY_PA_SEND_RT = "retryPaSendRT";
    private Thread threadOfExecution;

    @Autowired
    SchedulerService schedulerService;

    @Scheduled(cron = "${cron.job.schedule.expression.retry.trigger}")
    public void retryPaSendRT() {
        try {
            log.debug(String.format(LOG_BASE_HEADER_INFO, CRON_JOB, "retry sendRT", "Running at " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now(ZoneId.systemDefault()))));
            schedulerService.retryFailedPaSendRT();
            this.threadOfExecution = Thread.currentThread();
            logJobCompleted(RETRY_PA_SEND_RT);
        }
        catch (Exception e){
            logJobFailed(RETRY_PA_SEND_RT, e);
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

