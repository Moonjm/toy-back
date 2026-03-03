package com.toy.backend.common.config

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.SharedEntityManagerCreator

@Configuration
class KotlinJdslConfig {
    @Bean
    fun entityManager(entityManagerFactory: EntityManagerFactory): EntityManager =
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)
}