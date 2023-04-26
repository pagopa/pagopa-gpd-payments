package it.gov.pagopa.payments.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gov.pagopa.payments.model.Stamp;
import it.gov.pagopa.payments.model.partner.CtRichiestaMarcaDaBollo;
import java.util.Collections;
import javax.validation.Valid;
import org.modelmapper.Converter;
import org.modelmapper.MappingException;
import org.modelmapper.spi.ErrorMessage;
import org.modelmapper.spi.MappingContext;

public class ConvertMarcaDaBolloToCtRichiestaMarcaDaBollo
    implements Converter<Stamp, CtRichiestaMarcaDaBollo> {

  @Override
  public CtRichiestaMarcaDaBollo convert(
      MappingContext<Stamp, CtRichiestaMarcaDaBollo> context) throws MappingException {
    ObjectMapper mapper = new ObjectMapper();
    @Valid Stamp source = context.getSource();
    CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = new CtRichiestaMarcaDaBollo();
    try {
      ctRichiestaMarcaDaBollo.setTipoBollo(source.getStampType());
      ctRichiestaMarcaDaBollo.setProvinciaResidenza(source.getStampType());
      ctRichiestaMarcaDaBollo.setHashDocumento(mapper.writeValueAsBytes(source.getStampType()));
    } catch (JsonProcessingException e) {
      throw new MappingException(Collections.singletonList(new ErrorMessage(e.getMessage())));
    }
    return ctRichiestaMarcaDaBollo;
  }
}
