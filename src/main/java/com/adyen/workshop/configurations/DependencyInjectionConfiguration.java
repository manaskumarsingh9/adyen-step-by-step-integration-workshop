package com.adyen.workshop.configurations;

import com.adyen.Client;
import com.adyen.Config;
import com.adyen.enums.Environment;
import com.adyen.service.checkout.ModificationsApi;
import com.adyen.service.checkout.PaymentsApi;
import com.adyen.service.checkout.RecurringApi;
import com.adyen.util.HMACValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DependencyInjectionConfiguration {
    private final ApplicationConfiguration applicationConfiguration;

    public DependencyInjectionConfiguration(ApplicationConfiguration applicationConfiguration) {
        this.applicationConfiguration = applicationConfiguration;
    }

    @Bean
    Client client() {
        // Step 4
        var config = new Config();
        config.setApiKey(applicationConfiguration.getAdyenApiKey());
        config.setEnvironment(Environment.TEST);

        return new Client(config);
    }

    @Bean
    PaymentsApi paymentsApi(Client client) {
        client.setApplicationName("[adyen-step-by-step-integration-workshop workshop-checkout-example]");
        return new PaymentsApi(client);
    }

    @Bean
    RecurringApi recurringApi(Client client) {
        return new RecurringApi(client);
    }

    @Bean
    ModificationsApi modificationsApi(Client client) {
        return new ModificationsApi(client);
    }

    @Bean
    HMACValidator hmacValidator() {
        return new HMACValidator();
    }
}
