package com.example.backend.common.utils

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.JpqlQueryable
import com.linecorp.kotlinjdsl.querymodel.jpql.select.SelectQuery
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable

fun <T : Any> KotlinJdslJpqlExecutor.findAllNotNull(init: Jpql.() -> JpqlQueryable<SelectQuery<T>>): List<T> =
    this.findAll(init = init).filterNotNull()

fun <T : Any> KotlinJdslJpqlExecutor.findPageNotNull(
    pageable: Pageable,
    init: Jpql.() -> JpqlQueryable<SelectQuery<T>>,
): Page<T> {
    val page = this.findPage(pageable, init = init)
    return PageImpl(page.content.filterNotNull(), pageable, page.totalElements)
}
