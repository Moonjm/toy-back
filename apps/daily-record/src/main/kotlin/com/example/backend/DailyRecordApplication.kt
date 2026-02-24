package com.example.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class DailyRecordApplication

fun main(args: Array<String>) {
    runApplication<DailyRecordApplication>(*args)
}
