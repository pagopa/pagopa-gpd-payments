package it.gov.pagopa.payments.service.mapper;

import it.gov.pagopa.payments.model.*;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.utils.CustomizedMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public class TransferMapperSOAP {

    public static final String IBAN_APPOGGIO_KEY = "IBANAPPOGGIO";

    /**
     * @param transfer     GPD response
     * @param transferType XML request
     * @return maps input into {@link CtTransferPA} model
     */
    public static CtTransferPA getTransferResponse(PaymentsTransferModelResponse transfer, StTransferType transferType) {
        CtTransferPA transferPA = new CtTransferPA();
        transferPA.setFiscalCodePA(transfer.getOrganizationFiscalCode());
        transferPA.setIBAN(getIbanByTransferType(transferType, transfer));
        transferPA.setIdTransfer(Integer.parseInt(transfer.getIdTransfer()));
        transferPA.setRemittanceInformation(transfer.getRemittanceInformation());
        transferPA.setTransferAmount(BigDecimal.valueOf(transfer.getAmount()));
        transferPA.setTransferCategory(transfer.getCategory());

        return transferPA;
    }

    /**
     * @param transfer     GPD response
     * @param transferType V2 XML request
     * @return maps input into {@link CtTransferPA} model
     */
    public static CtTransferPAV2 getTransferResponseV2(CustomizedMapper customizedModelMapper, PaymentsTransferModelResponse transfer, StTransferType transferType) {
        Stamp stamp = transfer.getStamp();
        CtRichiestaMarcaDaBollo richiestaMarcaDaBollo = null;

        if (stamp != null && stamp.getHashDocument() != null && stamp.getStampType() != null && stamp.getProvincialResidence() != null)
            richiestaMarcaDaBollo = customizedModelMapper.map(stamp, CtRichiestaMarcaDaBollo.class);

        CtTransferPAV2 transferPA = new CtTransferPAV2();
        transferPA.setFiscalCodePA(transfer.getOrganizationFiscalCode());
        transferPA.setCompanyName(" "); // stText140(1, 140) todo: to be filled in with the correct company name for the PO
        // Nth transfer
        transferPA.setRichiestaMarcaDaBollo(richiestaMarcaDaBollo);
        transferPA.setIdTransfer(Integer.parseInt(transfer.getIdTransfer()));
        transferPA.setRemittanceInformation(transfer.getRemittanceInformation());
        transferPA.setTransferAmount(BigDecimal.valueOf(transfer.getAmount()));
        transferPA.setTransferCategory(transfer.getCategory());

        List<TransferMetadataModel> transferMetadataModels = transfer.getTransferMetadata();
        if (transferMetadataModels != null && !transferMetadataModels.isEmpty()) {
            CtMetadata ctMetadata = new CtMetadata();
            List<CtMapEntry> transferMapEntry = ctMetadata.getMapEntry();
            for (TransferMetadataModel transferMetadataModel : transferMetadataModels) {
                transferMapEntry.add(getTransferMetadata(transferMetadataModel));
            }
            transferPA.setMetadata(ctMetadata);
        }

        // PagoPA-1624: only two cases PAGOPA or POSTAL
        if (transferType != null && transferType.value().equals(StTransferType.PAGOPA.value())) {
            Optional.ofNullable(transfer.getPostalIban()).ifPresent(value -> createIbanAppoggioMetadata(transferPA, value));
            transferPA.setIBAN(transfer.getIban());
        } else {
            transferPA.setIBAN(getIbanByTransferType(transferType, transfer));
        }

        return transferPA;
    }

    /**
     * The method return iban given transferType and transfer, according to
     * https://pagopa.atlassian.net/wiki/spaces/PAG/pages/96403906/paGetPayment#trasferType
     */
    public static String getIbanByTransferType(StTransferType transferType, PaymentsTransferModelResponse transfer) {

        String defaultIban = Optional.ofNullable(transfer.getIban()).orElseGet(() -> Optional.ofNullable(transfer.getPostalIban()).orElseGet(() -> null));

        return transferType != null && transferType.value()
                .equals(StTransferType.POSTAL.value()) && transfer.getPostalIban() != null ? transfer.getPostalIban() : defaultIban;
    }

    public static CtMapEntry getTransferMetadata(TransferMetadataModel metadataModel) {
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey(metadataModel.getKey());
        ctMapEntry.setValue(metadataModel.getValue());
        return ctMapEntry;
    }

    private static void createIbanAppoggioMetadata(CtTransferPAV2 transferPA, String value) {
        CtMapEntry mapEntry = new CtMapEntry();
        mapEntry.setKey(IBAN_APPOGGIO_KEY);
        mapEntry.setValue(value);
        CtMetadata ctMetadata = Optional.ofNullable(transferPA.getMetadata()).orElse(new CtMetadata());
        ctMetadata.getMapEntry().add(mapEntry);
        transferPA.setMetadata(ctMetadata);
    }
}
