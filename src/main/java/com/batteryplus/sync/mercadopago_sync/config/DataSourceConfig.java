package com.batteryplus.sync.mercadopago_sync.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    // --- MercadoPagoSync DB ---
    @Bean(name = "mercadopagoDataSource")
    @ConditionalOnProperty(name = "app.datasource.mercadopago.enabled", havingValue = "true")
    @ConfigurationProperties(prefix = "app.datasource.mercadopago")
    public DataSource mercadopagoDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = "mercadopagoJdbcTemplate")
    @ConditionalOnProperty(name = "app.datasource.mercadopago.enabled", havingValue = "true")
    public JdbcTemplate mercadopagoJdbcTemplate(
            @Qualifier("mercadopagoDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // --- Verina DB ---
    @Bean(name = "verinaDataSource")
    @ConditionalOnProperty(name = "app.datasource.verina.enabled", havingValue = "true")
    @ConfigurationProperties(prefix = "app.datasource.verina")
    public DataSource verinaDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = "verinaJdbcTemplate")
    @ConditionalOnProperty(name = "app.datasource.verina.enabled", havingValue = "true")
    public JdbcTemplate verinaJdbcTemplate(
            @Qualifier("verinaDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    // --- BatteryPlus DB (lectura) ---
    @Bean(name = "batteryplusDataSource")
    @ConditionalOnProperty(name = "app.datasource.batteryplus.enabled", havingValue = "true")
    @ConfigurationProperties(prefix = "app.datasource.batteryplus")
    public DataSource batteryplusDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = "batteryplusJdbcTemplate")
    @ConditionalOnProperty(name = "app.datasource.batteryplus.enabled", havingValue = "true")
    public JdbcTemplate batteryplusJdbcTemplate(
            @Qualifier("batteryplusDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}