package io.docpilot.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class AppConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean(destroyMethod = "close")
    public DataSource dataSource(AppProperties props) throws IOException {
        Path sqlitePath = props.persistence().sqliteFilePath();
        Files.createDirectories(sqlitePath.getParent());

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setBusyTimeout(props.persistence().busyTimeoutMs());
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqliteConfig.setTempStore(SQLiteConfig.TempStore.MEMORY);
        sqliteConfig.setCacheSize((int) -props.persistence().cacheSizeKb());
        sqliteConfig.setLockingMode(SQLiteConfig.LockingMode.NORMAL);
        sqliteConfig.setReadUncommitted(false);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(sqliteConfig);
        sqliteDataSource.setUrl("jdbc:sqlite:" + sqlitePath);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(sqliteDataSource);
        hikariConfig.setPoolName("doc-mcp-sqlite");
        hikariConfig.setMaximumPoolSize(props.persistence().poolSize());
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(10_000);
        hikariConfig.setValidationTimeout(5_000);
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.setInitializationFailTimeout(-1);
        hikariConfig.setIdleTimeout(60_000);
        hikariConfig.setMaxLifetime(0);
        hikariConfig.setAutoCommit(true);

        return new HikariDataSource(hikariConfig);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
