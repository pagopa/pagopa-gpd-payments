package it.gov.pagopa.payments.scheduler;

import it.gov.pagopa.payments.service.PartnerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Slf4j
@Validated
public class SchedulerTest {

    @Autowired
    PartnerService partnerService;

    @GetMapping(
            value = "/scheduler",
            produces = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity getFailureReceipts() {
        partnerService.getAllFailuresQueue();
        return new ResponseEntity<>(HttpStatus.OK);
    }
}
