package it.gov.pagopa.payments.config;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BlobStorageClientConfiguration {

    @Bean
    BlobContainerClient deadLetterBlobContainerClient(
            @Value("${dead.letter.storage.connection.string}") String connectionString,
            @Value("${dead.letter.storage.container.name}") String containerName) {

        return new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient()
                .getBlobContainerClient(containerName);
    }
}