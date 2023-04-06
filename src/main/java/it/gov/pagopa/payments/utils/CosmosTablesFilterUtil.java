package it.gov.pagopa.payments.utils;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.UtilityClass;

@UtilityClass
@Getter
@Setter
public class CosmosTablesFilterUtil {

    private String partitionKey;

    private String rowKeyDateStart;
}
