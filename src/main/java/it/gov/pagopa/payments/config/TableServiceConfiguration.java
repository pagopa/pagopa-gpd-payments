package it.gov.pagopa.payments.config;

import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import io.netty.handler.logging.LogLevel;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

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
    HttpClient nettyHttpClient =
        HttpClient.create()
            .resolver(DefaultAddressResolverGroup.INSTANCE)
            .wiretap(
                "reactor.netty.http.client.HttpClient",
                LogLevel.DEBUG,
                AdvancedByteBufFormat.TEXTUAL)
            .httpResponseDecoder(spec -> spec.maxHeaderSize(8192 * 2));

    var azureHttpClient = new NettyAsyncHttpClientBuilder(nettyHttpClient).build();

    return new TableClientBuilder()
        .connectionString(CONNECTION_STRING)
        .tableName(TABLE_NAME)
        .httpClient(azureHttpClient)
        .buildClient();
  }
}
