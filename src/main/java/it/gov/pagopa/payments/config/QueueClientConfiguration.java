package it.gov.pagopa.payments.config;

import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import it.gov.pagopa.payments.config.logging.AzureLoggingPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueueClientConfiguration {

    private static String QUEUE_NAME;

    private static String CONNECTION_STRING;

    @Value("${azure.queue.connection.string}")
    public void setConnectionStringStatic(String connectionString) {
        QueueClientConfiguration.CONNECTION_STRING = connectionString;
    }

    @Value("${azure.queue.queueName}")
    public void setTableNameStatic(String queueName) {
        QueueClientConfiguration.QUEUE_NAME = queueName;
    }

    @Bean
    public QueueClient queueClientConfig(
            AzureLoggingPolicy.Factory loggingPolicyFactory){
        return new QueueClientBuilder()
                .queueName(QUEUE_NAME)
                .connectionString(CONNECTION_STRING)
                .addPolicy(AzureLoggingPolicy.correlationPolicy())
                .addPolicy(loggingPolicyFactory.create(AzureLoggingPolicy.QUEUE))
                .buildClient();
    }
}
