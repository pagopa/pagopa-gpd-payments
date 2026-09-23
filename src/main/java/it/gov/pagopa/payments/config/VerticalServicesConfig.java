package it.gov.pagopa.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "service.gps.vertical")
public class VerticalServicesConfig {

    private Map<String, ServiceConfig> serviceMap = new HashMap<>();

    public Map<String, ServiceConfig> getServiceMap() {
        return serviceMap;
    }

    public void setServiceMap(Map<String, ServiceConfig> serviceMap) {
        this.serviceMap = serviceMap;
    }

    public ServiceConfig getServiceConfigByServiceId(String idServizio) {
        return serviceMap.get(idServizio);
    }

    public String getUrlByServiceId(String idServizio) {
        ServiceConfig serviceConfig = serviceMap.get(idServizio);
        return serviceConfig != null ? serviceConfig.getUrlTarget() : "http://default-url";
    }

    public String getSubscriptionKeyByServiceId(String idServizio) {
        ServiceConfig serviceConfig = serviceMap.get(idServizio);
        return serviceConfig != null ? serviceConfig.getSubscriptionKey() : null;
    }

    public static class ServiceConfig {

        private String urlTarget;
        private String subscriptionKey;

        public String getUrlTarget() {
            return urlTarget;
        }

        public void setUrlTarget(String urlTarget) {
            this.urlTarget = urlTarget;
        }

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }
    }
}
