package it.gov.pagopa.payments.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.xml.MarshallingHttpMessageConverter;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

  @Bean
  public RestTemplate restTemplate() {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.getMessageConverters().add(0, jaxbMarshallingHttpMessageConverter());
    restTemplate.getInterceptors().add((request, body, execution) -> {
      String requestId = MDC.get("requestId");
      if (requestId != null && !requestId.isEmpty()) {
        request.getHeaders().set("X-Request-Id", requestId);
      }
      return execution.execute(request, body);
    });
    return restTemplate;
  }

  private MarshallingHttpMessageConverter jaxbMarshallingHttpMessageConverter() {
    Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
    marshaller.setContextPath("it.gov.pagopa.payments.model.partner");
    marshaller.setSupportJaxbElementClass(true);
    marshaller.setCheckForXmlRootElement(false);
    return new MarshallingHttpMessageConverter(marshaller, marshaller);
  }
}
