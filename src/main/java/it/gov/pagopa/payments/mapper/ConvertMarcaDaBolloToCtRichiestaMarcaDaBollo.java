package it.gov.pagopa.payments.mapper;

import java.util.Collections;

import javax.validation.Valid;

import org.modelmapper.Converter;
import org.modelmapper.MappingException;
import org.modelmapper.spi.ErrorMessage;
import org.modelmapper.spi.MappingContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.gov.pagopa.payments.model.MarcaDaBollo;
import it.gov.pagopa.payments.model.partner.CtRichiestaMarcaDaBollo;

public class ConvertMarcaDaBolloToCtRichiestaMarcaDaBollo implements Converter<MarcaDaBollo, CtRichiestaMarcaDaBollo> {

  @Override
  public CtRichiestaMarcaDaBollo convert(MappingContext<MarcaDaBollo, CtRichiestaMarcaDaBollo> context) throws MappingException {
    ObjectMapper mapper = new ObjectMapper();
    @Valid MarcaDaBollo mb = context.getSource();
    CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = new CtRichiestaMarcaDaBollo();
    try {
      ctRichiestaMarcaDaBollo.setTipoBollo(mb.getTipoBollo());
      ctRichiestaMarcaDaBollo.setProvinciaResidenza(mb.getTipoBollo());
      ctRichiestaMarcaDaBollo.setHashDocumento(mapper.writeValueAsBytes(mb.getTipoBollo()));
    } 
    catch (JsonProcessingException e) {
      throw new MappingException(Collections.singletonList(new ErrorMessage(e.getMessage())));
    }
    return ctRichiestaMarcaDaBollo;
  }

}
