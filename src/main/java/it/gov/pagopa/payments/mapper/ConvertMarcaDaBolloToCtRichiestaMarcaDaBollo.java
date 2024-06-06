package it.gov.pagopa.payments.mapper;

import it.gov.pagopa.payments.model.Stamp;
import it.gov.pagopa.payments.model.partner.CtRichiestaMarcaDaBollo;
import javax.validation.Valid;
import org.modelmapper.Converter;
import org.modelmapper.MappingException;
import org.modelmapper.spi.MappingContext;

public class ConvertMarcaDaBolloToCtRichiestaMarcaDaBollo
    implements Converter<Stamp, CtRichiestaMarcaDaBollo> {

  @Override
  public CtRichiestaMarcaDaBollo convert(MappingContext<Stamp, CtRichiestaMarcaDaBollo> context)
      throws MappingException {
    @Valid Stamp source = context.getSource();
    CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = new CtRichiestaMarcaDaBollo();
    ctRichiestaMarcaDaBollo.setTipoBollo(source.getStampType());
    ctRichiestaMarcaDaBollo.setProvinciaResidenza(source.getProvincialResidence());
    ctRichiestaMarcaDaBollo.setHashDocumento(source.getHashDocument().getBytes());
    return ctRichiestaMarcaDaBollo;
  }
}
