package com.engly.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Injectable clock for TOTP time-step and lockout arithmetic (fixed in tests). */
@Configuration
public class MfaConfig {
    @Bean
    public Clock mfaClock() {
        return Clock.systemUTC();
    }
}
