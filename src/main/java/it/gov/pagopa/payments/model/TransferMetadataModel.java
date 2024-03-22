package it.gov.pagopa.payments.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferMetadataModel implements Serializable {
    /**
	 * 
	 */
	private static final long serialVersionUID = -3849684007832934581L;
	
	private String key;
    private String value;
}
