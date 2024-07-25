package it.gov.pagopa.payments.mapper;

import it.gov.pagopa.payments.model.Stamp;
import it.gov.pagopa.payments.model.partner.CtRichiestaMarcaDaBollo;
import javax.validation.Valid;
import org.modelmapper.Converter;
import org.modelmapper.MappingException;
import org.modelmapper.spi.MappingContext;

import java.util.Base64;

public class ConvertMarcaDaBolloToCtRichiestaMarcaDaBollo
    implements Converter<Stamp, CtRichiestaMarcaDaBollo> {

  @Override
  public CtRichiestaMarcaDaBollo convert(MappingContext<Stamp, CtRichiestaMarcaDaBollo> context)
      throws MappingException {
    @Valid Stamp source = context.getSource();
    CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = new CtRichiestaMarcaDaBollo();
    ctRichiestaMarcaDaBollo.setTipoBollo(source.getStampType());
    ctRichiestaMarcaDaBollo.setProvinciaResidenza(source.getProvincialResidence());
    // the given source.getHashDocument() is already taken as base64 from GPD-Core,
    // so in this point decode the base64 byte array and encode it next (at the time of response marshalling)
    ctRichiestaMarcaDaBollo.setHashDocumento(Base64.getDecoder().decode(source.getHashDocument().getBytes()));

    return ctRichiestaMarcaDaBollo;
  }
}
