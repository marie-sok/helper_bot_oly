package com.example.helper_bot_oly;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReminderTelegramBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReminderTelegramBotApplication.class, args);
    }
}