package it.gov.pagopa.payments.config;

import com.azure.core.util.ConfigurationBuilder;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TableServiceConfiguration {
    private static String TABLE_NAME;

    private static String CONNECTION_STRING;

    @Value("${azure.tables.connection.string}")
    public void setConnectionStringStatic(String connectionString) {
        TableServiceConfiguration.CONNECTION_STRING = connectionString;
    }

    @Value("${azure.tables.tableName}")
    public void setTableNameStatic(String tableName) {
        TableServiceConfiguration.TABLE_NAME = tableName;
    }

    @Bean
    public TableClient tableClientConfiguration() {
        var config = new ConfigurationBuilder()
                .putProperty("MAX_HTTP_HEADER_SIZE_IN_BYTES", "16384")
                .build();
        return new TableClientBuilder()
                .configuration(config)
                .connectionString(CONNECTION_STRING)
                .tableName(TABLE_NAME)
                .buildClient();
    }



}
