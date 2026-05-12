package com.batteryplus.sync.mercadopago_sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

@SpringBootApplication(exclude = {
		org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
		org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration.class
})
public class MercadoPagoSyncApplication {
	public static void main(String[] args) {
		SpringApplication.run(MercadoPagoSyncApplication.class, args);
	}
}