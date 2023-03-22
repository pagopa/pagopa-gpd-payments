package it.gov.pagopa.payments.config;


import org.modelmapper.Converter;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.mapper.ConvertMarcaDaBolloToCtRichiestaMarcaDaBollo;
import it.gov.pagopa.payments.mapper.ConvertReceiptEntityToReceiptModelResponse;
import it.gov.pagopa.payments.model.MarcaDaBollo;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import it.gov.pagopa.payments.model.partner.CtRichiestaMarcaDaBollo;
import it.gov.pagopa.payments.utils.CustomizedMapper;

@Configuration
public class MappingsConfiguration {

  
  @Bean
  ModelMapper modelMapper() {
    ModelMapper mapper = new ModelMapper();
    mapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);

    Converter<ReceiptEntity, ReceiptModelResponse> convertReceiptEntityToReceiptModelResponse = new ConvertReceiptEntityToReceiptModelResponse();

    mapper.createTypeMap(ReceiptEntity.class, ReceiptModelResponse.class).setConverter(convertReceiptEntityToReceiptModelResponse);

    return mapper;
  }

  @Bean
  CustomizedMapper customizedModelMapper() {
    CustomizedMapper mapper = new CustomizedMapper();
    mapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
    
    Converter<MarcaDaBollo, CtRichiestaMarcaDaBollo> convertMarcaDaBolloToCtRichiestaMarcaDaBollo = new ConvertMarcaDaBolloToCtRichiestaMarcaDaBollo();

    mapper.createTypeMap(MarcaDaBollo.class, CtRichiestaMarcaDaBollo.class).setConverter(convertMarcaDaBolloToCtRichiestaMarcaDaBollo);
    
    return mapper;
  }

}
