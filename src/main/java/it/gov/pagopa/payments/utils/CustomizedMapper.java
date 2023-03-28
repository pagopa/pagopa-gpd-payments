package it.gov.pagopa.payments.utils;

import org.modelmapper.ModelMapper;

public class CustomizedMapper extends ModelMapper {

  @Override
  public <D> D map(Object source, Class<D> destinationType) {
    if (source == null) {
      return null;
    }
    return super.map(source, destinationType);
  }
}
