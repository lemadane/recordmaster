package io.lemadane.recordmaster.demo;

import io.lemadane.recordmaster.RecordDatabase;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public RecordDatabase recordDatabase() throws IOException {
        Path dbPath = Files.createTempDirectory("recordmaster-demo-db");
        return RecordDatabase.open(dbPath);
    }
}
