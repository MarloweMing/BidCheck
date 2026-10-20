package cn.bidcheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BidCheckApplication {
    public static void main(String[] args) { SpringApplication.run(BidCheckApplication.class, args); }
}
