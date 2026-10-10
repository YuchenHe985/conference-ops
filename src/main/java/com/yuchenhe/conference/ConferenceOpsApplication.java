package com.yuchenhe.conference;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ConferenceOpsApplication {
  public static void main(String[] args) {
    SpringApplication.run(ConferenceOpsApplication.class, args);
  }

  /** Injected wherever "now" matters, so tests can pin time. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
