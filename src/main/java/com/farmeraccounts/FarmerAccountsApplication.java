package com.farmeraccounts;

import com.farmeraccounts.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class FarmerAccountsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FarmerAccountsApplication.class, args);
    }
}
