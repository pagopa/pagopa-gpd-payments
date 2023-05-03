package it.gov.pagopa.payments.mock;

import it.gov.pagopa.payments.model.partner.CtPaymentPAV2;
import it.gov.pagopa.payments.model.partner.PaGetPaymentV2Response;

public class PaGetPaymentV2ResMock {

    public static PaGetPaymentV2Response getMock() {

        PaGetPaymentV2Response mock = new PaGetPaymentV2Response();
        CtPaymentPAV2 data = new CtPaymentPAV2();
        data.setCompanyName("company name");
        data.setCreditorReferenceId("id");
        mock.setData(data);

        return mock;
    }
}
