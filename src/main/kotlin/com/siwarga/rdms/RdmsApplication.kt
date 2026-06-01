package com.siwarga.rdms

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class RdmsApplication

fun main(args: Array<String>) {
    runApplication<RdmsApplication>(*args)
}
