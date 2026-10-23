package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.gov.pagopa.payments.controller.BaseController;
import it.gov.pagopa.payments.model.AppInfo;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LoggingAspectMetadataTest {

  @Test
  void startupAndInfoUseTheSameGlobalServiceIdentity() {
    Logger logger = (Logger) LoggerFactory.getLogger(LoggingAspect.class);
    Level previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      new ApplicationContextRunner()
          .withPropertyValues(
              "info.application.version=test-version",
              "info.application.name=pagopa-gpd-payments",
              "info.properties.environment=dev")
          .withBean(HttpServletRequest.class, () -> mock(HttpServletRequest.class))
          .withUserConfiguration(LoggingAspect.class, BaseController.class)
          .run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, appender.list.size());
            assertEquals(
                "-> Starting pagopa-gpd-payments version test-version - environment dev",
                appender.list.get(0).getFormattedMessage());
            AppInfo info = context.getBean(BaseController.class).healthCheck().getBody();
            assertEquals("pagopa-gpd-payments", info.getName());
            assertEquals("test-version", info.getVersion());
            assertEquals("dev", info.getEnvironment());
          });
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      appender.stop();
    }
  }
}
