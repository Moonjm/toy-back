package com.toy.backend.tesla

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import javax.sql.DataSource

/**
 * **기본 DataSource까지 손으로 정의하는 이유가 있다.** 보조 DataSource 빈을 등록하는 순간
 * `DataSourceAutoConfiguration.PooledDataSourceConfiguration`의
 * `@ConditionalOnMissingBean({DataSource, XADataSource})`가 꺼져서 **기존 daily-record
 * DataSource가 통째로 사라진다.** 자동설정이 만들어 주던 것을 여기서 다시 만든다.
 *
 * **주입 지점마다 `@Qualifier`를 붙이는 이유도 있다.** 같은 타입의 빈이 둘씩이고 한쪽에
 * `@Primary`가 붙어 있는데, Spring은 **이름 일치보다 `@Primary`를 먼저 본다**. 파라미터
 * 이름을 `teslaMate...`로 지어도 daily-record 쪽이 주입되어, TeslaMate DataSource가
 * daily-record 설정으로 만들어진다 — 붙은 척하며 다른 DB를 읽는다.
 *
 * `@Primary`가 빠지면 JPA·트랜잭션 매니저가 어느 쪽을 쓸지 몰라 기동에 실패하거나, 더 나쁘게는
 * **TeslaMate DB를 daily-record로 착각해 `ddl-auto: update`가 거기에 테이블을 만든다.**
 *
 * `teslaMateJdbcClient`가 `HikariDataSource`가 아니라 `DataSource`를 받는 이유:
 * `common-core`가 물고 있는 p6spy(datasource-decorator)가 모든 DataSource 빈을
 * `DecoratedDataSource`로 감싸므로 구체 타입으로는 주입되지 않는다.
 *
 * 이 빈 때문에 `JdbcClientAutoConfiguration`이 backing off 한다 — 이 앱의 다른 코드가
 * `JdbcClient`를 쓰지 않으므로 잃는 것이 없다.
 */
@Configuration
class TeslaMateDataSourceConfig {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    fun dataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    fun dataSource(
        @Qualifier("dataSourceProperties") properties: DataSourceProperties,
    ): HikariDataSource = properties.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Bean
    @ConfigurationProperties("teslamate.datasource")
    fun teslaMateDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean
    @ConfigurationProperties("teslamate.datasource.hikari")
    fun teslaMateDataSource(
        @Qualifier("teslaMateDataSourceProperties") properties: DataSourceProperties,
    ): HikariDataSource = properties.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Bean
    fun teslaMateJdbcClient(
        @Qualifier("teslaMateDataSource") teslaMateDataSource: DataSource,
    ): JdbcClient = JdbcClient.create(teslaMateDataSource)
}
