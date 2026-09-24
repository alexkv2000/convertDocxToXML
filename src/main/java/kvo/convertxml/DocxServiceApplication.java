package kvo.convertxml;

import kvo.convertxml.config.ImanProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(ImanProperties.class)
@EnableScheduling
public class DocxServiceApplication {

    public static void main(String[] args){
        SpringApplication.run(DocxServiceApplication.class,args);
    }
}