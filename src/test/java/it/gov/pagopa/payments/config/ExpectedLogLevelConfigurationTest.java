package it.gov.pagopa.payments.config;

import static org.junit.jupiter.api.Assertions.*;

import it.gov.pagopa.payments.config.feign.GPDFeignConfig;
import org.slf4j.event.Level;
import it.gov.pagopa.payments.config.logging.FeignLoggingCapability;
import it.gov.pagopa.payments.config.logging.AzureLoggingPolicy;
import it.gov.pagopa.payments.config.logging.VerticalLoggingInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class ExpectedLogLevelConfigurationTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(
          RestTemplateConfig.class, GPDFeignConfig.class, AzureLoggingPolicy.Factory.class)
      .withPropertyValues("gpd.subscription-key=test-key");

  @ParameterizedTest
  @ValueSource(strings = {"INFO", "DEBUG"})
  void springWiresConfiguredLevelIntoAllHttpObservers(String level) {
    contextRunner.withPropertyValues("logging.http.expected-level=" + level).run(context -> {
      assertNull(context.getStartupFailure());
      var expected = Level.valueOf(level);
      assertEquals(expected, ReflectionTestUtils.getField(
          context.getBean(FeignLoggingCapability.class), "expectedLogLevel"));
      var observer = context.getBean(RestTemplate.class).getInterceptors().get(0);
      assertSame(context.getBean(VerticalLoggingInterceptor.class), observer);
      assertEquals(1, context.getBeansOfType(FeignLoggingCapability.class).size());
      assertEquals(expected, ReflectionTestUtils.getField(observer, "expectedLogLevel"));
      assertAzureLevel(context.getBean(AzureLoggingPolicy.Factory.class), expected);
    });
  }

  @Test
  void missingPropertyDefaultsToInfo() {
    contextRunner.run(context -> {
      assertNull(context.getStartupFailure());
      assertEquals(Level.INFO, ReflectionTestUtils.getField(
          context.getBean(FeignLoggingCapability.class), "expectedLogLevel"));
      assertEquals(Level.INFO, ReflectionTestUtils.getField(
          context.getBean(VerticalLoggingInterceptor.class), "expectedLogLevel"));
      assertAzureLevel(context.getBean(AzureLoggingPolicy.Factory.class), Level.INFO);
    });
  }

  @Test
  void environmentVariableIsMappedThroughApplicationProperties() {
    contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
        .addLast(assertDoesNotThrow(() ->
            new ResourcePropertySource("file:src/main/resources/application.properties"))))
        .withPropertyValues("LOGGING_LEVEL_EXPECTED_RESULT=DEBUG")
        .run(context -> {
          assertNull(context.getStartupFailure());
          assertEquals(Level.DEBUG, ReflectionTestUtils.getField(
              context.getBean(FeignLoggingCapability.class), "expectedLogLevel"));
          assertEquals(Level.DEBUG, ReflectionTestUtils.getField(
              context.getBean(RestTemplate.class).getInterceptors().get(0), "expectedLogLevel"));
          assertAzureLevel(context.getBean(AzureLoggingPolicy.Factory.class), Level.DEBUG);
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"invalid", "OFF"})
  void unsupportedExpectedLevelsFailStartup(String level) {
    contextRunner.withPropertyValues("logging.http.expected-level=" + level).run(context ->
        assertNotNull(context.getStartupFailure()));
  }

  private static void assertAzureLevel(AzureLoggingPolicy.Factory factory, Level expected) {
    for (String dependency : new String[] {
        AzureLoggingPolicy.TABLES, AzureLoggingPolicy.QUEUE, AzureLoggingPolicy.BLOB}) {
      AzureLoggingPolicy policy = factory.create(dependency);
      assertEquals(expected, ReflectionTestUtils.getField(policy, "expectedLogLevel"));
      assertEquals(dependency, ReflectionTestUtils.getField(policy, "dependency"));
    }
  }
}
