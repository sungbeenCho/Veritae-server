package com.veritae.veritae_server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VeritaeServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(VeritaeServerApplication.class, args);
	}

}
