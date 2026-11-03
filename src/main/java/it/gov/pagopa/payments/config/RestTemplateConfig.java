package it.gov.pagopa.payments.config;

import it.gov.pagopa.payments.config.logging.VerticalLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.xml.MarshallingHttpMessageConverter;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.web.client.RestTemplate;

@Configuration
@Import(VerticalLoggingInterceptor.class)
public class RestTemplateConfig {

  @Bean
  public RestTemplate restTemplate(
      VerticalLoggingInterceptor loggingInterceptor) {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.getMessageConverters().add(0, jaxbMarshallingHttpMessageConverter());
    restTemplate.getInterceptors().add(loggingInterceptor);
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
